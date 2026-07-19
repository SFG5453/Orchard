// Fetches and normalizes the optional BetterLyrics provider response.
const betterLyricsBaseUrl = 'https://lyrics-api.boidu.dev/getLyrics';

export async function resolveBetterLyrics(metadata, { fetchWithTimeout, parseTtml }) {
  const params = new URLSearchParams({
    s: metadata.title,
    a: metadata.artist
  });
  if (metadata.album) params.append('al', metadata.album);
  if (metadata.durationMs) params.append('d', Math.round(metadata.durationMs / 1000).toString());
  if (metadata.videoId) params.append('videoId', metadata.videoId);

  const response = await fetchWithTimeout(`${betterLyricsBaseUrl}?${params.toString()}`);
  if (!response.ok) return null;

  const payload = await response.json();
  const ttml = typeof payload?.ttml === 'string'
    ? payload.ttml
    : typeof payload?.lyrics === 'string' ? payload.lyrics : '';
  return ttml ? parseTtml(ttml) : null;
}
