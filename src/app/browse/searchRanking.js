function abbreviatedNumber(value) {
  const match = String(value || '').match(/([\d,.]+)\s*([kmbt])?/i);
  if (!match) return 0;

  const number = Number(match[1].replace(/,/g, ''));
  const multiplier = { k: 1e3, m: 1e6, b: 1e9, t: 1e12 }[match[2]?.toLowerCase()] || 1;
  return Number.isFinite(number) ? number * multiplier : 0;
}

function searchPopularity(item) {
  const directViews = abbreviatedNumber(item?.views);
  if (directViews) return directViews;

  const metadata = [item?.subtitle, item?.itemCount].filter(Boolean).join(' ');
  const match = metadata.match(
    /([\d,.]+\s*[kmbt]?)\s*(?:subscribers?|views?|plays?|monthly\s+(?:audience|listeners?))/i
  );
  return match ? abbreviatedNumber(match[1]) : 0;
}

function normalizedRankingText(value = '') {
  return String(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function topMatchRelevance(item, query) {
  const normalizedQuery = normalizedRankingText(query);
  const normalizedTitle = normalizedRankingText(item?.title);
  if (!normalizedQuery || !normalizedTitle) return 0;

  const aliasMatches = (item?.searchAliases || [])
    .map(normalizedRankingText)
    .some((alias) => alias === normalizedQuery);
  if (aliasMatches) return 86;

  const isPhraseQuery = normalizedQuery.includes(' ');
  if (normalizedTitle === normalizedQuery) return isPhraseQuery ? 70 : 40;
  if (normalizedTitle.startsWith(`${normalizedQuery} `)) return 34;
  if (normalizedTitle.split(' ').some((part) => part.startsWith(normalizedQuery))) return 28;
  if (normalizedTitle.includes(normalizedQuery)) return 18;

  const haystack = normalizedRankingText([
    normalizedTitle,
    item?.artist,
    ...(item?.artists || []),
    ...(item?.searchAliases || []),
    item?.subtitle,
    item?.album
  ].filter(Boolean).join(' '));
  if (haystack.includes(normalizedQuery)) return 24;

  const queryTokens = normalizedQuery.split(' ').filter(Boolean);
  const haystackTokens = new Set(haystack.split(' ').filter(Boolean));
  if (queryTokens.length > 1 && queryTokens.every((token) => haystackTokens.has(token))) return 20;
  if (queryTokens.some((token) => [...haystackTokens].some((candidate) => candidate.startsWith(token)))) return 12;

  return 0;
}

export function sortBySearchPopularity(items = []) {
  return items
    .map((item, index) => ({
      item,
      index,
      popularity: searchPopularity(item),
      priority: Number(item?.customSearchPriority || 0)
    }))
    .sort((left, right) => right.priority - left.priority || right.popularity - left.popularity || left.index - right.index)
    .map(({ item }) => item);
}

export function sortByTopMatch(items = [], query = '') {
  return items
    .map((item, index) => ({
      item,
      index,
      popularity: searchPopularity(item),
      relevance: topMatchRelevance(item, query)
    }))
    .filter(({ relevance }) => relevance > 0)
    .sort((left, right) => {
      const leftScore = left.relevance + Math.log10(left.popularity + 1) * 10;
      const rightScore = right.relevance + Math.log10(right.popularity + 1) * 10;
      return rightScore - leftScore || right.popularity - left.popularity || left.index - right.index;
    })
    .map(({ item }) => item);
}
