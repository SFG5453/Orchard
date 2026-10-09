import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";

import { ConnectHub, cleanLan, iceServers, protocolError } from "../src/connect.js";
import worker from "../src/index.js";
import { signJwt } from "../src/jwt.js";
import { createD1 } from "./d1.js";

const now = () => Math.floor(Date.now() / 1000);

// Just enough of the Durable Object WebSocket hibernation API.
class FakeSocket {
  constructor() {
    this.sent = [];
    this.closed = null;
    this.attachment = null;
  }
  send(text) {
    if (this.closed) throw new Error("closed");
    this.sent.push(JSON.parse(text));
  }
  close(code, reason) {
    this.closed = { code, reason };
  }
  serializeAttachment(value) {
    this.attachment = structuredClone(value);
  }
  deserializeAttachment() {
    return this.attachment;
  }
  last(type) {
    return [...this.sent].reverse().find((message) => message.type === type);
  }
}

class FakeCtx {
  constructor() {
    this.sockets = [];
    const data = new Map();
    this.storage = {
      get: async (key) => structuredClone(data.get(key)),
      put: async (key, value) => void data.set(key, structuredClone(value)),
      delete: async (key) => data.delete(key),
    };
  }
  acceptWebSocket(socket, tags) {
    socket.tags = tags;
    this.sockets.push(socket);
  }
  getWebSockets(tag) {
    return this.sockets.filter((socket) => !socket.closed && (!tag || socket.tags.includes(tag)));
  }
}

const v2 = { connect_protocol_major: 2, connect_protocol_minor: 0 };

function device(id, platform, extra = {}) {
  return { id: "spoofed", name: id, platform, providers: ["youtube", "qobuz"], provider_sessions: { qobuz: false }, ...extra };
}

describe("Connect hub", () => {
  let hub;
  let env;

  async function join(deviceId, platform, lan = [{ host: "192.168.1.5", port: 32147 }], extra = {}) {
    const socket = new FakeSocket();
    hub.accept(socket, { userId: "user-1", deviceId, expiresAt: now() + 900 });
    await hub.webSocketMessage(socket, JSON.stringify({ type: "hello", ...v2, device: device(deviceId, platform, extra), lan }));
    return socket;
  }

  beforeEach(() => {
    env = {};
    hub = new ConnectHub(new FakeCtx(), env);
  });

  it("rejects clients without the v2 major before showing them anything", async () => {
    assert.equal(protocolError({}), "incompatible_client");
    assert.equal(protocolError({ connect_protocol_major: 1 }), "incompatible_protocol");
    assert.equal(protocolError({ connect_protocol_major: "2" }), "incompatible_client");
    assert.equal(protocolError(v2), "");

    const pc = await join("pc", "linux");
    const old = new FakeSocket();
    hub.accept(old, { userId: "user-1", deviceId: "old-phone", expiresAt: now() + 900 });
    await hub.webSocketMessage(old, JSON.stringify({ type: "hello", protocolVersion: 4, device: device("old-phone", "android") }));
    assert.equal(old.last("error").code, "incompatible_client");
    assert.equal(old.closed.code, 4002);
    assert.equal(old.last("presence"), undefined);
    // The old client never shows up for anyone else either.
    assert.deepEqual(pc.last("presence").devices.map((d) => d.device.id), ["pc"]);
  });

  it("publishes presence with the token's device id, not the client's", async () => {
    const pc = await join("pc", "linux");
    const phone = await join("phone", "android");
    assert.equal(phone.last("welcome").device_id, "phone");
    const seen = pc.last("presence").devices;
    assert.deepEqual(seen.map((d) => d.device.id).sort(), ["pc", "phone"]);
    assert.equal(seen.find((d) => d.device.id === "phone").device.connect_protocol_major, 2);
    // Closing removes the device for everyone.
    phone.close(1000, "bye");
    await hub.webSocketClose(phone);
    assert.deepEqual(pc.last("presence").devices.map((d) => d.device.id), ["pc"]);
  });

  it("grants both ends the same key and routes signals only between them", async () => {
    const pc = await join("pc", "linux");
    const phone = await join("phone", "android");
    const tablet = await join("tablet", "android");
    await hub.webSocketMessage(phone, JSON.stringify({ type: "session.request", request_id: "r1", to: "pc" }));
    const mine = phone.last("session.grant");
    const theirs = pc.last("session.grant");
    assert.equal(mine.role, "controller");
    assert.equal(mine.request_id, "r1");
    assert.equal(theirs.role, "target");
    assert.equal(mine.key, theirs.key);
    assert.equal(mine.session_id, theirs.session_id);
    assert.equal(mine.peer.id, "pc");
    assert.deepEqual(mine.peer_lan, [{ host: "192.168.1.5", port: 32147 }]);
    assert.equal(theirs.peer.id, "phone");
    assert.equal(tablet.last("session.grant"), undefined);

    const offer = { kind: "description", type: "offer", sdp: "v=0" };
    await hub.webSocketMessage(phone, JSON.stringify({ type: "signal", to: "pc", session_id: mine.session_id, data: offer }));
    assert.deepEqual(pc.last("signal"), { type: "signal", from: "phone", session_id: mine.session_id, data: offer });
    // A third device cannot inject into someone else's session.
    await hub.webSocketMessage(tablet, JSON.stringify({ type: "signal", to: "pc", session_id: mine.session_id, data: { evil: 1 } }));
    assert.deepEqual(pc.last("signal").data, offer);
    // Nor can signaling create a session on its own.
    await hub.webSocketMessage(tablet, JSON.stringify({ type: "signal", to: "pc", session_id: "made-up", data: offer }));
    assert.equal(pc.sent.filter((m) => m.type === "signal").length, 1);
  });

  it("reports offline peers and declines", async () => {
    const phone = await join("phone", "android");
    await hub.webSocketMessage(phone, JSON.stringify({ type: "session.request", request_id: "r2", to: "pc" }));
    assert.deepEqual(phone.last("error"), { type: "error", code: "peer_offline", request_id: "r2" });

    const pc = await join("pc", "linux");
    await hub.webSocketMessage(phone, JSON.stringify({ type: "session.request", request_id: "r3", to: "pc" }));
    const grant = pc.last("session.grant");
    await hub.webSocketMessage(pc, JSON.stringify({ type: "session.decline", session_id: grant.session_id, code: "busy" }));
    assert.deepEqual(phone.last("session.declined"), { type: "session.declined", session_id: grant.session_id, code: "busy" });
  });

  it("closes sockets whose token expired and limits session requests", async () => {
    const phone = await join("phone", "android");
    await join("pc", "linux");
    for (let i = 0; i < 12; i++) {
      await hub.webSocketMessage(phone, JSON.stringify({ type: "session.request", request_id: `r${i}`, to: "pc" }));
    }
    await hub.webSocketMessage(phone, JSON.stringify({ type: "session.request", request_id: "spam", to: "pc" }));
    assert.equal(phone.last("error").code, "slow_down");

    phone.serializeAttachment({ ...phone.deserializeAttachment(), expiresAt: now() - 1 });
    await hub.webSocketMessage(phone, JSON.stringify({ type: "update", ...v2, device: device("phone", "android") }));
    assert.equal(phone.closed.code, 4401);
  });

  it("keeps LAN endpoints to literal IPv4 addresses", () => {
    assert.deepEqual(
      cleanLan([{ host: "printer.local", port: 80 }, { host: "10.0.0.2", port: 32147 }, { host: "1.2.3.4", port: 70000 }]),
      [{ host: "10.0.0.2", port: 32147 }],
    );
  });

  it("adds Cloudflare TURN credentials when configured", async () => {
    assert.deepEqual(await iceServers({}), [{ urls: ["stun:stun.cloudflare.com:3478"] }]);
    const calls = [];
    const servers = await iceServers({ TURN_KEY_ID: "k", TURN_KEY_API_TOKEN: "t" }, async (url, init) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({
        iceServers: { urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: "u", credential: "c" },
      }));
    });
    assert.equal(calls[0].url, "https://rtc.live.cloudflare.com/v1/turn/keys/k/credentials/generate");
    assert.equal(calls[0].init.headers.authorization, "Bearer t");
    assert.equal(servers.length, 2);
    assert.equal(servers[1].username, "u");
    // A TURN outage degrades to STUN; the grant still goes out.
    const fallback = await iceServers({ TURN_KEY_ID: "k", TURN_KEY_API_TOKEN: "t" }, async () => new Response("", { status: 500 }));
    assert.deepEqual(fallback, [{ urls: ["stun:stun.cloudflare.com:3478"] }]);
  });
});

