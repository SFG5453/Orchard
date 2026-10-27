import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { afterEach, beforeEach, it, mock } from "node:test";

import { APPLE_DAILY_TOKENS, APPLE_DEFAULT_ORIGINS, APPLE_DEVELOPER_TOKEN_SECONDS, purgeAppleTokenUsage } from "../src/apple.js";
import worker from "../src/index.js";
import { signJwt } from "../src/jwt.js";
import { createD1 } from "./d1.js";

const NOW = 1792022400;
const PATH = "/apple/developer-token";
const CREDENTIALS = ["APPLE_MUSIC_TEAM_ID", "APPLE_MUSIC_KEY_ID", "APPLE_MUSIC_PRIVATE_KEY"];

let env, publicKey, rateKeys, accountKeys, rateAllowed, accountAllowed, token;

async function session(user = "user-1", device = "device-1") {
  env.DB.raw.prepare("INSERT OR IGNORE INTO users (id, created_at, updated_at) VALUES (?, 0, 0)").run(user);
  env.DB.raw.prepare(`INSERT OR IGNORE INTO devices
    (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at)
    VALUES (?, ?, 'Phone', 'android', ?, 0, 0, ?)`).run(device, user, `hash-${device}`, NOW);
  return signJwt({ iss: env.PUBLIC_URL, aud: "orchard", sub: user, did: device, iat: NOW, exp: NOW + 900 }, env.SIGNING_KEY);
}

function call({ method = "GET", path = PATH, headers = {}, auth = token } = {}) {
  return worker.fetch(new Request(`https://account.test${path}`, {
    method, headers: { "cf-connecting-ip": "203.0.113.7", ...(auth ? { authorization: `Bearer ${auth}` } : {}), ...headers },
  }), env);
}

function claimsOf(body) {
  return body.developer_token.split(".").slice(0, 2).map(part => JSON.parse(Buffer.from(part, "base64url")));
}

function assertSignature(token, key = publicKey) {
  const parts = token.split(".");
  assert.equal(parts.length, 3);
  const signature = Buffer.from(parts[2], "base64url");
  assert.equal(signature.length, 64);
  assert.equal(verify("sha256", Buffer.from(`${parts[0]}.${parts[1]}`), {
    key, dsaEncoding: "ieee-p1363",
  }, signature), true);
}

beforeEach(async () => {
  mock.method(Date, "now", () => NOW * 1000);
  const keys = generateKeyPairSync("ec", {
    namedCurve: "prime256v1", privateKeyEncoding: { type: "pkcs8", format: "pem" },
  });
  publicKey = keys.publicKey;
  rateKeys = [];
  accountKeys = [];
  rateAllowed = true;
  accountAllowed = true;
  const signing = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  env = {
    DB: createD1(),
    PUBLIC_URL: "https://account.test",
    SIGNING_KEY: JSON.stringify({ ...(await crypto.subtle.exportKey("jwk", signing.privateKey)), kid: "test" }),
    APPLE_MUSIC_TEAM_ID: "TEAM123456",
    APPLE_MUSIC_KEY_ID: "KEY1234567",
    APPLE_MUSIC_PRIVATE_KEY: keys.privateKey,
    AUTH_LIMITER: { async limit({ key }) {
      rateKeys.push(key);
      return { success: rateAllowed };
    } },
    APPLE_LIMITER: { async limit({ key }) {
      accountKeys.push(key);
      return { success: accountAllowed };
    } },
  };
  token = await session();
});

afterEach(() => mock.restoreAll());

it("issues a short-lived Apple ES256 developer token to an Orchard session", async () => {
  const response = await call();
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const body = await response.json();
  assert.deepEqual(Object.keys(body).sort(), ["developer_token", "expires_at"]);
  assert.equal(body.expires_at, NOW + APPLE_DEVELOPER_TOKEN_SECONDS);
  const [header, claims] = claimsOf(body);
  assert.deepEqual(header, { alg: "ES256", kid: env.APPLE_MUSIC_KEY_ID });
  assert.deepEqual(claims, { iss: env.APPLE_MUSIC_TEAM_ID, iat: NOW, exp: body.expires_at, origin: APPLE_DEFAULT_ORIGINS });
  assert.equal(claims.exp - claims.iat, APPLE_DEVELOPER_TOKEN_SECONDS);
  assertSignature(body.developer_token);
});

