import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it, mock } from "node:test";

import worker from "../src/index.js";
import { signJwt } from "../src/jwt.js";
import { REPORT_LIMITS } from "../src/support.js";
import { sweepSupport } from "../src/support_sync.js";
import { createD1 } from "./d1.js";

const now = () => Math.floor(Date.now() / 1000);
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);

function fakeR2() {
  const objects = new Map();
  return {
    objects,
    async put(key, bytes, options) { objects.set(key, { bytes: new Uint8Array(bytes), options }); },
    async get(key) { const object = objects.get(key); return object ? { body: object.bytes } : null; },
    async delete(key) { objects.delete(key); },
  };
}

// Stand-in for github.com and api.github.com. Tests edit `github` to steer it.
let github;
function fakeGithub() {
  return {
    issues: [],
    timeline: new Map(),
    etags: new Map(),
    requests: [],
    profile: { id: 4242, login: "octo", avatar_url: "https://avatars.test/octo" },
    issueStatus: 201,
  };
}

async function respond(url, init) {
  const { pathname } = new URL(url);
  github.requests.push({ url, init });
  if (url === "https://github.com/login/oauth/access_token") return Response.json({ access_token: "gho_test" });
  if (pathname === "/user") return Response.json(github.profile);
  if (pathname.startsWith("/applications/")) return new Response(null, { status: 204 });
  if (init?.method === "POST" && /\/issues$/.test(pathname)) {
    if (github.issueStatus !== 201) return new Response("nope", { status: github.issueStatus });
    const number = github.issues.length + 1;
    github.issues.push({ number, ...JSON.parse(init.body) });
    return Response.json({ number, html_url: `https://github.com/SFG5453/orchard-v4/issues/${number}` }, { status: 201 });
  }
  const timeline = /\/issues\/(\d+)\/timeline$/.exec(pathname);
  if (timeline) {
    const number = Number(timeline[1]);
    const etag = `"t${number}-${(github.timeline.get(number) || []).length}"`;
    if (init?.headers?.["if-none-match"] === etag) return new Response(null, { status: 304 });
    return Response.json(github.timeline.get(number) || [], { headers: { etag } });
  }
  if (/\/commits\/[0-9a-f]+$/.test(pathname)) {
    return Response.json({ commit: { message: "Fix the crash on start\n\nDetails" }, html_url: "https://github.com/c/abc" });
  }
  return new Response("not found", { status: 404 });
}

let env;
const tokens = {};

async function session(userId, deviceId = `${userId}-device`) {
  env.DB.raw.prepare("INSERT OR IGNORE INTO users (id, created_at, updated_at) VALUES (?, 0, 0)").run(userId);
  env.DB.raw.prepare(
    "INSERT OR IGNORE INTO devices (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at) VALUES (?, ?, 'Desk', 'linux', ?, 0, 0, ?)",
  ).run(deviceId, userId, `hash-${deviceId}`, now());
  return signJwt({ iss: "https://account.test", aud: "orchard", sub: userId, did: deviceId, iat: now(), exp: now() + 900 }, env.SIGNING_KEY);
}

function call(method, path, { token, body, headers = {} } = {}) {
  const init = { method, headers: { ...headers }, body };
  if (token) init.headers.authorization = `Bearer ${token}`;
  return worker.fetch(new Request(`https://account.test${path}`, init), env);
}

async function link(token) {
  const start = await call("POST", "/github/link", { token });
  assert.equal(start.status, 200);
  const url = new URL((await start.json()).url);
  assert.equal(url.origin, "https://github.com");
  assert.equal(url.searchParams.get("redirect_uri"), "https://account.test/auth/github/callback");
  return call("GET", `/auth/github/callback?code=abc&state=${url.searchParams.get("state")}`);
}

function reportForm(fields = {}) {
  const form = new FormData();
  form.set("kind", fields.kind ?? "bug");
  form.set("title", fields.title ?? "Crash on start");
  form.set("body", fields.body ?? "It crashes when I press play.");
  if (fields.diagnostics !== undefined) form.set("diagnostics", fields.diagnostics);
  if (fields.screenshot) form.set("screenshot", fields.screenshot);
  return form;
}

async function file(token, fields) {
  return call("POST", "/support/reports", { token, body: reportForm(fields) });
}

