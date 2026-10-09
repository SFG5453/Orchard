const HASH = /^[0-9a-f]{64}$/;
const MANIFEST_KEY = /^manifests\/[A-Za-z0-9._-]+\.json$/;
const OBJECT_KEY = /^objects\/[0-9a-f]{64}$/;
const MAX_CHANNELS = 32;
const MAX_MANIFESTS = 400;
const MAX_CHANNEL_BYTES = 1 << 20;
const MAX_MANIFEST_BYTES = 8 << 20;
const MAX_REFERENCES = 100_000;

export class BadBucket extends Error {}
export class Conflict extends Error {}

type RecordValue = Record<string, unknown>;

function record(value: unknown, label: string): RecordValue {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new BadBucket(`${label} must be an object`);
  return value as RecordValue;
}

function string(value: unknown, label: string): string {
  if (typeof value !== "string" || !value)
    throw new BadBucket(`${label} must be a nonempty string`);
  return value;
}

function objectEntries(value: unknown, label: string): [string, unknown][] {
  return Object.entries(record(value, label));
}

function fileHashes(value: unknown, label: string): string[] {
  const file = record(value, label);
  const parts = file.chunks;
  if (parts === undefined) {
    const sha = string(file.sha256, `${label}.sha256`);
    if (!HASH.test(sha)) throw new BadBucket(`${label} has an invalid hash`);
    return [sha];
  }
  if (!Array.isArray(parts) || parts.length === 0)
    throw new BadBucket(`${label}.chunks must be a nonempty array`);
  return parts.map((part, i) => {
    const sha = string(record(part, `${label}.chunks[${i}]`).sha256, `${label}.chunks[${i}].sha256`);
    if (!HASH.test(sha)) throw new BadBucket(`${label} has an invalid chunk hash`);
    return sha;
  });
}

export function signedPayload(text: string, label: string): RecordValue {
  let envelope: RecordValue;
  try { envelope = record(JSON.parse(text), label); }
  catch { throw new BadBucket(`${label} has invalid JSON`); }
  if (envelope.format !== "orchard-signed-v1" || envelope.algorithm !== "ecdsa-p256-sha256")
    throw new BadBucket(`${label} has an unknown signed envelope`);
  let payload: unknown;
  try {
    const encoded = string(envelope.payload, `${label}.payload`);
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) throw new Error("base64");
    payload = JSON.parse(atob(encoded));
  } catch { throw new BadBucket(`${label} has an invalid payload`); }
  return record(payload, `${label}.payload`);
}

export function manifestHashes(payload: RecordValue, key: string): Set<string> {
  const version = string(payload.version, `${key}.version`);
  const platform = string(payload.platform, `${key}.platform`);
  if (key !== `manifests/${version}-${platform}.json`)
    throw new BadBucket(`${key} does not match its payload`);
  if (!Array.isArray(payload.files)) throw new BadBucket(`${key}.files must be an array`);
  const hashes = new Set<string>();
  for (const [i, file] of payload.files.entries())
    for (const hash of fileHashes(file, `${key}.files[${i}]`)) hashes.add(hash);
  for (const [name, value] of objectEntries(payload.components, `${key}.components`)) {
    const files = record(value, `${key}.components.${name}`).files;
    if (!Array.isArray(files)) throw new BadBucket(`${key}.components.${name}.files must be an array`);
    for (const [i, file] of files.entries())
      for (const hash of fileHashes(file, `${key}.components.${name}.files[${i}]`)) hashes.add(hash);
  }
  return hashes;
}

export function channelReferences(payload: RecordValue, key: string): {
  manifests: Map<string, { sha256: string; size: number }>;
  objects: Set<string>;
} {
  if (key !== `releases/${string(payload.channel, `${key}.channel`)}.json`)
    throw new BadBucket(`${key} does not match its payload`);
  const manifests = new Map<string, { sha256: string; size: number }>();
  for (const [platform, value] of objectEntries(payload.platforms, `${key}.platforms`)) {
    const ref = record(value, `${key}.platforms.${platform}`);
    const manifest = string(ref.manifest, `${key}.platforms.${platform}.manifest`);
    const sha256 = string(ref.sha256, `${key}.platforms.${platform}.sha256`);
    const size = ref.size;
    if (!MANIFEST_KEY.test(manifest) || !HASH.test(sha256) ||
        typeof size !== "number" || !Number.isSafeInteger(size) || size <= 0)
      throw new BadBucket(`${key} has an invalid manifest reference`);
    manifests.set(manifest, { sha256, size });
  }
  const objects = new Set<string>();
  if (payload.bootstrapper !== undefined) {
    const boot = record(payload.bootstrapper, `${key}.bootstrapper`);
    for (const [platform, entry] of objectEntries(boot.platforms, `${key}.bootstrapper.platforms`))
      for (const hash of fileHashes(entry, `${key}.bootstrapper.${platform}`)) objects.add(hash);
  }
  return { manifests, objects };
}