it("accepts PEM newlines from Wrangler secrets and quoted dotenv values", async () => {
  const pem = env.APPLE_MUSIC_PRIVATE_KEY;
  for (const value of [pem.replace(/\n/g, "\\n"), pem.replace(/\n/g, "\r\n")]) {
    env.APPLE_MUSIC_PRIVATE_KEY = value;
    const response = await call();
    assert.equal(response.status, 200);
    assertSignature((await response.json()).developer_token);
  }
});

it("trims configuration whitespace", async () => {
  for (const name of CREDENTIALS) env[name] = ` \n${env[name]}\n `;
  const response = await call();
  assert.equal(response.status, 200);
  const body = await response.json();
  assertSignature(body.developer_token);
  assert.equal(JSON.parse(Buffer.from(body.developer_token.split(".")[0], "base64url")).kid, "KEY1234567");
});

it("ignores caller-supplied signing claims and configuration", async () => {
  const response = await call({ path: `${PATH}?exp=9999999999&iss=attacker&kid=attacker&alg=none` });
  assert.equal(response.status, 200);
  const body = await response.json();
  const [header, claims] = claimsOf(body);
  assert.deepEqual(header, { alg: "ES256", kid: "KEY1234567" });
  assert.deepEqual(claims, { iss: "TEAM123456", iat: NOW, exp: NOW + APPLE_DEVELOPER_TOKEN_SECONDS, origin: APPLE_DEFAULT_ORIGINS });
  assertSignature(body.developer_token);
});

it("returns unavailable when any Apple credential is missing, including after issuing a token", async () => {
  assert.equal((await call()).status, 200);
  for (const name of CREDENTIALS) {
    const original = env[name];
    for (const missing of [undefined, "", " \n "]) {
      env[name] = missing;
      const response = await call();
      assert.equal(response.status, 503);
      assert.equal(response.headers.get("cache-control"), "no-store");
      assert.deepEqual(await response.json(), {
        error: "apple_music_unavailable", error_description: "Apple Music is unavailable",
      });
    }
    env[name] = original;
  }
});

it("rejects malformed Team IDs and Key IDs", async () => {
  for (const name of CREDENTIALS.slice(0, 2)) {
    const original = env[name];
    for (const invalid of ["short", "ABCDEFGHIJK", "lowercase1", "INVALID!12"]) {
      env[name] = invalid;
      const response = await call();
      assert.equal(response.status, 503);
      assert.equal((await response.json()).error, "apple_music_unavailable");
    }
    env[name] = original;
  }
});

it("rejects malformed keys and other curves without exposing key material", async () => {
  const errors = mock.method(console, "error", () => {});
  const { privateKey } = generateKeyPairSync("ec", {
    namedCurve: "secp384r1", privateKeyEncoding: { type: "pkcs8", format: "pem" },
  });
  for (const pem of ["secret-invalid-pem", "-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----", privateKey]) {
    env.APPLE_MUSIC_PRIVATE_KEY = pem;
    const response = await call();
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), {
      error: "apple_music_unavailable", error_description: "Apple Music is unavailable",
    });
  }
  assert.equal(errors.mock.calls.length, 3);
  for (const call of errors.mock.calls) {
    assert.deepEqual(call.arguments, ['{"event":"apple_music_signing_failed"}']);
  }
});

it("sanitizes signing failures", async () => {
  const errors = mock.method(console, "error", () => {});
  mock.method(crypto.subtle, "sign", async () => { throw new Error(env.APPLE_MUSIC_PRIVATE_KEY); });
  const response = await call();
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    error: "apple_music_unavailable", error_description: "Apple Music is unavailable",
  });
  assert.deepEqual(errors.mock.calls[0].arguments, ['{"event":"apple_music_signing_failed"}']);
});

