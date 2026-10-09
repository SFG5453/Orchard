import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it, mock } from "node:test";

import { encodeJsonSegment, pkceChallenge, randomToken } from "../src/crypto.js";
import { verifyJwt } from "../src/jwt.js";
import worker from "../src/index.js";
import { REFRESH_IDLE_SECONDS, REFRESH_REUSE_GRACE_SECONDS, purgeExpired } from "../src/service.js";
import { createD1 } from "./d1.js";

const LOOPBACK = "http://127.0.0.1:53682/callback";
const ANDROID = "dev.sfg.orchard.mobile://account/callback";
const now = () => Math.floor(Date.now() / 1000);

async function makeEnv() {
  const { privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const jwk = { ...(await crypto.subtle.exportKey("jwk", privateKey)), kid: "test-key" };
  return {
    DB: createD1(),
    PUBLIC_URL: "https://account.test",
    GOOGLE_CLIENT_ID: "google-client",
    GOOGLE_CLIENT_SECRET: "google-secret",
    SIGNING_KEY: JSON.stringify(jwk),
  };
}

function fakeIdToken(claims) {
  return `${encodeJsonSegment({ alg: "RS256" })}.${encodeJsonSegment(claims)}.sig`;
}

let env;
let googleClaims;
let googleRequests;

function call(method, path, { body, token, headers = {} } = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers["content-type"] = "application/json";
  }
  if (token) init.headers.authorization = `Bearer ${token}`;
  return worker.fetch(new Request(`https://account.test${path}`, init), env);
}

async function authorize({ redirectUri = LOOPBACK } = {}) {
  const verifier = randomToken();
  const clientState = randomToken(16);
  const query = new URLSearchParams({
    redirect_uri: redirectUri,
    state: clientState,
    code_challenge: await pkceChallenge(verifier),
    code_challenge_method: "S256",
  });
  const start = await call("GET", `/auth/google/start?${query}`);
  assert.equal(start.status, 302);
  const google = new URL(start.headers.get("location"));
  assert.equal(google.origin, "https://accounts.google.com");

  const callback = await call("GET", `/auth/google/callback?code=google-code&state=${google.searchParams.get("state")}`);
  assert.equal(callback.status, 302);
  const back = new URL(callback.headers.get("location"));
  assert.equal(back.searchParams.get("state"), clientState);
  return { verifier, back, google };
}

async function signIn(deviceName = "Desk") {
  const { verifier, back } = await authorize();
  const response = await call("POST", "/auth/token", {
    body: {
      grant_type: "authorization_code",
      code: back.searchParams.get("code"),
      code_verifier: verifier,
      redirect_uri: LOOPBACK,
      device: { name: deviceName, platform: "linux" },
    },
  });
  assert.equal(response.status, 200);
  return response.json();
}

function refresh(refreshToken) {
  return call("POST", "/auth/token", { body: { grant_type: "refresh_token", refresh_token: refreshToken } });
}

beforeEach(async () => {
  env = await makeEnv();
  googleRequests = [];
  googleClaims = {
    iss: "https://accounts.google.com",
    aud: "google-client",
    sub: "google-user-1",
    exp: now() + 3600,
    email: "fan@example.com",
    email_verified: true,
    name: "Orchard Fan",
    picture: "https://example.com/p.png",
  };
  mock.method(globalThis, "fetch", async (url, init) => {
    googleRequests.push({ url: String(url), body: new URLSearchParams(String(init.body)) });
    return Response.json({ id_token: fakeIdToken(googleClaims) });
  });
});

afterEach(() => mock.restoreAll());

