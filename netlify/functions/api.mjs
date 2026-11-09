import { readFile } from "node:fs/promises";
import { randomBytes, timingSafeEqual } from "node:crypto";
import pg from "pg";
import { hashPassword, hashToken, validName, validPassword } from "../security.mjs";

export const config = {
  path: ["/health", "/register", "/login", "/logout", "/me", "/code", "/friends",
    "/friends/:id", "/friends/add", "/friends/remove", "/usage", "/usage/start",
    "/usage/hide", "/admin/users"]
};

let pool;
let schemaReady;
const fail = (status, message) => { throw Object.assign(new Error(message), { status }); };
const json = (data, status = 200) => Response.json(data, {
  status, headers: { "Cache-Control": "no-store" }
});
const ok = { ok: true };
const account = ({ id, name, admin, enabled }) => ({ id, name, admin, enabled });
const isId = id => Number.isInteger(id) && id > 0 && id <= 2147483647;

async function body(req) {
  const raw = await req.arrayBuffer();
  if (!raw.byteLength || raw.byteLength > 2_000_000) fail(413, "Petición demasiado grande o vacía.");
  let value;
  try { value = JSON.parse(new TextDecoder().decode(raw)); }
  catch { fail(400, "Petición inválida."); }
  if (!value || Array.isArray(value) || typeof value !== "object") fail(400, "Petición inválida.");
  return value;
}

function snapshot(days) {
  if (!Array.isArray(days) || days.length > 30) fail(400, "Historial inválido.");
  const dates = new Set();
  return days.map(day => {
    const { date, screen, apps } = day ?? {};
    if (typeof date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(date) || dates.has(date)
        || !Number.isInteger(screen) || screen < 0 || screen > 90_000_000
        || !apps || typeof apps !== "object" || Array.isArray(apps)) fail(400, "Día de uso inválido.");
    const timestamp = new Date(date);
    if (!Number.isFinite(+timestamp) || timestamp.toISOString().slice(0, 10) !== date) fail(400, "Fecha inválida.");
    const entries = Object.entries(apps);
    if (entries.length > 1000 || entries.some(([name, time]) =>
      !name.length || name.length > 300 || !Number.isInteger(time) || time < 0 || time > screen)) {
      fail(400, "Tiempo por aplicación inválido.");
    }
    dates.add(date);
    return { date, screen, apps };
  }).sort((a, b) => b.date.localeCompare(a.date));
}

async function throttle(key, count, now) {
  const { rows: [entry] } = await pool.query(`
    INSERT INTO rate_limits(key,used,expires) VALUES($1,1,$2+300)
    ON CONFLICT(key) DO UPDATE SET
      used=CASE WHEN rate_limits.expires <= $2 THEN 1 ELSE LEAST(rate_limits.used+1,$3+1) END,
      expires=CASE WHEN rate_limits.expires <= $2 THEN $2+300 ELSE rate_limits.expires END
    RETURNING used`, [key, now, count]);
  if (entry.used > count) fail(429, "Demasiados intentos. Intenta más tarde.");
}

async function transaction(action) {
  const db = await pool.connect();
  try {
    await db.query("BEGIN");
    const result = await action(db);
    await db.query("COMMIT");
    return result;
  } catch (error) {
    await db.query("ROLLBACK");
    throw error;
  } finally {
    db.release();
  }
}

async function prepareSchema() {
  const { rows: [state] } = await pool.query(
    "SELECT to_regclass('users') IS NOT NULL AS initialized");
  if (state.initialized) return;

  const schema = await readFile("netlify/schema.sql", "utf8");
  await transaction(async db => {
    await db.query("SELECT pg_advisory_xact_lock(209392207)");
    await db.query(schema);
  });
}

