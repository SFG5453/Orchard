// Reads authenticated channel subscriptions and retains only official artist identities.
import { Innertube, UniversalCache } from 'youtubei.js';

const MAX_SUBSCRIPTION_PAGES = 20;

function cleanText(value = '') {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function badgeText(badge = {}) {
  return cleanText(badge.label || badge.tooltip || badge.icon_type || badge.style || badge.type);
}

export function isOfficialArtistChannel(channel = {}) {
  const author = channel.author || {};
  if (author.is_verified_artist) return true;

  return [...(channel.badges || author.badges || [])]
    .some((badge) => /official artist channel|verified.artist/i.test(badgeText(badge)));
}

export function subscribedArtistFromChannel(channel = {}) {
  if (!isOfficialArtistChannel(channel)) return null;
  const author = channel.author || {};
  const name = cleanText(author.name || channel.title);
  const browseId = cleanText(channel.id || author.id || channel.endpoint?.payload?.browseId);
  if (!name || !browseId) return null;
  const thumbnails = [...(author.thumbnails || channel.thumbnails || [])];

  return {
    name,
    title: name,
    browseId,
    type: 'artist',
    thumbnail: cleanText(thumbnails.at(-1)?.url || author.best_thumbnail?.url || thumbnails[0]?.url),
    subtitle: 'Subscribed on YouTube'
  };
}

export function subscribedArtistsFromChannels(channels = []) {
  const seen = new Set();

  return channels
    .map(subscribedArtistFromChannel)
    .filter((artist) => {
      const key = cleanText(artist?.browseId || artist?.name).toLowerCase();
      if (!artist || !key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

export function createSubscribedArtistsService({ authState, cachePath }) {
  let clientIdentity = '';
  let clientPromise;

  function browserIdentity() {
    const browser = authState.browser || {};
    return `${browser.cookie || ''}\n${browser.dataSyncId || ''}\n${browser.poToken || ''}`;
  }

  async function subscriptionClient() {
    const browser = authState.browser || {};
    if (!/(?:^|;\s*)SAPISID=/.test(browser.cookie || '')) {
      throw new Error('Browser YouTube sign-in is required to load artist subscriptions.');
    }

    const identity = browserIdentity();
    if (!clientPromise || clientIdentity !== identity) {
      clientIdentity = identity;
      clientPromise = Innertube.create({
        cache: new UniversalCache(true, cachePath),
        client_type: 'WEB',
        retrieve_player: false,
        generate_session_locally: true,
        cookie: browser.cookie,
        visitor_data: browser.visitorData || undefined,
        on_behalf_of_user: browser.dataSyncId || undefined,
        po_token: browser.poToken || undefined
      });
    }

    try {
      return await clientPromise;
    } catch (error) {
      clientPromise = null;
      clientIdentity = '';
      throw error;
    }
  }

  async function subscribedArtists() {
    const yt = await subscriptionClient();
    const channels = [];
    let feed = await yt.getChannelsFeed();
    let pageCount = 0;

    while (feed && pageCount < MAX_SUBSCRIPTION_PAGES) {
      channels.push(...feed.channels);
      pageCount += 1;
      feed = feed.has_continuation ? await feed.getContinuation() : null;
    }

    return subscribedArtistsFromChannels(channels);
  }

  return { subscribedArtists };
}