beforeEach(async () => {
  const { privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  env = {
    DB: createD1(),
    SUPPORT_FILES: fakeR2(),
    PUBLIC_URL: "https://account.test",
    SIGNING_KEY: JSON.stringify({ ...(await crypto.subtle.exportKey("jwk", privateKey)), kid: "k" }),
    GITHUB_CLIENT_ID: "Iv1.client",
    GITHUB_CLIENT_SECRET: "secret",
    GITHUB_TOKEN: "github_pat_test",
    GITHUB_REPOSITORY: "SFG5453/orchard-v4",
    GITHUB_WEBHOOK_SECRET: "hook-secret",
  };
  github = fakeGithub();
  mock.method(globalThis, "fetch", respond);
  tokens.alice = await session("alice");
  tokens.bob = await session("bob");
});

afterEach(() => mock.restoreAll());

describe("GitHub linking", () => {
  it("links, shows on /me, and drops the GitHub token", async () => {
    const page = await link(tokens.alice);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /@octo/);
    const me = await (await call("GET", "/me", { token: tokens.alice })).json();
    assert.deepEqual(me.github, { id: "4242", login: "octo", avatar: "https://avatars.test/octo" });
    assert.ok(github.requests.some(({ url, init }) => url.includes("/applications/Iv1.client/token") && init.method === "DELETE"));
  });

  it("refuses a GitHub account already linked elsewhere", async () => {
    await link(tokens.alice);
    const page = await link(tokens.bob);
    assert.equal(page.status, 409);
    const me = await (await call("GET", "/me", { token: tokens.bob })).json();
    assert.equal(me.github, null);
  });

  it("rejects replayed and unknown states", async () => {
    const start = await (await call("POST", "/github/link", { token: tokens.alice })).json();
    const state = new URL(start.url).searchParams.get("state");
    assert.equal((await call("GET", `/auth/github/callback?code=abc&state=${state}`)).status, 200);
    assert.equal((await call("GET", `/auth/github/callback?code=abc&state=${state}`)).status, 400);
  });

  it("unlinks", async () => {
    await link(tokens.alice);
    assert.equal((await call("DELETE", "/github", { token: tokens.alice })).status, 204);
    const list = await (await call("GET", "/support/reports", { token: tokens.alice })).json();
    assert.equal(list.github, null);
  });
});