async function authenticate(req, ctx, route, now) {
  await throttle("auth:" + (ctx.ip || "unknown"), 18, now);
  const data = await body(req);
  const name = typeof data.name === "string" ? data.name.trim().toLowerCase() : "";
  if (!validName(name) || !validPassword(data.password)) fail(400, "Usuario o contraseña inválidos.");
  const token = randomBytes(32).toString("base64url");
  let user;
  if (route === "POST /login") {
    await throttle("login:" + name, 10, now);
    const result = await pool.query("SELECT * FROM users WHERE name=$1", [name]);
    user = result.rows[0];
    const digest = await hashPassword(data.password, user?.salt ?? Buffer.alloc(16));
    if (!user?.enabled || !timingSafeEqual(user.password, digest)) fail(401, "Usuario o contraseña incorrectos, o cuenta inactiva.");
  }
  const credentials = user ? null : randomBytes(16);
  const digest = credentials && await hashPassword(data.password, credentials);
  return transaction(async db => {
    if (!user) {
      const result = await db.query(
        "INSERT INTO users(name,salt,password) VALUES($1,$2,$3) ON CONFLICT DO NOTHING RETURNING *",
        [name, credentials, digest]);
      user = result.rows[0];
      if (!user) fail(409, "El usuario ya existe.");
    } else {
      const result = await db.query("SELECT enabled FROM users WHERE id=$1 FOR UPDATE", [user.id]);
      if (!result.rows[0]?.enabled) fail(401, "Cuenta inactiva.");
    }
    await db.query("DELETE FROM sessions WHERE expires<=$1", [now]);
    await db.query("DELETE FROM rate_limits WHERE expires<=$1", [now]);
    await db.query("INSERT INTO sessions VALUES($1,$2,$3)", [hashToken(token), user.id, now + 30*86400]);
    return { token, user: account(user) };
  });
}

