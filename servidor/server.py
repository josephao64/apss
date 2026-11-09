"""API de Tiempo en Pantalla. Detrás de un proxy HTTPS."""
import contextlib
import getpass
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB = os.getenv("SCREEN_DB", "pantalla.sqlite3")
BODY_LIMIT = 2_000_000
NAME = re.compile(r"[a-z0-9_]{3,24}")
AUTH_SLOTS = threading.BoundedSemaphore(2)
THROTTLE_LOCK = threading.Lock()
THROTTLE = {}
SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
 id INTEGER PRIMARY KEY, name TEXT UNIQUE NOT NULL, salt BLOB NOT NULL,
 password BLOB NOT NULL, admin INTEGER NOT NULL DEFAULT 0,
 enabled INTEGER NOT NULL DEFAULT 1, updated INTEGER, snapshot TEXT, lease TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS one_admin ON users(admin) WHERE admin=1;
CREATE TABLE IF NOT EXISTS sessions(
 token TEXT PRIMARY KEY, uid INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS codes(
 code TEXT PRIMARY KEY, owner INTEGER UNIQUE NOT NULL REFERENCES users(id), expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS friends(
 a INTEGER REFERENCES users(id), b INTEGER REFERENCES users(id),
 PRIMARY KEY(a,b), CHECK(a<b)
);
"""

class Rejected(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message

@contextlib.contextmanager
def database():
    connection = sqlite3.connect(DB, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    try:
        with connection:
            yield connection
    finally:
        connection.close()

def credentials(name, password):
    if not isinstance(name, str) or not isinstance(password, str):
        raise Rejected(400, "Escribe un usuario y una contraseña.")
    name = name.strip().lower()
    if not NAME.fullmatch(name) or not 8 <= len(password) <= 128:
        raise Rejected(400, "Usuario de 3 a 24 letras, números o guiones bajos y contraseña de 8 a 128 caracteres.")
    return name, password

def password_hash(password, salt):
    with AUTH_SLOTS:
        return hashlib.scrypt(password.encode(), salt=salt, n=2**17, r=8, p=1,
                              maxmem=192 * 1024**2)

def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()

def account(row):
    return {"id": row["id"], "name": row["name"], "admin": bool(row["admin"]),
            "enabled": bool(row["enabled"])}

def throttle(key, count, window):
    now = time.monotonic()
    with THROTTLE_LOCK:
        if len(THROTTLE) >= 10000:
            for old in [k for k, (_, end) in THROTTLE.items() if end <= now]:
                THROTTLE.pop(old, None)
        if key not in THROTTLE and len(THROTTLE) >= 10000:
            raise Rejected(429, "Intenta nuevamente en unos minutos.")
        used, end = THROTTLE.get(key, (0, now))
        if end <= now:
            used, end = 0, now + window
        if used >= count:
            raise Rejected(429, "Demasiados intentos. Intenta más tarde.")
        THROTTLE[key] = used + 1, end

def snapshot(data):
    days = data.get("days")
    if not isinstance(days, list) or len(days) > 30:
        raise Rejected(400, "Historial inválido.")
    clean, dates = [], set()
    for day in days:
        if not isinstance(day, dict):
            raise Rejected(400, "Historial inválido.")
        stamp, total, apps = day.get("date"), day.get("screen"), day.get("apps")
        try:
            valid_date = isinstance(stamp, str) and date.fromisoformat(stamp).isoformat() == stamp
        except ValueError:
            valid_date = False
        if not valid_date or stamp in dates or type(total) is not int or not 0 <= total <= 90_000_000:
            raise Rejected(400, "Día de uso inválido.")
        if not isinstance(apps, dict) or len(apps) > 1000:
            raise Rejected(400, "Lista de aplicaciones inválida.")
        for name, duration in apps.items():
            if not 1 <= len(name) <= 300 or type(duration) is not int or not 0 <= duration <= total:
                raise Rejected(400, "Tiempo por aplicación inválido.")
        dates.add(stamp)
        clean.append({"date": stamp, "screen": total, "apps": apps})
    return json.dumps({"days": sorted(clean, key=lambda d: d["date"], reverse=True)},
                      ensure_ascii=False, separators=(",", ":"))

class Api(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise Rejected(400, "Petición inválida.")
        if not 0 < length <= BODY_LIMIT:
            raise Rejected(413, "Petición demasiado grande o vacía.")
        try:
            obj = json.loads(self.rfile.read(length))
            if isinstance(obj, dict):
                return obj
        except (ValueError, RecursionError):
            pass
        raise Rejected(400, "Petición inválida.")

    def respond(self):
        try:
            with database() as db:
                payload = self.route(db)
            status = 200
        except Rejected as exc:
            status, payload = exc.status, {"error": exc.message}
        except Exception:
            status, payload = 500, {"error": "Error del servidor."}
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def route(self, db):
        now = int(time.time())
        route = self.command, self.path
        if route == ("GET", "/health"):
            return {"ok": True}
        if route in (("POST", "/login"), ("POST", "/register")):
            # Caddy overwrites this header. The API port is private in Compose.
            ip = self.client_address[0]
            if os.getenv("SCREEN_TRUST_CADDY") == "1":
                ip = self.headers.get("X-Client-IP", ip)
            throttle(("auth", ip), 18, 300)
            body = self.body()
            name, password = credentials(body.get("name"), body.get("password"))
            if route[1] == "/register":
                salt = secrets.token_bytes(16)
                digest = password_hash(password, salt)
                try:
                    db.execute("INSERT INTO users(name,salt,password) VALUES(?,?,?)", (name, salt, digest))
                except sqlite3.IntegrityError:
                    raise Rejected(409, "El usuario ya existe.")
            row = db.execute("SELECT * FROM users WHERE name=?", (name,)).fetchone()
            if route[1] == "/login":
                throttle(("login", name), 10, 300)
                digest = password_hash(password, row["salt"] if row else bytes(16))
                if not row or not hmac.compare_digest(row["password"], digest) or not row["enabled"]:
                    raise Rejected(401, "Usuario o contraseña incorrectos, o cuenta inactiva.")
            token = secrets.token_urlsafe(32)
            db.execute("DELETE FROM sessions WHERE expires<=?", (now,))
            db.execute("INSERT INTO sessions VALUES(?,?,?)", (token_hash(token), row["id"], now + 30*86400))
            return {"token": token, "user": account(row)}

        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer ") or not 40 <= len(auth) <= 80:
            raise Rejected(401, "Inicia sesión.")
        hashed = token_hash(auth[7:])
        user = db.execute("""SELECT users.* FROM sessions JOIN users ON users.id=sessions.uid
                             WHERE token=? AND expires>? AND enabled=1""", (hashed, now)).fetchone()
        if user is None:
            raise Rejected(401, "La sesión terminó. Inicia sesión nuevamente.")
        uid = user["id"]
        if route == ("GET", "/me"):
            return {"user": account(user)}
        if route == ("POST", "/logout"):
            db.execute("DELETE FROM sessions WHERE token=?", (hashed,))
            db.execute("UPDATE users SET lease=NULL,updated=NULL WHERE id=?", (uid,))
        elif route == ("POST", "/code"):
            throttle(("code", uid), 12, 300)
            code = secrets.token_hex(5).upper()
            db.execute("DELETE FROM codes WHERE owner=? OR expires<=?", (uid, now))
            db.execute("INSERT INTO codes VALUES(?,?,?)", (code, uid, now + 86400))
            return {"code": code}
        elif route == ("POST", "/friends/add"):
            throttle(("invite", uid), 15, 300)
            code = self.body().get("code")
            if not isinstance(code, str) or not re.fullmatch("[0-9A-F]{10}", code):
                raise Rejected(400, "Ingresa los 10 caracteres del código.")
            db.execute("BEGIN IMMEDIATE")
            owner = db.execute("""SELECT owner FROM codes JOIN users ON owner=users.id
                                  WHERE code=? AND expires>? AND enabled=1""", (code, now)).fetchone()
            if not owner or owner["owner"] == uid:
                raise Rejected(400, "Código inválido, vencido o propio.")
            db.execute("INSERT OR IGNORE INTO friends VALUES(?,?)", sorted((owner["owner"], uid)))
            db.execute("DELETE FROM codes WHERE code=?", (code,))
        elif route == ("POST", "/friends/remove"):
            other = self.body().get("id")
            if type(other) is not int or not 0 < other < 2**63:
                raise Rejected(400, "Amigo inválido.")
            db.execute("DELETE FROM friends WHERE a=? AND b=?", sorted((uid, other)))
        elif route == ("GET", "/friends"):
            rows = db.execute("""SELECT id,name,updated FROM friends JOIN users
                              ON id=CASE WHEN a=? THEN b ELSE a END
                              WHERE (a=? OR b=?) AND enabled=1 ORDER BY name""", (uid, uid, uid))
            return {"friends": [dict(row) for row in rows], "now": now}
        elif self.command == "GET" and re.fullmatch(r"/friends/[0-9]{1,18}", self.path):
            other = int(self.path.rsplit("/", 1)[1])
            a, b = sorted((uid, other))
            row = db.execute("""SELECT name,updated,snapshot FROM users JOIN friends
                              ON friends.a=? AND friends.b=? WHERE id=? AND enabled=1""", (a, b, other)).fetchone()
            if not row:
                raise Rejected(404, "Esta amistad ya no está disponible.")
            return {"name": row["name"], "updated": row["updated"], "now": now,
                    "days": json.loads(row["snapshot"])["days"] if row["snapshot"] else []}
        elif route == ("POST", "/usage/start"):
            lease = secrets.token_urlsafe(24)
            db.execute("UPDATE users SET lease=? WHERE id=? AND enabled=1", (lease, uid))
            return {"lease": lease}
        elif route == ("POST", "/usage"):
            body = self.body()
            value = snapshot(body)
            lease = body.get("lease")
            if not isinstance(lease, str) or len(lease) != 32:
                raise Rejected(409, "La transmisión terminó.")
            updated = db.execute("""UPDATE users SET snapshot=?,updated=?
                                    WHERE id=? AND enabled=1 AND lease=?""", (value, now, uid, lease))
            if updated.rowcount != 1:
                raise Rejected(409, "La transmisión terminó.")
        elif route == ("POST", "/usage/hide"):
            db.execute("UPDATE users SET snapshot=NULL,updated=NULL,lease=NULL WHERE id=?", (uid,))
        elif route in (("GET", "/admin/users"), ("POST", "/admin/users")):
            if not user["admin"]:
                raise Rejected(403, "Acceso de administrador requerido.")
            if self.command == "GET":
                return {"users": [account(r) for r in db.execute("SELECT * FROM users ORDER BY admin DESC,name")]}
            body = self.body()
            other, enabled = body.get("id"), body.get("enabled")
            if type(other) is not int or not 0 < other < 2**63 or type(enabled) is not bool or other == uid:
                raise Rejected(400, "Cuenta inválida.")
            db.execute("UPDATE users SET enabled=? WHERE id=? AND admin=0", (enabled, other))
            if not enabled:
                db.execute("DELETE FROM sessions WHERE uid=?", (other,))
                db.execute("UPDATE users SET lease=NULL,updated=NULL WHERE id=?", (other,))
        else:
            raise Rejected(404, "Ruta no disponible.")
        return {"ok": True}

if __name__ == "__main__":
    with database() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript(SCHEMA)
    if len(sys.argv) == 3 and sys.argv[1] == "admin":
        password = getpass.getpass("Contraseña del administrador: ")
        if password != getpass.getpass("Confirma la contraseña: "):
            sys.exit("Las contraseñas no coinciden.")
        try:
            name, password = credentials(sys.argv[2], password)
            salt = secrets.token_bytes(16)
            digest = password_hash(password, salt)
            with database() as db:
                db.execute("INSERT INTO users(name,salt,password,admin) VALUES(?,?,?,1)", (name, salt, digest))
        except Rejected as exc:
            sys.exit(exc.message)
        except sqlite3.IntegrityError:
            sys.exit("Ya existe un administrador o el nombre está en uso.")
        print("Administrador creado.")
    else:
        server = ThreadingHTTPServer((os.getenv("SCREEN_BIND", "127.0.0.1"),
                                      int(os.getenv("SCREEN_PORT", os.getenv("PORT", "8080")))), Api)
        server.serve_forever()
