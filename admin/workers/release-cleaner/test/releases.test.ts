import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import { BadBucket, channelReferences, inventory, isOldEnough, liveHashes, manifestHashes, signedPayload } from "../src/releases.ts";

const old = new Date("2026-08-01T00:00:00Z");
const hash = (letter: string) => letter.repeat(64);
const envelope = (payload: object) => JSON.stringify({
  format: "orchard-signed-v1", algorithm: "ecdsa-p256-sha256", payload: btoa(JSON.stringify(payload)),
});

class Bucket {
  data = new Map<string, { text: string; version: string }>();
  put(key: string, text: string) { this.data.set(key, { text, version: `v${this.data.size}` }); }
  async get(key: string) {
    const item = this.data.get(key);
    return item && { size: item.text.length, text: async () => item.text };
  }
  async list({ prefix }: { prefix: string }) {
    return { objects: [...this.data].filter(([key]) => key.startsWith(prefix)).map(([key, value]) => ({
      key, version: value.version, size: value.text.length, uploaded: old,
    })), truncated: false };
  }
}

function fixture() {
  const bucket = new Bucket();
  const current = { version: "2.0.0", platform: "win-x86_64", files: [
    { path: "app.exe", sha256: hash("a"), chunks: [{ sha256: hash("b"), size: 4 }, { sha256: hash("c"), size: 4 }] },
  ], components: { qt: { version: "1", files: [{ path: "qt.dll", sha256: hash("d") }] } } };
  const previous = { version: "1.0.0", platform: "win-x86_64", files: [
    { path: "app.exe", sha256: hash("e") },
  ], components: { qt: { version: "1", files: [{ path: "qt.dll", sha256: hash("d") }] } } };
  const currentText = envelope(current);
  bucket.put("manifests/2.0.0-win-x86_64.json", currentText);
  bucket.put("manifests/1.0.0-win-x86_64.json", envelope(previous));
  bucket.put("releases/stable.json", envelope({ channel: "stable", version: "2.0.0", platforms: {
    "win-x86_64": { manifest: "manifests/2.0.0-win-x86_64.json",
      size: currentText.length, sha256: createHash("sha256").update(currentText).digest("hex") },
  }, bootstrapper: { version: "1.0.0", platforms: { "win-x86_64": { sha256: hash("f") } } } }));
  return bucket;
}

test("live channels protect their manifest and bootstrapper, and old manifests protect repair objects", async () => {
  const bucket = fixture();
  const state = await inventory(bucket as never);
  assert.deepEqual([...state.active], ["manifests/2.0.0-win-x86_64.json"]);
  const refs = await liveHashes(bucket as never, state);
  assert.deepEqual(refs, new Set([hash("b"), hash("c"), hash("d"), hash("e"), hash("f")]));
  bucket.data.delete("manifests/1.0.0-win-x86_64.json");
  const afterRetire = await liveHashes(bucket as never, await inventory(bucket as never));
  assert.equal(afterRetire.has(hash("e")), false);
  assert.equal(afterRetire.has(hash("d")), true);
});

test("missing live manifests and malformed references fail closed", async () => {
  const bucket = fixture();
  bucket.data.delete("manifests/2.0.0-win-x86_64.json");
  await assert.rejects(inventory(bucket as never), BadBucket);
  assert.throws(() => channelReferences({ channel: "stable", platforms: {
    windows: { manifest: "../other.json", sha256: hash("a"), size: 12 },
  } }, "releases/stable.json"), BadBucket);
  assert.throws(() => manifestHashes({ version: "1.0.0", platform: "win-x86_64", files: [
    { sha256: "bad" },
  ], components: {} }, "manifests/1.0.0-win-x86_64.json"), BadBucket);
});

test("a canary-only bucket is scanned and its manifest stays protected", async () => {
  const bucket = fixture();
  const release = bucket.data.get("releases/stable.json")!.text;
  const payload = signedPayload(release, "releases/stable.json");
  bucket.data.delete("releases/stable.json");
  bucket.put("releases/canary.json", envelope({ ...payload, channel: "canary" }));
  const state = await inventory(bucket as never);
  assert.deepEqual(state.channels, ["releases/canary.json"]);
  assert.deepEqual(state.activeBy.get("manifests/2.0.0-win-x86_64.json"), ["canary"]);
  assert.equal((await liveHashes(bucket as never, state)).has(hash("f")), true);
});

test("every live channel is protected, and a changed channel invalidates an old scan", async () => {
  const bucket = fixture();
  const oldManifest = bucket.data.get("manifests/1.0.0-win-x86_64.json")!.text;
  const canary = envelope({ channel: "canary", version: "1.0.0", platforms: {
    "win-x86_64": { manifest: "manifests/1.0.0-win-x86_64.json",
      size: oldManifest.length, sha256: createHash("sha256").update(oldManifest).digest("hex") },
  } });
  bucket.put("releases/canary.json", canary);
  const state = await inventory(bucket as never);
  assert.equal(state.active.has("manifests/1.0.0-win-x86_64.json"), true);
  assert.deepEqual(state.activeBy.get("manifests/1.0.0-win-x86_64.json"), ["canary"]);
  assert.deepEqual(state.channelDetails.map(({ name, version, platforms }) => ({ name, version, platforms })), [
    { name: "stable", version: "2.0.0", platforms: ["win-x86_64"] },
    { name: "canary", version: "1.0.0", platforms: ["win-x86_64"] },
  ]);
  await liveHashes(bucket as never, state);
  bucket.put("releases/canary.json", envelope({ channel: "canary", version: "3.0.0", platforms: {
    "win-x86_64": { manifest: "manifests/3.0.0-win-x86_64.json", size: 10, sha256: hash("a") },
  } }));
  await assert.rejects(liveHashes(bucket as never, state), /Release channel changed during scan/);
});

test("objects need a minimum age, even when unreferenced", () => {
  assert.equal(isOldEnough(new Date("2026-10-01T00:00:00Z"), 7, Date.parse("2026-10-07T00:00:00Z")), false);
  assert.equal(isOldEnough(new Date("2026-09-30T00:00:00Z"), 7, Date.parse("2026-10-07T00:00:00Z")), true);
  assert.equal(signedPayload(envelope({ channel: "stable" }), "channel").channel, "stable");
});