describe("sign-in", () => {
  it("issues a verifiable Orchard session", async () => {
    const tokens = await signIn();
    assert.equal(tokens.token_type, "Bearer");
    assert.equal(tokens.user.email, "fan@example.com");
    assert.match(tokens.refresh_token, new RegExp(`^${tokens.device_id}\\.`));

    const claims = await verifyJwt(tokens.access_token, env.SIGNING_KEY, { issuer: "https://account.test" });
    assert.equal(claims.sub, tokens.user.id);
    assert.equal(claims.did, tokens.device_id);

    const me = await call("GET", "/me", { token: tokens.access_token });
    assert.deepEqual(await me.json(), { ...tokens.user, github: null });

    const jwks = await (await call("GET", "/.well-known/jwks.json")).json();
    assert.equal(jwks.keys.length, 1);
    assert.equal(jwks.keys[0].d, undefined);
  });

  it("uses PKCE with Google", async () => {
    const { google } = await authorize();
    const sent = googleRequests[0].body;
    assert.equal(sent.get("client_secret"), "google-secret");
    assert.equal(await pkceChallenge(sent.get("code_verifier")), google.searchParams.get("code_challenge"));
  });

  it("maps the same Google account to the same user", async () => {
    const first = await signIn("Desk");
    const second = await signIn("Laptop");
    assert.equal(first.user.id, second.user.id);
    assert.notEqual(first.device_id, second.device_id);
  });

  it("only redirects to Orchard callbacks", async () => {
    const challenge = await pkceChallenge(randomToken());
    for (const redirectUri of [
      "https://evil.example/callback",
      "http://127.0.0.1/callback",
      "http://127.0.0.1:5000/other",
      "http://127.0.0.1:5000/callback?x=1",
      "http://127.0.0.1.evil.example:5000/callback",
      "dev.sfg.orchard.mobile://evil/callback",
      "dev.sfg.orchard.mobile://account/other",
      "dev.sfg.orchard.mobile://account/callback?x=1",
      "dev.sfg.orchard.mobile.evil://account/callback",
    ]) {
      const query = new URLSearchParams({ redirect_uri: redirectUri, state: randomToken(16), code_challenge: challenge, code_challenge_method: "S256" });
      const response = await call("GET", `/auth/google/start?${query}`);
      assert.equal(response.status, 400, redirectUri);
    }
  });

  it("returns Android sign-in to the app callback", async () => {
    const { verifier, back } = await authorize({ redirectUri: ANDROID });
    assert.equal(back.origin, "null");
    assert.equal(back.toString().split("?")[0], ANDROID);
    const tokens = await call("POST", "/auth/token", { body: {
      grant_type: "authorization_code",
      code: back.searchParams.get("code"),
      code_verifier: verifier,
      redirect_uri: ANDROID,
      device: { name: "Phone", platform: "android" },
    } });
    assert.equal(tokens.status, 200);
    assert.equal((await tokens.json()).user.email, "fan@example.com");
  });

  it("requires S256 PKCE", async () => {
    const query = new URLSearchParams({ redirect_uri: LOOPBACK, state: randomToken(16), code_challenge: "plain", code_challenge_method: "plain" });
    assert.equal((await call("GET", `/auth/google/start?${query}`)).status, 400);
  });

  it("rejects a wrong verifier, a wrong redirect, and code reuse", async () => {
    const { verifier, back } = await authorize();
    const code = back.searchParams.get("code");
    const exchange = (overrides) => call("POST", "/auth/token", {
      body: { grant_type: "authorization_code", code, code_verifier: verifier, redirect_uri: LOOPBACK, ...overrides },
    });
    // A failed attempt burns the code, so each case needs its own.
    assert.equal((await exchange({ code_verifier: randomToken() })).status, 400);
    assert.equal((await exchange({})).status, 400);

    const second = await authorize();
    const good = { code: second.back.searchParams.get("code"), code_verifier: second.verifier };
    assert.equal((await exchange({ ...good, redirect_uri: "http://127.0.0.1:1/callback" })).status, 400);

    const third = await authorize();
    const ok = { code: third.back.searchParams.get("code"), code_verifier: third.verifier };
    assert.equal((await exchange(ok)).status, 200);
    assert.equal((await exchange(ok)).status, 400);
  });

  it("passes Google denials back to the app", async () => {
    const query = new URLSearchParams({ redirect_uri: LOOPBACK, state: randomToken(16), code_challenge: await pkceChallenge(randomToken()), code_challenge_method: "S256" });
    const google = new URL((await call("GET", `/auth/google/start?${query}`)).headers.get("location"));
    const response = await call("GET", `/auth/google/callback?error=access_denied&state=${google.searchParams.get("state")}`);
    assert.equal(new URL(response.headers.get("location")).searchParams.get("error"), "access_denied");
  });

  it("rejects ID tokens for another client", async () => {
    googleClaims.aud = "someone-else";
    const { back } = await authorize();
    assert.equal(back.searchParams.get("error"), "server_error");
    assert.equal(back.searchParams.get("code"), null);
  });

  it("refuses unknown callback state", async () => {
    const response = await call("GET", "/auth/google/callback?code=x&state=nope");
    assert.equal(response.status, 400);
    assert.match(response.headers.get("content-type"), /text\/html/);
  });
});

