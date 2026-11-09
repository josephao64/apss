import { readFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import { text } from "node:stream/consumers";
import pg from "pg";
import { hashPassword, validName, validPassword } from "./security.mjs";

const name = process.argv[2]?.trim().toLowerCase();
if (!process.env.DATABASE_URL || !validName(name) || process.stdin.isTTY) {
  console.error("Configura DATABASE_URL e indica el usuario. Pasa la contraseña por stdin.");
  process.exit(1);
}
const password = (await text(process.stdin)).replace(/\r?\n$/, "");
if (!validPassword(password)) throw new Error("Contraseña de 8 a 128 caracteres.");
const db = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1, enableChannelBinding: true });
try {
  await db.query(await readFile(new URL("./schema.sql", import.meta.url), "utf8"));
  const salt = randomBytes(16);
  const digest = await hashPassword(password, salt);
  const result = await db.query(
    "INSERT INTO users(name,salt,password,admin) VALUES($1,$2,$3,true) ON CONFLICT DO NOTHING",
    [name, salt, digest]);
  if (!result.rowCount) throw new Error("Ya existe un administrador o el usuario está ocupado.");
  console.log("Base de datos preparada y administrador creado.");
} finally {
  await db.end();
}
