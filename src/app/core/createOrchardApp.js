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

import { installArtworkService } from '../appearance/artworkService.js';
import { installArtistGenreActions } from '../browse/artistGenreActions.js';
import { installAudioEngineActions } from '../playback/audioEngineActions.js';
import { installAudioEngineState } from '../playback/audioEngineState.js';
import { installAppearanceLifecycle } from '../appearance/appearanceLifecycle.js';
import { installAutoplayActions } from '../playback/autoplayActions.js';
import { installBrowseActions } from '../browse/browseActions.js';
import { installChangelogActions } from './changelogActions.js';
import { installCollectionActions } from '../browse/collectionActions.js';
import { installCollectionQuickSearchActions } from '../browse/collectionQuickSearchActions.js';
import { installComputedState } from './computedState.js';
import { installConnectionActions } from './connectionActions.js';
import { installConnectActions } from '../platform/connectActions.js';
import { installCustomArtistIdlePreviewActions } from '../appearance/customArtistIdlePreviewActions.js';
import { installCustomArtistProfileArtwork } from '../appearance/customArtistProfileArtwork.js';
import { installCustomArtistProfileTransition } from '../appearance/customArtistProfileTransition.js';
import { installDesktopActions } from '../platform/desktopActions.js';
import { installLegacyInstallActions } from '../platform/legacyInstallActions.js';
import { installLifecycle } from './lifecycle.js';
import { installListeningPartyActions } from '../social/listeningPartyActions.js';
import { installLastfmScrobbling } from '../social/lastfmScrobbling.js';
import { installMediaHandlers } from '../playback/mediaHandlers.js';
import { installNavigationActions } from './navigationActions.js';
import { installPlaybackControls } from '../playback/playbackControls.js';
import { installPlaybackResolve } from '../playback/playbackResolve.js';
import { installPlaylistActions } from '../browse/playlistActions.js';
import { installPodcastActions } from '../browse/podcastActions.js';
import { installQueueTransitionSort } from '../playback/queueTransitionSort.js';
import { installReplayStats } from '../playback/replayStats.js';
import { installReadinessActions } from './readinessActions.js';
import { installRadioActions } from '../browse/radioActions.js';
import { installReleaseRadarActions } from '../browse/releaseRadarActions.js';
import { installSearchLinkActions } from '../social/searchLinkActions.js';
import { installSessionHistoryActions } from '../playback/sessionHistoryActions.js';
import { installShareActions } from '../social/shareActions.js';
import { installSleepTimerActions } from '../playback/sleepTimerActions.js';
import { installSmartQueueActions } from '../playback/smartQueueActions.js';
import { installSmartCrossfadeActions } from '../playback/smartCrossfadeActions.js';
import { installSongActions } from '../browse/songActions.js';
import { installSongCacheActions } from '../playback/songCacheActions.js';
import { installSponsorBlockActions } from '../playback/sponsorBlockActions.js';
import { installSpotlightActions } from '../browse/spotlightActions.js';
import { installState } from './state.js';
import { installSystemMediaActions } from '../platform/systemMediaActions.js';
import { installSupportActions } from '../platform/supportActions.js';
import { installUpdateActions } from '../platform/updateActions.js';
import { installNetworkActions } from '../platform/networkActions.js';
import { installVisualUtils } from '../appearance/visualUtils.js';
import { installYouTubeHistoryActions } from '../browse/youtubeHistoryActions.js';
import { installYouTubeLikesActions } from '../browse/youtubeLikesActions.js';

/**
 * Composes the renderer application onto one deliberately mutable context.
 * Installer order is behavior-critical: later domains consume state and methods
 * installed earlier. Keep these direct imports visible instead of hiding the
 * dependency graph behind barrels.
 * @returns {object} Context owned by the root Vue application lifecycle.
 */
export function createOrchardApp() {
  const ctx = {};

  installVisualUtils(ctx);
  installAudioEngineState(ctx);
  installState(ctx);
  installLastfmScrobbling(ctx);
  installYouTubeHistoryActions(ctx);
  installYouTubeLikesActions(ctx);
  installSmartCrossfadeActions(ctx);
  installQueueTransitionSort(ctx);
  installAppearanceLifecycle(ctx);
  installComputedState(ctx);
  installNavigationActions(ctx);
  installConnectionActions(ctx);
  installSearchLinkActions(ctx);
  installCollectionQuickSearchActions(ctx);
  installSpotlightActions(ctx);
  installAudioEngineActions(ctx);
  installConnectActions(ctx);
  installBrowseActions(ctx);
  installArtistGenreActions(ctx);
  installCustomArtistIdlePreviewActions(ctx);
  installCustomArtistProfileArtwork(ctx);
  installCustomArtistProfileTransition(ctx);
  installRadioActions(ctx);
  installPodcastActions(ctx);
  installPlaybackResolve(ctx);
  installPlaylistActions(ctx);
  installShareActions(ctx);
  installPlaybackControls(ctx);
  installSponsorBlockActions(ctx);
  installSongActions(ctx);
  installSongCacheActions(ctx);
  installReplayStats(ctx);
  installReleaseRadarActions(ctx);
  installSessionHistoryActions(ctx);
  installReadinessActions(ctx);
  installSupportActions(ctx);
  installSmartQueueActions(ctx);
  installCollectionActions(ctx);
  installAutoplayActions(ctx);
  installUpdateActions(ctx);
  installLegacyInstallActions(ctx);
  installNetworkActions(ctx);
  installChangelogActions(ctx);
  installArtworkService(ctx);
  installMediaHandlers(ctx);
  installSystemMediaActions(ctx);
  installDesktopActions(ctx);
  installSleepTimerActions(ctx);
  installListeningPartyActions(ctx);
  installLifecycle(ctx);

  return ctx;
}
