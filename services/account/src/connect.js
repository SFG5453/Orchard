/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Orchard Connect hub: one Durable Object per account. It knows which devices
// are online, hands both ends of a session the same short-lived key, and relays
// WebRTC signaling. Playback traffic never passes through here.

import { base64url } from "./crypto.js";
import { HttpError, issuer, nowSeconds } from "./http.js";
import { verifyJwt } from "./jwt.js";

export const HUB_SUBPROTOCOL = "orchard-connect.2";
export const CONNECT_PROTOCOL_MAJOR = 2;
export const GRANT_SECONDS = 60;
// A session record outlives its grant so reconnect signaling still routes.
export const SESSION_SECONDS = 12 * 60 * 60;
export const MAX_SIGNAL_BYTES = 64 * 1024;
const MAX_MESSAGE_BYTES = 96 * 1024;
const REQUESTS_PER_MINUTE = 12;
const DEFAULT_STUN = { urls: ["stun:stun.cloudflare.com:3478"] };
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

function offeredProtocols(request) {
  return (request.headers.get("sec-websocket-protocol") || "").split(",").map((value) => value.trim()).filter(Boolean);
}

// Worker side: authenticate the upgrade, then hand it to the account's hub.
// The bearer token rides in the subprotocol list because WebSocket clients
// cannot set headers, and a token in the URL would end up in logs.
export async function openHub(request, env) {
  if ((request.headers.get("upgrade") || "").toLowerCase() !== "websocket") {
    throw new HttpError(426, "upgrade_required", "Connect to the hub with a WebSocket");
  }
  const offered = offeredProtocols(request);
  if (!offered.includes(HUB_SUBPROTOCOL)) {
    throw new HttpError(400, "incompatible_client", "This Orchard version cannot use Orchard Connect");
  }
  const bearer = offered.find((value) => value.startsWith("bearer."));
  const claims = bearer ? await verifyJwt(bearer.slice(7), env.SIGNING_KEY, { issuer: issuer(env) }) : null;
  if (!claims) throw new HttpError(401, "invalid_token", "Sign in to Orchard again");
  const device = await env.DB.prepare("SELECT id FROM devices WHERE id = ? AND user_id = ?").bind(claims.did, claims.sub).first();
  if (!device) throw new HttpError(401, "invalid_token", "This device was signed out");

  const headers = new Headers(request.headers);
  headers.set("x-orchard-user", claims.sub);
  headers.set("x-orchard-device", claims.did);
  headers.set("x-orchard-expires", String(claims.exp));
  const stub = env.CONNECT_HUB.get(env.CONNECT_HUB.idFromName(claims.sub));
  return stub.fetch(new Request(request.url, { method: "GET", headers }));
}

export function protocolError(message) {
  const major = message?.connect_protocol_major;
  if (!Number.isInteger(major)) return "incompatible_client";
  return major === CONNECT_PROTOCOL_MAJOR ? "" : "incompatible_protocol";
}