describe("refresh tokens", () => {
  it("rotate on every use", async () => {
    const first = await signIn();
    const second = await (await refresh(first.refresh_token)).json();
    assert.notEqual(second.refresh_token, first.refresh_token);
    assert.equal(second.device_id, first.device_id);
    assert.equal((await refresh(second.refresh_token)).status, 200);
  });

  it("allow a just-rotated token inside the grace window", async () => {
    const first = await signIn();
    assert.equal((await refresh(first.refresh_token)).status, 200);
    assert.equal((await refresh(first.refresh_token)).status, 200);
  });

  it("revoke the device when a stale token is replayed", async () => {
    const first = await signIn();
    const second = await (await refresh(first.refresh_token)).json();
    env.DB.raw.prepare("UPDATE devices SET rotated_at = ?").run(now() - REFRESH_REUSE_GRACE_SECONDS - 1);

    assert.equal((await refresh(first.refresh_token)).status, 400);
    assert.equal((await refresh(second.refresh_token)).status, 400);
    assert.equal((await call("GET", "/me", { token: second.access_token })).status, 401);
  });

  it("expire after long inactivity", async () => {
    const first = await signIn();
    env.DB.raw.prepare("UPDATE devices SET last_seen_at = ?").run(now() - REFRESH_IDLE_SECONDS);
    assert.equal((await refresh(first.refresh_token)).status, 400);
  });

  it("reject garbage", async () => {
    for (const token of ["", "nodot", ".x", "00000000-0000-0000-0000-000000000000.x"]) {
      assert.equal((await refresh(token)).status, 400);
    }
  });
});

describe("devices", () => {
  it("lists and revokes other devices", async () => {
    const desk = await signIn("Desk");
    const laptop = await signIn("Laptop");

    const { devices } = await (await call("GET", "/devices", { token: desk.access_token })).json();
    assert.deepEqual(devices.map((d) => [d.name, d.current]).sort(), [["Desk", true], ["Laptop", false]]);

    assert.equal((await call("DELETE", `/devices/${laptop.device_id}`, { token: desk.access_token })).status, 204);
    assert.equal((await refresh(laptop.refresh_token)).status, 400);
    assert.equal((await call("GET", "/me", { token: laptop.access_token })).status, 401);
  });

  it("cannot remove another account's device", async () => {
    const mine = await signIn();
    googleClaims.sub = "google-user-2";
    const theirs = await signIn();
    assert.equal((await call("DELETE", `/devices/${theirs.device_id}`, { token: mine.access_token })).status, 404);
  });

  it("logout revokes the current device", async () => {
    const tokens = await signIn();
    assert.equal((await call("POST", "/auth/logout", { body: { refresh_token: tokens.refresh_token } })).status, 204);
    assert.equal((await refresh(tokens.refresh_token)).status, 400);
  });

  it("rejects forged access tokens", async () => {
    const tokens = await signIn();
    const [header, , signature] = tokens.access_token.split(".");
    const forged = `${header}.${encodeJsonSegment({ sub: "x", did: tokens.device_id, aud: "orchard", exp: now() + 60 })}.${signature}`;
    assert.equal((await call("GET", "/me", { token: forged })).status, 401);
  });
});

describe("maintenance", () => {
  it("purges expired sign-in state", async () => {
    await authorize();
    await purgeExpired(env, now() + 3600);
    assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS n FROM auth_requests").get().n, 0);
    assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS n FROM auth_codes").get().n, 0);
  });
});