it("uses a separate Apple rate-limit bucket per Cloudflare IP", async () => {
  assert.equal((await call()).status, 200);
  assert.equal((await call({ headers: { "cf-connecting-ip": "203.0.113.8" } })).status, 200);
  assert.deepEqual(rateKeys, ["apple:203.0.113.7", "apple:203.0.113.8"]);
});

it("rejects rate-limited requests before signing", async () => {
  rateAllowed = false;
  const sign = mock.method(crypto.subtle, "sign", async () => { throw new Error("must not sign"); });
  const response = await call();
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal((await response.json()).error, "slow_down");
  assert.equal(sign.mock.calls.length, 0);
});

it("supports local development without rate-limit bindings", async () => {
  delete env.AUTH_LIMITER;
  delete env.APPLE_LIMITER;
  assert.equal((await call()).status, 200);
});

it("only issues tokens through GET", async () => {
  for (const method of ["POST", "PUT", "DELETE"]) {
    assert.equal((await call({ method })).status, 404);
  }
  assert.deepEqual(rateKeys, []);
});

it("requires a live Orchard session before signing", async () => {
  const sign = mock.method(crypto.subtle, "sign", async () => { throw new Error("must not sign"); });
  for (const auth of ["", "not-a-jwt"]) assert.equal((await call({ auth })).status, 401);
  env.DB.raw.prepare("DELETE FROM devices WHERE id = ?").run("device-1");
  assert.equal((await call()).status, 401);
  assert.equal(sign.mock.calls.length, 0);
});

it("restricts tokens to configured page origins", async () => {
  env.APPLE_MUSIC_ORIGINS = " https://one.example , https://two.example ";
  const [, claims] = claimsOf(await (await call()).json());
  assert.deepEqual(claims.origin, ["https://one.example", "https://two.example"]);
});

it("rate-limits each account across IPs", async () => {
  await call({ headers: { "cf-connecting-ip": "203.0.113.9" } });
  assert.deepEqual(accountKeys, ["user-1"]);
  accountAllowed = false;
  const response = await call();
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
});

it("caps tokens per account per UTC day across devices", async () => {
  const other = await session("user-1", "device-2");
  for (let i = 0; i < APPLE_DAILY_TOKENS; i++) {
    assert.equal((await call({ auth: i % 2 ? token : other })).status, 200);
  }
  const rejected = await call();
  assert.equal(rejected.status, 429);
  assert.equal((await rejected.json()).error, "apple_daily_limit");
  assert.equal(rejected.headers.get("retry-after"), String(86400 - (NOW % 86400)));
  assert.equal((await call({ auth: await session("user-2", "device-3") })).status, 200);
});

it("purges usage older than yesterday", async () => {
  await call();
  const day = Math.floor(NOW / 86400);
  env.DB.raw.prepare("INSERT INTO apple_token_daily_usage (user_id, day, used) VALUES ('old', ?, 1)").run(day - 2);
  await purgeAppleTokenUsage(env, NOW);
  assert.deepEqual(env.DB.raw.prepare("SELECT user_id FROM apple_token_daily_usage").all().map(row => row.user_id), ["user-1"]);
});

it("issues Shazam tokens without an origin claim", async () => {
  const response = await call({ path: "/apple/shazam-token" });
  assert.equal(response.status, 200);
  const body = await response.json();
  const [header, claims] = claimsOf(body);
  assert.deepEqual(header, { alg: "ES256", kid: "KEY1234567" });
  assert.deepEqual(claims, { iss: "TEAM123456", iat: NOW, exp: NOW + APPLE_DEVELOPER_TOKEN_SECONDS });
  assertSignature(body.developer_token);
});

it("shares the daily cap and session check with Shazam tokens", async () => {
  assert.equal((await call({ path: "/apple/shazam-token", auth: null })).status, 401);
  for (let i = 0; i < APPLE_DAILY_TOKENS; i++) assert.equal((await call()).status, 200);
  assert.equal((await call({ path: "/apple/shazam-token" })).status, 429);
});
