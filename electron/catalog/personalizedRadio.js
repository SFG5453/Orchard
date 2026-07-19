// Builds a personalized radio feed from injected authenticated music clients.
function textValue(value = {}) {
  return value?.simpleText || value?.runs?.map((run) => run?.text || '').join('') || '';
}

function bestRawThumbnail(renderer = {}) {
  const thumbnails = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails ||
    renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails ||
    renderer.thumbnail?.thumbnails ||
    [];

  return thumbnails.at(-1)?.url || thumbnails[0]?.url || null;
}

function collectMixItems(value, items = []) {
  if (!value || typeof value !== 'object') return items;

  const renderer = value.musicTwoRowItemRenderer;
  if (renderer) {
    const titleRun = renderer.title?.runs?.[0] || {};
    const browsePayload = renderer.navigationEndpoint?.browseEndpoint ||
      titleRun.navigationEndpoint?.browseEndpoint ||
      null;
    const title = textValue(renderer.title);

    if (browsePayload?.browseId && /^(My Supermix|My Mix \d+|Discover Mix)$/i.test(title)) {
      items.push({
        type: 'playlist',
        title,
        subtitle: textValue(renderer.subtitle),
        thumbnail: bestRawThumbnail(renderer),
        browseId: browsePayload.browseId,
        browsePayload: { ...browsePayload }
      });
    }
  }

  for (const child of Object.values(value)) {
    if (Array.isArray(child)) child.forEach((entry) => collectMixItems(entry, items));
    else if (child && typeof child === 'object') collectMixItems(child, items);
  }

  return items;
}

export function createPersonalizedRadio({
  musicClientForBrowse,
  resolveMusicCollectionWithFallback
}) {
  return async function personalizedRadio() {
    const yt = await musicClientForBrowse();
    const collection = await resolveMusicCollectionWithFallback(yt, 'mixed', {
      browseId: 'FEmusic_mixed_for_you'
    });
    const mixes = collectMixItems(collection.data);
    const radio = mixes.find((item) => item.title === 'My Supermix') ||
      mixes.find((item) => /^My Mix \d+$/i.test(item.title)) ||
      mixes.find((item) => item.title === 'Discover Mix');

    if (!radio) throw new Error('YouTube Music did not return a personalized radio station.');
    return radio;
  };
}
