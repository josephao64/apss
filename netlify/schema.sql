CREATE TABLE IF NOT EXISTS users (
  id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name text UNIQUE NOT NULL,
  salt bytea NOT NULL,
  password bytea NOT NULL,
  admin boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true,
  updated integer,
  snapshot jsonb,
  lease text
);
CREATE UNIQUE INDEX IF NOT EXISTS one_admin ON users(admin) WHERE admin;
CREATE TABLE IF NOT EXISTS sessions (
  token text PRIMARY KEY,
  uid integer NOT NULL REFERENCES users(id),
  expires integer NOT NULL
);
CREATE TABLE IF NOT EXISTS codes (
  code text PRIMARY KEY,
  owner integer UNIQUE NOT NULL REFERENCES users(id),
  expires integer NOT NULL
);
CREATE TABLE IF NOT EXISTS friends (
  a integer NOT NULL REFERENCES users(id),
  b integer NOT NULL REFERENCES users(id),
  PRIMARY KEY(a, b),
  CHECK(a < b)
);
CREATE TABLE IF NOT EXISTS rate_limits (
  key text PRIMARY KEY,
  used integer NOT NULL,
  expires integer NOT NULL
);