describe("filing reports", () => {
  it("requires a linked GitHub account", async () => {
    const response = await file(tokens.alice);
    assert.equal(response.status, 403);
    assert.equal((await response.json()).error, "github_required");
    assert.equal(github.issues.length, 0);
  });

  it("files an attributed issue with the screenshot and diagnostics", async () => {
    await link(tokens.alice);
    const response = await file(tokens.alice, {
      diagnostics: JSON.stringify({ version: "4.0.0", note: "```break```" }),
      screenshot: new File([PNG], "shot.png", { type: "image/png" }),
    });
    assert.equal(response.status, 201);
    const { report } = await response.json();
    assert.equal(report.number, 1);
    assert.equal(report.state, "open");

    const issue = github.issues[0];
    assert.equal(issue.title, "Crash on start");
    assert.deepEqual(issue.labels, ["bug"]);
    assert.match(issue.body, /by @octo\.$/);
    assert.match(issue.body, /on linux/);
    assert.doesNotMatch(issue.body, /```break/);
    const shot = /!\[Screenshot\]\((https:\/\/account\.test\/support\/screenshots\/[^)]+)\)/.exec(issue.body)[1];
    const served = await worker.fetch(new Request(shot), env);
    assert.equal(served.status, 200);
    assert.equal(served.headers.get("content-type"), "image/png");
  });

  it("refuses screenshots whose bytes lie about their type", async () => {
    await link(tokens.alice);
    const response = await file(tokens.alice, { screenshot: new File([PNG], "x.jpg", { type: "image/jpeg" }) });
    assert.equal(response.status, 415);
    assert.equal(github.issues.length, 0);
  });

  it("cleans up the screenshot when GitHub refuses the issue", async () => {
    await link(tokens.alice);
    github.issueStatus = 500;
    const response = await file(tokens.alice, { screenshot: new File([PNG], "shot.png", { type: "image/png" }) });
    assert.equal(response.status, 502);
    assert.equal(env.SUPPORT_FILES.objects.size, 0);
  });

  it("rate limits per user", async () => {
    await link(tokens.alice);
    const [, hourly] = REPORT_LIMITS[0];
    for (let i = 0; i < hourly; i++) assert.equal((await file(tokens.alice)).status, 201);
    const limited = await file(tokens.alice);
    assert.equal(limited.status, 429);
    assert.ok(Number(limited.headers.get("retry-after")) > 0);
  });

  it("keeps reports private to their owner", async () => {
    await link(tokens.alice);
    const { report } = await (await file(tokens.alice)).json();
    assert.equal((await call("GET", `/support/reports/${report.id}`, { token: tokens.bob })).status, 404);
  });
});

describe("issue activity", () => {
  const actor = (login, id) => ({ login, id, avatar_url: `https://avatars.test/${login}` });

  async function filed() {
    await link(tokens.alice);
    return (await (await file(tokens.alice)).json()).report;
  }

  it("turns the timeline into events and unread counts", async () => {
    const report = await filed();
    github.timeline.set(1, [
      { event: "labeled", id: 1, actor: actor("sfg", 1), label: { name: "bug" }, created_at: "2026-10-05T10:00:00Z" },
      { event: "commented", id: 2, actor: actor("octo", 4242), body: "More info", author_association: "NONE", created_at: "2026-10-05T10:01:00Z" },
      { event: "commented", id: 3, actor: actor("sfg", 1), body: "Can repro", author_association: "OWNER", html_url: "https://github.com/x#c3", created_at: "2026-10-05T10:02:00Z" },
      { event: "referenced", actor: actor("sfg", 1), commit_id: "abcdef1234567", commit_url: "https://api.github.com/repos/SFG5453/orchard-v4/commits/abcdef1234567", created_at: "2026-10-05T10:03:00Z" },
      { event: "closed", id: 5, actor: actor("sfg", 1), commit_id: "abcdef1234567", created_at: "2026-10-05T10:04:00Z" },
    ]);
    // Inline sync only touches reports older than a minute.
    env.DB.raw.prepare("UPDATE support_reports SET synced_at = 0, read_at = read_at - 120").run();

    const list = await (await call("GET", "/support/reports", { token: tokens.alice })).json();
    assert.equal(list.github.login, "octo");
    assert.equal(list.reports[0].state, "closed");
    assert.equal(list.reports[0].state_reason, "completed");
    // Maintainer reply, commit and close notify; the label and your own comment do not.
    assert.equal(list.unread, 3);
    assert.equal(list.reports[0].latest.title, "Fixed by commit abcdef1");

    const detail = await (await call("GET", `/support/reports/${report.id}`, { token: tokens.alice })).json();
    const titles = detail.events.map((event) => event.title);
    assert.deepEqual(titles, ["Labeled bug", "You commented", "sfg replied", "Commit abcdef1 mentions this report", "Fixed by commit abcdef1"]);
    assert.equal(detail.events[2].maintainer, true);
    assert.equal(detail.events[3].body, "Fix the crash on start");

    assert.equal((await call("POST", `/support/reports/${report.id}/read`, { token: tokens.alice })).status, 204);
    const after = await (await call("GET", "/support/reports", { token: tokens.alice })).json();
    assert.equal(after.unread, 0);
  });

  it("sends a conditional request once nothing changed", async () => {
    await filed();
    github.timeline.set(1, [{ event: "reopened", id: 9, actor: actor("sfg", 1), created_at: "2026-10-05T10:00:00Z" }]);
    await sweepSupport(env);
    github.requests.length = 0;
    await sweepSupport(env);
    const timelineCalls = github.requests.filter(({ url }) => url.includes("/timeline"));
    assert.equal(timelineCalls.length, 1);
    assert.match(timelineCalls[0].init.headers["if-none-match"], /^"t1-1"$/);
  });

  it("accepts signed push webhooks and records referencing commits", async () => {
    const report = await filed();
    env.DB.raw.prepare("UPDATE support_reports SET read_at = read_at - 120").run();
    const payload = JSON.stringify({
      repository: { full_name: "SFG5453/orchard-v4" },
      commits: [{ id: "1234567890abc", message: "Stop the crash (fixes #1)", url: "https://github.com/c/1234567", timestamp: "2026-10-05T11:00:00Z", author: { username: "sfg" } }],
    });
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode("hook-secret"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const signature = Buffer.from(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload))).toString("hex");

    const bad = await call("POST", "/github/webhook", { body: payload, headers: { "x-github-event": "push", "x-hub-signature-256": `sha256=${"0".repeat(64)}` } });
    assert.equal(bad.status, 401);
    const good = await call("POST", "/github/webhook", { body: payload, headers: { "x-github-event": "push", "x-hub-signature-256": `sha256=${signature}` } });
    assert.equal(good.status, 202);

    const detail = await (await call("GET", `/support/reports/${report.id}`, { token: tokens.alice })).json();
    assert.equal(detail.events[0].title, "Commit 1234567 mentions this report");
    assert.equal(detail.events[0].body, "Stop the crash (fixes #1)");
    assert.equal(detail.report.unread, 1);
  });
});
