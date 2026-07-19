export function normalizeBackgroundUrl(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function isHlsSource(value) {
  const source = normalizeBackgroundUrl(value);
  if (!source) return false;

  try {
    return new URL(source, 'https://orchard.invalid').pathname.toLowerCase().endsWith('.m3u8');
  } catch {
    return /\.m3u8(?:$|[?#])/i.test(source);
  }
}

export function interpolateRgb(from, to, progress) {
  const amount = Math.max(0, Math.min(1, Number(progress) || 0));
  return [0, 1, 2].map((index) => Math.round(from[index] + ((to[index] - from[index]) * amount)));
}