describe("Connect hub route", () => {
  let env;
  let forwarded;

  beforeEach(async () => {
    const { privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
    const jwk = { ...(await crypto.subtle.exportKey("jwk", privateKey)), kid: "test-key" };
    forwarded = null;
    env = {
      DB: createD1(),
      PUBLIC_URL: "https://account.test",
      SIGNING_KEY: JSON.stringify(jwk),
      CONNECT_HUB: {
        idFromName: (name) => `hub:${name}`,
        get: (id) => ({
          fetch: async (request) => {
            forwarded = { id, request };
            return new Response("hub");
          },
        }),
      },
    };
    const t = now();
    env.DB.raw.exec(`INSERT INTO users (id, email, name, picture, created_at, updated_at) VALUES ('user-1', '', '', '', ${t}, ${t})`);
    env.DB.raw.exec(`INSERT INTO devices (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at)
      VALUES ('device-1', 'user-1', 'PC', 'linux', 'hash', ${t}, ${t}, ${t})`);
  });

  async function token(claims = {}) {
    const t = now();
    return signJwt({ iss: "https://account.test", aud: "orchard", sub: "user-1", did: "device-1", iat: t, exp: t + 900, ...claims },
      env.SIGNING_KEY);
  }

  function open(protocols, upgrade = "websocket") {
    const headers = { upgrade };
    if (protocols) headers["sec-websocket-protocol"] = protocols;
    return worker.fetch(new Request("https://account.test/connect/hub", { headers }), env);
  }

  it("needs a WebSocket upgrade with the v2 subprotocol", async () => {
    assert.equal((await open(null, "")).status, 426);
    const old = await open(`bearer.${await token()}`);
    assert.equal(old.status, 400);
    assert.equal((await old.json()).error, "incompatible_client");
  });

  it("authenticates the bearer subprotocol and routes to the account's hub", async () => {
    assert.equal((await open("orchard-connect.2, bearer.not-a-token")).status, 401);
    assert.equal((await open(`orchard-connect.2, bearer.${await token({ did: "gone" })}`)).status, 401);
    const response = await open(`orchard-connect.2, bearer.${await token()}`);
    assert.equal(await response.text(), "hub");
    assert.equal(forwarded.id, "hub:user-1");
    assert.equal(forwarded.request.headers.get("x-orchard-device"), "device-1");
    assert.equal(forwarded.request.headers.get("x-orchard-user"), "user-1");
  });
});
