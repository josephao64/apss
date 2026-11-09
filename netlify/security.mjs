import { createHash, scrypt as derive } from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(derive);
export const validName = value => typeof value === "string" && /^[a-z0-9_]{3,24}$/.test(value);
export const validPassword = value => typeof value === "string" && value.length >= 8 && value.length <= 128;
export const hashToken = value => createHash("sha256").update(value).digest("hex");
export const hashPassword = (value, salt) =>
  scrypt(value, salt, 64, { N: 2 ** 17, r: 8, p: 1, maxmem: 192 * 1024 ** 2 });