async function handle(req, ctx, route, now) {
  if (route === "GET /health") {
    await pool.query("SELECT 1 FROM users LIMIT 1");
    return ok;
  }
  if (route === "POST /login" || route === "POST /register") return authenticate(req, ctx, route, now);
  const auth = req.headers.get("authorization");
  if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(auth ?? "")) fail(401, "Inicia sesión.");
  const token = hashToken(auth.slice(7));
  const result = await pool.query(`
    SELECT u.id,u.name,u.admin,u.enabled FROM users u JOIN sessions s ON u.id=s.uid
    WHERE s.token=$1 AND s.expires>$2 AND u.enabled`, [token, now]);
  const user = result.rows[0];
  if (!user) fail(401, "La sesión terminó. Inicia sesión nuevamente.");
  const uid = user.id;

  switch (route) {
    case "GET /me": return { user: account(user) };
    case "POST /logout":
      await transaction(async db => {
        await db.query("DELETE FROM sessions WHERE token=$1", [token]);
        await db.query("UPDATE users SET lease=NULL,updated=NULL WHERE id=$1", [uid]);
      });
      return ok;
    case "POST /code": {
      await throttle("code:" + uid, 12, now);
      const code = randomBytes(5).toString("hex").toUpperCase();
      await pool.query(`INSERT INTO codes VALUES($1,$2,$3)
        ON CONFLICT(owner) DO UPDATE SET code=EXCLUDED.code,expires=EXCLUDED.expires`, [code, uid, now+86400]);
      return { code };
    }
    case "POST /friends/add": {
      await throttle("invite:" + uid, 15, now);
      const { code } = await body(req);
      if (typeof code !== "string" || !/^[0-9A-F]{10}$/.test(code)) fail(400, "Ingresa los 10 caracteres del código.");
      await transaction(async db => {
        const { rows: [match] } = await db.query(`DELETE FROM codes c USING users u
          WHERE c.code=$1 AND c.expires>$2 AND c.owner<>$3 AND c.owner=u.id AND u.enabled
          RETURNING c.owner`, [code, now, uid]);
        if (!match) fail(400, "Código inválido, vencido o propio.");
        await db.query("INSERT INTO friends VALUES(LEAST($1::int,$2::int),GREATEST($1::int,$2::int)) ON CONFLICT DO NOTHING",
          [uid, match.owner]);
      });
      return ok;
    }
    case "POST /friends/remove": {
      const { id } = await body(req);
      if (!isId(id)) fail(400, "Amigo inválido.");
      await pool.query("DELETE FROM friends WHERE a=LEAST($1::int,$2::int) AND b=GREATEST($1::int,$2::int)", [uid, id]);
      return ok;
    }
    case "GET /friends": {
      const { rows } = await pool.query(`SELECT id,name,updated FROM friends JOIN users
        ON id=CASE WHEN a=$1 THEN b ELSE a END WHERE (a=$1 OR b=$1) AND enabled ORDER BY name`, [uid]);
      return { friends: rows, now };
    }
    case "POST /usage/start": {
      const lease = randomBytes(24).toString("base64url");
      await pool.query("UPDATE users SET lease=$1 WHERE id=$2 AND enabled", [lease, uid]);
      return { lease };
    }
    case "POST /usage": {
      const data = await body(req);
      if (typeof data.lease !== "string" || data.lease.length !== 32) fail(409, "La transmisión terminó.");
      const days = snapshot(data.days);
      const updated = await pool.query(
        "UPDATE users SET snapshot=$1,updated=$2 WHERE id=$3 AND enabled AND lease=$4",
        [JSON.stringify(days), now, uid, data.lease]);
      if (!updated.rowCount) fail(409, "La transmisión terminó.");
      return ok;
    }
    case "POST /usage/hide":
      await pool.query("UPDATE users SET snapshot=NULL,updated=NULL,lease=NULL WHERE id=$1", [uid]);
      return ok;
    case "GET /admin/users": {
      if (!user.admin) fail(403, "Acceso de administrador requerido.");
      const { rows } = await pool.query("SELECT id,name,admin,enabled FROM users ORDER BY admin DESC,name");
      return { users: rows };
    }
    case "POST /admin/users": {
      if (!user.admin) fail(403, "Acceso de administrador requerido.");
      const { id, enabled } = await body(req);
      if (!isId(id) || id === uid || typeof enabled !== "boolean") fail(400, "Cuenta inválida.");
      await transaction(async db => {
        await db.query(`UPDATE users SET enabled=$1,lease=CASE WHEN $1 THEN lease END,
          updated=CASE WHEN $1 THEN updated END WHERE id=$2 AND NOT admin`, [enabled, id]);
        if (!enabled) await db.query("DELETE FROM sessions WHERE uid=$1", [id]);
      });
      return ok;
    }
    default: {
      const match = /^GET \/friends\/(\d{1,10})$/.exec(route);
      const id = Number(match?.[1]);
      if (!isId(id)) fail(404, "Ruta no disponible.");
      const { rows: [friend] } = await pool.query(`
        SELECT name,updated,snapshot AS days FROM users JOIN friends
        ON friends.a=LEAST($1::int,$2::int) AND friends.b=GREATEST($1::int,$2::int)
        WHERE id=$2 AND enabled`, [uid, id]);
      if (!friend) fail(404, "Esta amistad ya no está disponible.");
      return { ...friend, days: friend.days ?? [], now };
    }
  }
}

export default async (req, ctx) => {
  try {
    if (!process.env.DATABASE_URL) fail(503, "Configura la base de datos del servidor.");
    if (!pool) {
      pool = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 2, connectionTimeoutMillis: 6000, enableChannelBinding: true });
      pool.on("error", error => console.error("Conexión inactiva de PostgreSQL", error.code ?? error.name));
    }
    schemaReady ??= prepareSchema().catch(error => {
      schemaReady = undefined;
      throw error;
    });
    await schemaReady;
    const route = req.method + " " + new URL(req.url).pathname;
    return json(await handle(req, ctx, route, Math.floor(Date.now()/1000)));
  } catch (error) {
    if (error.status) return json({ error: error.message }, error.status);
    console.error("Error de la API", error.code ?? error.name);
    return json({ error: "Error del servidor." }, 500);
  }
};
