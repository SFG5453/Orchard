// Extracts playlist item counts from localized metadata when structured counts are absent.
const countPattern = /([\d,]+)\s+(songs?|tracks?|videos?)\b/i;

export function playlistItemCount(value) {
  const match = String(value || '').match(countPattern);
  if (!match) return null;

  const count = Number(match[1].replace(/,/g, ''));
  if (!Number.isFinite(count) || count < 1) return null;
  return { count, label: `${count.toLocaleString('en-US')} ${match[2].toLowerCase()}` };
}

function collectCountMatches(value, matches, seen, depth = 0) {
  if (value == null || depth > 8) return;
  if (typeof value === 'string' || typeof value === 'number') {
    const match = playlistItemCount(value);
    if (match) matches.push(match);
    return;
  }
  if (typeof value !== 'object' || seen.has(value)) return;

  seen.add(value);
  const rendered = value.toString !== Object.prototype.toString ? value.toString() : '';
  const renderedMatch = playlistItemCount(rendered);
  if (renderedMatch) matches.push(renderedMatch);

  for (const [key, child] of Object.entries(value)) {
    if (/contents|items|thumbnail/i.test(key)) continue;
    collectCountMatches(child, matches, seen, depth + 1);
  }
}

export function playlistTotalItemCount(data, ...candidateValues) {
  const shelf = data?.contents?.twoColumnBrowseResultsRenderer?.secondaryContents
    ?.sectionListRenderer?.contents?.find((entry) => entry?.musicPlaylistShelfRenderer)
    ?.musicPlaylistShelfRenderer;
  const matches = [];
  const seen = new WeakSet();

  collectCountMatches(data?.header, matches, seen);
  collectCountMatches(shelf?.header, matches, seen);
  collectCountMatches(shelf?.numItemsText || shelf?.num_items_text, matches, seen);
  candidateValues.forEach((value) => collectCountMatches(value, matches, seen));

  return matches.sort((left, right) => right.count - left.count)[0] || null;
}