function cleanText(value, limit = 64) {
  return String(value ?? "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, limit);
}

// Only fields the hub vouches for or can bound; the id always comes from the token.
export function cleanDevice(raw, deviceId) {
  const device = raw && typeof raw === "object" ? raw : {};
  const list = (value) => (Array.isArray(value) ? value.filter((v) => typeof v === "string").map((v) => cleanText(v, 32)).slice(0, 16) : []);
  const sessions = {};
  if (device.provider_sessions && typeof device.provider_sessions === "object") {
    for (const [name, signedIn] of Object.entries(device.provider_sessions).slice(0, 16)) {
      if (typeof signedIn === "boolean") sessions[cleanText(name, 32)] = signedIn;
    }
  }
  const playing = device.currently_playing && typeof device.currently_playing === "object" ? device.currently_playing : null;
  return {
    id: deviceId,
    name: cleanText(device.name) || "Orchard device",
    platform: cleanText(device.platform, 16),
    kind: cleanText(device.kind, 16),
    connect_protocol_major: CONNECT_PROTOCOL_MAJOR,
    connect_protocol_minor: Number.isInteger(device.connect_protocol_minor) ? device.connect_protocol_minor : 0,
    can_render_audio: device.can_render_audio === true,
    can_mix_audio: device.can_mix_audio === true,
    can_fetch_artwork: device.can_fetch_artwork === true,
    providers: list(device.providers),
    provider_sessions: sessions,
    audio_transports: list(device.audio_transports),
    currently_playing: playing && {
      id: cleanText(playing.id, 256),
      title: cleanText(playing.title, 256),
      artist: cleanText(playing.artist, 256),
      playing: playing.playing === true,
    },
  };
}

export function cleanLan(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((item) => item && IPV4.test(String(item.host)) && Number.isInteger(item.port) && item.port > 0 && item.port < 65536)
    .slice(0, 4)
    .map((item) => ({ host: String(item.host), port: item.port }));
}

// Short-lived TURN credentials from Cloudflare Realtime, when configured. Without
// them, devices behind strict NATs still fall back to LAN-only.
export async function iceServers(env, fetcher = fetch) {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return [DEFAULT_STUN];
  try {
    const response = await fetcher(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate`, {
      method: "POST",
      headers: { authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ ttl: 3600 }),
    });
    if (!response.ok) throw new Error(`TURN credentials: HTTP ${response.status}`);
    const body = await response.json();
    const servers = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
    return [DEFAULT_STUN, ...servers.filter((server) => server && server.urls)];
  } catch (error) {
    console.error(error.message);
    return [DEFAULT_STUN];
  }
}

export class ConnectHub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.requests = new Map();
    this.fetchTurn = (...args) => fetch(...args);
  }

  async fetch(request) {
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.accept(server, {
      userId: request.headers.get("x-orchard-user"),
      deviceId: request.headers.get("x-orchard-device"),
      expiresAt: Number(request.headers.get("x-orchard-expires")) || 0,
    });
    return new Response(null, { status: 101, webSocket: client, headers: { "sec-websocket-protocol": HUB_SUBPROTOCOL } });
  }

  accept(socket, { userId, deviceId, expiresAt }) {
    // One live socket per device; a reconnect replaces the old one.
    for (const old of this.ctx.getWebSockets(deviceId)) old.close(4001, "replaced");
    this.ctx.acceptWebSocket(socket, [deviceId]);
    socket.serializeAttachment({ userId, deviceId, expiresAt, announced: false });
  }

  send(socket, message) {
    try {
      socket.send(JSON.stringify(message));
    } catch {
      // A socket closing underneath us is cleaned up by webSocketClose.
    }
  }

  socketFor(deviceId) {
    return this.ctx.getWebSockets(deviceId).find((socket) => socket.deserializeAttachment()?.announced) || null;
  }

  async broadcastPresence(closing = null) {
    // A closing socket can still be listed while its close handler runs.
    const sockets = this.ctx.getWebSockets().filter((socket) => socket !== closing && socket.deserializeAttachment()?.announced);
    const devices = [];
    for (const socket of sockets) {
      const { deviceId } = socket.deserializeAttachment();
      const stored = await this.ctx.storage.get(`device:${deviceId}`);
      if (stored) devices.push(stored);
    }
    for (const socket of sockets) this.send(socket, { type: "presence", devices });
  }

  async webSocketMessage(socket, raw) {
    const state = socket.deserializeAttachment();
    if (!state) return;
    if (state.expiresAt && state.expiresAt <= nowSeconds()) {
      socket.close(4401, "token_expired");
      return;
    }
    if (typeof raw !== "string" || raw.length > MAX_MESSAGE_BYTES) return;
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return;
    }
    if (!message || typeof message !== "object") return;
    switch (message.type) {
      case "hello":
      case "update":
        return this.announce(socket, state, message);
      case "refresh":
        return this.refresh(socket, state, message);
      case "session.request":
        return state.announced && this.requestSession(socket, state, message);
      case "signal":
        return state.announced && this.relaySignal(state, message);
      case "session.decline":
        return this.declineSession(state, message);
      case "session.end":
        return this.endSession(state, message);
      default:
    }
  }

  async announce(socket, state, message) {
    const error = protocolError(message);
    if (error) {
      // Old clients never get presence, so they cannot connect by accident.
      this.send(socket, { type: "error", code: error, message: "Update Orchard to use Orchard Connect" });
      socket.close(4002, error);
      return;
    }
    await this.ctx.storage.put(`device:${state.deviceId}`, {
      device: cleanDevice(message.device, state.deviceId),
      lan: cleanLan(message.lan),
    });
    if (!state.announced) {
      socket.serializeAttachment({ ...state, announced: true });
      this.send(socket, { type: "welcome", device_id: state.deviceId });
    }
    await this.broadcastPresence();
  }

  async refresh(socket, state, message) {
    const claims = await verifyJwt(message.token, this.env.SIGNING_KEY, { issuer: issuer(this.env) });
    if (!claims || claims.sub !== state.userId || claims.did !== state.deviceId) {
      socket.close(4401, "invalid_token");
      return;
    }
    socket.serializeAttachment({ ...state, expiresAt: claims.exp });
  }

  allowRequest(deviceId) {
    const minute = Math.floor(Date.now() / 60000);
    const entry = this.requests.get(deviceId);
    if (!entry || entry.minute !== minute) {
      this.requests.set(deviceId, { minute, count: 1 });
      return true;
    }
    entry.count += 1;
    return entry.count <= REQUESTS_PER_MINUTE;
  }

  async requestSession(socket, state, message) {
    const requestId = cleanText(message.request_id, 64);
    const to = cleanText(message.to, 64);
    const fail = (code) => this.send(socket, { type: "error", code, request_id: requestId });
    if (!this.allowRequest(state.deviceId)) return fail("slow_down");
    const target = to && to !== state.deviceId ? this.socketFor(to) : null;
    if (!target) return fail("peer_offline");
    const [mine, theirs] = await Promise.all([
      this.ctx.storage.get(`device:${state.deviceId}`),
      this.ctx.storage.get(`device:${to}`),
    ]);
    if (!mine || !theirs) return fail("peer_offline");

    const sessionId = crypto.randomUUID();
    const key = base64url(crypto.getRandomValues(new Uint8Array(32)));
    const ice = await iceServers(this.env, this.fetchTurn);
    await this.ctx.storage.put(`session:${sessionId}`, {
      controller: state.deviceId,
      target: to,
      expiresAt: nowSeconds() + SESSION_SECONDS,
    });
    const grant = { type: "session.grant", session_id: sessionId, key, ice_servers: ice, expires_in: GRANT_SECONDS };
    this.send(socket, { ...grant, role: "controller", request_id: requestId, peer: theirs.device, peer_lan: theirs.lan });
    this.send(target, { ...grant, role: "target", peer: mine.device, peer_lan: mine.lan });
  }

  async session(sessionId) {
    const session = await this.ctx.storage.get(`session:${cleanText(sessionId, 64)}`);
    if (!session) return null;
    if (session.expiresAt <= nowSeconds()) {
      await this.ctx.storage.delete(`session:${sessionId}`);
      return null;
    }
    return session;
  }

  // Signaling only flows between the two ends of a granted session.
  async relaySignal(state, message) {
    const session = await this.session(message.session_id);
    const to = cleanText(message.to, 64);
    if (!session) return;
    const pair = [session.controller, session.target];
    if (!pair.includes(state.deviceId) || !pair.includes(to) || to === state.deviceId) return;
    if (!message.data || typeof message.data !== "object" || JSON.stringify(message.data).length > MAX_SIGNAL_BYTES) return;
    const peer = this.socketFor(to);
    if (peer) this.send(peer, { type: "signal", from: state.deviceId, session_id: message.session_id, data: message.data });
  }

  async declineSession(state, message) {
    const session = await this.session(message.session_id);
    if (!session || session.target !== state.deviceId) return;
    await this.ctx.storage.delete(`session:${message.session_id}`);
    const controller = this.socketFor(session.controller);
    if (controller) {
      this.send(controller, { type: "session.declined", session_id: message.session_id, code: cleanText(message.code, 32) || "declined" });
    }
  }

  async endSession(state, message) {
    const session = await this.session(message.session_id);
    if (session && (session.controller === state.deviceId || session.target === state.deviceId)) {
      await this.ctx.storage.delete(`session:${message.session_id}`);
    }
  }

  async webSocketClose(socket) {
    const state = socket.deserializeAttachment();
    // A replaced socket shares the device id with its successor; keep that record.
    if (state && !this.ctx.getWebSockets(state.deviceId).some((other) => other !== socket)) {
      await this.ctx.storage.delete(`device:${state.deviceId}`);
    }
    await this.broadcastPresence(socket);
  }

  async webSocketError(socket) {
    await this.webSocketClose(socket);
  }
}