async function listAll(bucket: R2Bucket, prefix: string, max: number): Promise<R2Object[]> {
  const objects: R2Object[] = [];
  let cursor: string | undefined;
  do {
    const page = await bucket.list({ prefix, cursor, limit: 1000 });
    objects.push(...page.objects);
    if (objects.length > max) throw new BadBucket(`Too many ${prefix} entries (limit ${max}); cleanup stopped`);
    cursor = page.truncated ? page.cursor : undefined;
    if (page.truncated && !cursor) throw new BadBucket(`R2 did not return a cursor for ${prefix}`);
  } while (cursor);
  return objects;
}

async function readSmall(bucket: R2Bucket, key: string, max: number): Promise<string> {
  const object = await bucket.get(key);
  if (!object) throw new BadBucket(`${key} disappeared during scan`);
  if (object.size > max) throw new BadBucket(`${key} exceeds the ${max} byte safety limit`);
  return object.text();
}

export interface Inventory {
  channels: string[];
  channelDetails: { key: string; name: string; version: string; platforms: string[]; uploaded: Date; objectVersion: string }[];
  activeBy: Map<string, string[]>;
  active: Set<string>;
  manifests: R2Object[];
}

// The bucket has no crystal ball for old installs; every current channel stays protected.
export async function inventory(bucket: R2Bucket): Promise<Inventory> {
  const channelObjects = await listAll(bucket, "releases/", MAX_CHANNELS);
  if (channelObjects.length === 0) throw new BadBucket("No release channels found; cleanup stopped");
  const manifests = await listAll(bucket, "manifests/", MAX_MANIFESTS);
  const known = new Set(manifests.map((entry) => entry.key));
  const active = new Set<string>();
  // Canary gets the same bodyguard as stable: every published reference is protected.
  const activeBy = new Map<string, string[]>();
  const channelDetails: Inventory["channelDetails"] = [];
  for (const entry of channelObjects) {
    if (!/^releases\/[A-Za-z0-9._-]+\.json$/.test(entry.key))
      throw new BadBucket(`Unexpected key ${entry.key} in releases/`);
    const payload = signedPayload(await readSmall(bucket, entry.key, MAX_CHANNEL_BYTES), entry.key);
    const refs = channelReferences(payload, entry.key);
    if (refs.manifests.size === 0) throw new BadBucket(`${entry.key} names no manifests`);
    const name = string(payload.channel, `${entry.key}.channel`);
    const version = string(payload.version, `${entry.key}.version`);
    const platforms = Object.keys(record(payload.platforms, `${entry.key}.platforms`));
    channelDetails.push({ key: entry.key, name, version, platforms, uploaded: entry.uploaded, objectVersion: entry.version });
    for (const key of refs.manifests.keys()) {
      if (!known.has(key)) throw new BadBucket(`Live channel references missing ${key}`);
      active.add(key);
      activeBy.set(key, [...(activeBy.get(key) ?? []), name]);
    }
  }
  return { channels: channelObjects.map((entry) => entry.key), channelDetails, activeBy, active, manifests };
}

export async function liveHashes(bucket: R2Bucket, snapshot: Inventory): Promise<Set<string>> {
  const hashes = new Set<string>();
  const pinned = new Map<string, { sha256: string; size: number }>();
  const known = new Set(snapshot.manifests.map((entry) => entry.key));
  for (const key of snapshot.channels) {
    const payload = signedPayload(await readSmall(bucket, key, MAX_CHANNEL_BYTES), key);
    const refs = channelReferences(payload, key);
    for (const [manifest, ref] of refs.manifests) {
      if (!known.has(manifest)) throw new Conflict(`Release channel changed during scan; rescan before deleting`);
      pinned.set(manifest, ref);
    }
    for (const hash of refs.objects) hashes.add(hash);
  }
  // Read every retained manifest, including old versions needed for repair.
  for (const entry of snapshot.manifests) {
    if (!MANIFEST_KEY.test(entry.key)) throw new BadBucket(`Unexpected key ${entry.key} in manifests/`);
    const text = await readSmall(bucket, entry.key, MAX_MANIFEST_BYTES);
    const ref = pinned.get(entry.key);
    if (ref) {
      const bytes = new TextEncoder().encode(text);
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), (b) => b.toString(16).padStart(2, "0")).join("");
      if (bytes.length !== ref.size || digest !== ref.sha256)
        throw new BadBucket(`Live manifest ${entry.key} does not match its channel`);
    }
    for (const hash of manifestHashes(signedPayload(text, entry.key), entry.key)) hashes.add(hash);
    if (hashes.size > MAX_REFERENCES)
      throw new BadBucket(`Too many referenced objects (limit ${MAX_REFERENCES}); cleanup stopped`);
  }
  return hashes;
}

export function isObjectKey(key: string): boolean { return OBJECT_KEY.test(key); }

export function isOldEnough(uploaded: Date, days: number, now = Date.now()): boolean {
  return now - uploaded.getTime() >= days * 86_400_000;
}

export function objectAgeDays(value: string): number {
  const days = Number(value);
  if (!Number.isSafeInteger(days) || days < 1 || days > 365)
    throw new BadBucket("MIN_OBJECT_AGE_DAYS must be an integer from 1 to 365");
  return days;
}
