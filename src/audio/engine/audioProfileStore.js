import { get, set } from 'idb-keyval';
import { learnedAudioProfilesSchema } from './audioEngineSchemas.js';

const PROFILE_STORAGE_KEY = 'orchard:auto-eq-profiles:v1';
const MAX_PROFILES = 120;

export async function loadLearnedAudioProfiles() {
  try {
    const result = learnedAudioProfilesSchema.safeParse(await get(PROFILE_STORAGE_KEY));
    return result.success ? result.data : [];
  } catch {
    return [];
  }
}

export async function saveLearnedAudioProfiles(profiles) {
  const valid = learnedAudioProfilesSchema.parse(profiles);
  await set(PROFILE_STORAGE_KEY, valid.slice(-MAX_PROFILES));
}
