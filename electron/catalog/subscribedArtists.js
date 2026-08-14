/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Reads authenticated channel subscriptions and retains only official artist identities.
import { Innertube, UniversalCache } from 'youtubei.js';
import { withFreshYouTubeSession } from '../auth/youtubeClientSession.js';

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
    return `${browser.cookie || ''}\n${browser.accountIndex || 0}\n${browser.dataSyncId || ''}\n${browser.poToken || ''}`;
  }

  async function subscriptionClient() {
    const browser = authState.browser || {};
    if (!/(?:^|;\s*)SAPISID=/.test(browser.cookie || '')) {
      throw new Error('Browser YouTube sign-in is required to load artist subscriptions.');
    }

    const identity = browserIdentity();
    if (!clientPromise || clientIdentity !== identity) {
      clientIdentity = identity;
      clientPromise = Innertube.create(withFreshYouTubeSession({
        cache: new UniversalCache(true, cachePath),
        client_type: 'WEB',
        retrieve_player: false,
        generate_session_locally: true,
        cookie: browser.cookie,
        visitor_data: browser.visitorData || undefined,
        account_index: browser.accountIndex || 0,
        on_behalf_of_user: browser.dataSyncId || undefined,
        po_token: browser.poToken || undefined
      }));
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

  async function setArtistSubscription(channelId, subscribed) {
    const id = cleanText(channelId);
    if (!id) throw new Error('Artist channel ID is required.');
    const yt = await subscriptionClient();
    if (subscribed) await yt.interact.subscribe(id);
    else await yt.interact.unsubscribe(id);
    return { browseId: id, subscribed: Boolean(subscribed) };
  }

  return { setArtistSubscription, subscribedArtists };
}
