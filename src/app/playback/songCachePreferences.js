export const SONG_CACHE_DEFAULTS = {
  enabled: true,
  maxSizeMb: 512
};

export function clampSongCacheMaxSizeMb(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return SONG_CACHE_DEFAULTS.maxSizeMb;
  return Math.min(4096, Math.max(128, Math.round(numeric / 128) * 128));
}
