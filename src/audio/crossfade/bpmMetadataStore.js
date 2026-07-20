import { get, set } from 'idb-keyval';

const STORAGE_KEY = 'orchard:bpm-metadata:v1';

export function createBpmMetadataStorage() {
  return {
    async load() {
      try {
        const records = await get(STORAGE_KEY);
        return Array.isArray(records) ? records : [];
      } catch {
        return [];
      }
    },
    async save(records) {
      try {
        await set(STORAGE_KEY, records);
      } catch {
        // BPM metadata is an optimization; storage failures fall back to memory.
      }
    }
  };
}
