<script>
import { setupBrowseDetailView } from './browseDetailSetup.js';
import VirtualPlaylistTrackList from '../player/VirtualPlaylistTrackList.vue';

export default {
  name: 'BrowseDetailView',
  components: { VirtualPlaylistTrackList },
  props: { app: { type: Object, required: true } },
  setup: setupBrowseDetailView
};
</script>

<template>
        <main
          ref="detailPageRef"
          class="detail-page"
          :data-artist-id="browseDetail?.kind === 'artist' ? browseDetail?.browseId : null"
          :class="[
            {
              'detail-page--collection': browseDetail && browseDetail.kind !== 'artist',
              'detail-page--artist': browseDetail?.kind === 'artist',
              'detail-page--album': browseDetail?.kind === 'album',
              'detail-page--podcast': browseDetail?.kind === 'podcast',
              'detail-page--playlist': browseDetail?.kind === 'playlist'
            },
            browseDetail?.customLayout ? `detail-page--layout-${browseDetail.customLayout}` : ''
          ]"
        >
          <div v-if="browseLoading && !browseDetail" class="empty-state">Loading collection…</div>

          <template v-else-if="browseDetail">
            <section
              class="detail-hero"
              :class="{
                'detail-hero--artist': browseDetail.kind === 'artist',
                'detail-hero--collection': browseDetail.kind !== 'artist',
                'detail-hero--album': browseDetail.kind === 'album',
                'detail-hero--playlist': browseDetail.kind === 'playlist',
                'detail-hero--album-wall': customArtistAlbumWallTiles.length
              }"
              :style="detailHeroBackdrop"
            >
              <div
                v-if="customArtistAlbumWallTiles.length"
                class="custom-artist-album-wall"
                aria-hidden="true"
              >
                <div
                  v-for="(tile, tileIndex) in customArtistAlbumWallTiles"
                  :key="`custom-artist-album-wall-${tile.id || tile.image || tileIndex}`"
                  class="custom-artist-album-wall__tile"
                  :class="{ 'custom-artist-album-wall__tile--feature': tileIndex === 0 }"
                >
                  <q-img :src="tile.image" class="custom-artist-album-wall__image" />
                </div>
              </div>

              <div class="detail-hero__art">
                <div
                  v-if="playlistArtworkCollageItems.length"
                  class="detail-art detail-art--collage"
                  aria-hidden="true"
                >
                  <template
                    v-for="(tile, tileIndex) in playlistArtworkCollageItems"
                    :key="`playlist-artwork-tile-${tile.id || tileIndex}`"
                  >
                    <video
                      v-if="tile.videoUrl"
                      class="detail-art__tile"
                      :src="tile.videoUrl"
                      :poster="tile.poster || tile.image"
                      autoplay
                      muted
                      loop
                      playsinline
                      preload="metadata"
                      @canplay="playInlineArtworkVideo"
                      @pause="playInlineArtworkVideo"
                      @waiting="playInlineArtworkVideo"
                    />
                    <q-img
                      v-else
                      :src="tile.image"
                      class="detail-art__tile"
                    />
                  </template>
                </div>
                <video
                  v-else-if="detailArtworkVideo"
                  ref="detailArtworkVideoRef"
                  :key="detailArtworkVideo"
                  class="detail-art detail-art--video"
                  :src="detailArtworkVideo"
                  :poster="detailArtworkImage"
                  autoplay
                  muted
                  loop
                  playsinline
                  preload="auto"
                  @canplay="playDetailArtworkVideo"
                  @pause="keepDetailArtworkVideoPlaying"
                  @ended="restartDetailArtworkVideo"
                  @stalled="keepDetailArtworkVideoPlaying"
                  @waiting="keepDetailArtworkVideoPlaying"
                  @error="onDetailArtworkVideoError"
                />
                <q-img
                  v-else-if="detailArtworkImage"
                  :src="detailArtworkImage"
                  class="detail-art"
                  :class="{ 'custom-artist-page-art__image': isCustomArtistPage }"
                />
                <div v-else class="detail-art detail-art--empty">
                  <q-icon name="album" />
                </div>
              </div>

              <div class="detail-hero__copy">
                <div v-if="browseDetail.kind !== 'artist'" class="detail-collection-type">
                  {{ browseDetail.kind === 'album' ? albumTypeLabel(browseDetail) : browseDetail.kind === 'podcast' ? 'Podcast' : 'Playlist' }}
                </div>
                <div class="detail-title-line">
                  <h2>{{ browseDetail.title }}</h2>
                  <ExplicitBadge :explicit="browseDetail.explicit" />
                </div>
                <div v-if="artistGenreLabel" class="detail-artist-genre">
                  <q-icon name="auto_awesome" />
                  <span>{{ artistGenreLabel }}</span>
                </div>
                <button
                  v-if="browseDetail.kind === 'album' && (browseDetail.artist || browseDetail.subtitle)"
                  type="button"
                  class="detail-subtitle detail-subtitle--link"
                  :title="`Open ${browseDetail.artist || browseDetail.subtitle}`"
                  @click="openBrowseDetailArtist(browseDetail)"
                >
                  {{ browseDetail.artist || browseDetail.subtitle }}
                </button>
                <div
                  v-else-if="browseDetail.kind !== 'playlist' && (browseDetail.kind !== 'artist' || browseDetail.subtitle)"
                  class="detail-subtitle"
                >
                  <template v-for="(segment, index) in subtitleSegments" :key="index">
                    <span v-if="segment.highlight" class="artist-highlight">{{ segment.text }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </div>
                <div v-if="(browseDetail.kind === 'album' ? (browseDetail.releaseDateText || browseDetail.year) : (browseDetail.itemCount || browseDetail.year || browseDetail.totalDuration || browseDetail.views)) || browseDetail.hasEasterEgg" class="detail-meta">
                  <template v-if="browseDetail.kind === 'album'">
                    <span>{{ browseDetail.releaseDateText || browseDetail.year }}</span>
                  </template>
                  <template v-else>
                    <span v-if="browseDetail.itemCount">{{ browseDetail.itemCount }}</span>
                    <span v-if="browseDetail.year">{{ browseDetail.year }}</span>
                    <span v-if="browseDetail.totalDuration">{{ browseDetail.totalDuration }}</span>
                    <span v-if="browseDetail.views">{{ browseDetail.views }}</span>
                    <span v-if="browseDetail.hasEasterEgg" class="easter-egg-indicator" :title="`Type ${browseDetail.easterEggKeys?.join(' ')}`">
                      <q-icon name="keyboard" /> Type {{ browseDetail.easterEggKeys?.join(' ') }}
                    </span>
                  </template>
                </div>
                <p
                  v-if="browseDetail.description && (browseDetail.kind !== 'artist' || browseDetail.description !== browseDetail.subtitle)"
                  class="detail-description"
                >
                  <template v-for="(segment, index) in descriptionSegments" :key="index">
                    <span v-if="segment.highlight" class="artist-highlight">{{ segment.text }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </p>
                <button
                  v-if="canOpenDescription"
                  type="button"
                  class="detail-description-trigger"
                  :aria-label="`Read full description for ${browseDetail.title}`"
                  @click="openDescriptionDialog"
                >
                  <q-icon name="open_in_full" />
                  <span>{{ descriptionActionLabel }}</span>
                </button>
                <div class="detail-actions">
                  <div class="detail-actions__primary">
                    <button type="button" class="action-button action-button--primary" @click="playCollection(browseDetail)">
                      <q-icon name="play_arrow" />
                      <span>Play</span>
                    </button>
                    <button type="button" class="action-button" @click="playCollection(browseDetail, { shuffle: true })">
                      <q-icon name="shuffle" />
                      <span>Shuffle</span>
                    </button>
                    <button type="button" class="action-button" @click="shareBrowseDetailLink">
                      <q-icon name="ios_share" />
                      <span>Share</span>
                    </button>
                    <button
                      v-if="browseDetail.kind === 'playlist' && browseDetail.editable"
                      type="button"
                      class="action-button action-button--danger"
                      @click="openDeletePlaylistDialog(browseDetail)"
                    >
                      <q-icon name="delete_outline" />
                      <span>Delete</span>
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <q-dialog v-model="descriptionDialogOpen" aria-label="Full description">
              <q-card class="description-dialog-card">
                <div class="description-dialog__header">
                  <h3>{{ descriptionDialogTitle }}</h3>
                  <button
                    type="button"
                    class="description-dialog__close"
                    aria-label="Close description"
                    @click="descriptionDialogOpen = false"
                  >
                    <q-icon name="close" />
                  </button>
                </div>
                <div class="description-dialog__body">
                  <template v-for="(segment, index) in descriptionDialogSegments" :key="index">
                    <span v-if="segment.highlight" class="artist-highlight">{{ segment.text }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </div>
              </q-card>
            </q-dialog>

            <section
              class="table-card"
              :class="{
                'table-card--album': browseDetail.kind === 'album',
                'table-card--artist': browseDetail.kind === 'artist'
              }"
              :aria-label="`${browseDetail.title} tracks`"
            >
              <div v-if="browseDetail.kind === 'artist'" class="artist-track-heading">
                <h2>Popular</h2>
                <span>{{ browseDetail.tracks.length }} {{ browseDetail.tracks.length === 1 ? 'track' : 'tracks' }}</span>
              </div>
              <div class="table-head" :class="{ 'table-head--with-album': !['album', 'podcast'].includes(browseDetail.kind) }">
                <span>#</span>
                <span>Title</span>
                <span v-if="!['album', 'podcast'].includes(browseDetail.kind)">Artist</span>
                <span v-if="!['album', 'podcast'].includes(browseDetail.kind)">Album</span>
                <span>Time</span>
                <span />
              </div>

              <VirtualPlaylistTrackList
                v-if="browseDetail.kind === 'playlist'"
                ref="virtualPlaylistRef"
                :key="`virtual-playlist-${browseDetail.browseId || browseDetail.title}`"
                :app="app"
                :detail="browseDetail"
                :tracks="browseDetail.tracks"
              />

              <div v-if="browseDetail.kind !== 'playlist'" class="playlist-nonvirtual-tracks">
                <div
                v-for="(track, trackIndex) in browseDetail.tracks"
                :key="`browse-track-${track.id || track.index || track.title}`"
                class="table-row"
                :class="{
                  'table-row--active': activeTrack?.id === track.id,
                  'table-row--quick-search-match': collectionQuickSearchOpen && collectionQuickSearchFocusedTrackIndex === trackIndex,
                  'table-row--disabled': !isPlayableTrack(track),
                  'table-row--with-album': !['album', 'podcast'].includes(browseDetail.kind)
                }"
                :data-collection-track-index="trackIndex"
                :role="isPlayableTrack(track) ? 'button' : undefined"
                :tabindex="isPlayableTrack(track) ? 0 : -1"
                @click="playBrowseDetailTrack(track)"
                @keydown="onBrowseTrackRowKeydown($event, track)"
              >
                <span class="table-index">{{ track.index }}</span>
                <span class="table-track">
                  <q-img
                    v-if="collectionTrackCover(track, browseDetail)"
                    :src="collectionTrackCover(track, browseDetail)"
                    class="table-cover"
                  />
                  <span v-else class="table-cover table-cover--empty">
                    <q-icon name="music_note" />
                  </span>
                  <span class="table-copy">
                    <span class="explicit-title">
                      <strong @contextmenu="shareTrackSongLink(track, $event, browseDetail)">{{ track.title }}</strong>
                      <ExplicitBadge :explicit="track.explicit" />
                    </span>
                    <small>{{ itemMeta(track, browseDetail.artist) }}</small>
                  </span>
                </span>
                <span v-if="!['album', 'podcast'].includes(browseDetail.kind)" class="table-artist">
                  <template v-if="trackArtistLinks(track, browseDetail).length">
                    <template
                      v-for="(artist, artistIndex) in trackArtistLinks(track, browseDetail)"
                      :key="`track-artist-${track.id || track.index || track.title}-${artist.name}-${artistIndex}`"
                    >
                      <span v-if="artistIndex" class="table-link-separator">, </span>
                      <button
                        v-if="artist.browseId"
                        type="button"
                        class="table-link"
                        :title="`Open ${artist.name}`"
                        @click.stop="openBrowseTrackArtist(track, artist)"
                        @contextmenu="shareTrackSongLink(track, $event, browseDetail)"
                        @keydown.stop
                      >
                        {{ artist.name }}
                      </button>
                      <span v-else @contextmenu="shareTrackSongLink(track, $event, browseDetail)">{{ artist.name }}</span>
                    </template>
                  </template>
                  <span v-else>—</span>
                </span>
                <span v-if="!['album', 'podcast'].includes(browseDetail.kind)" class="table-album">
                  <button
                    v-if="track.albumId || track.futureAlbumId"
                    type="button"
                    class="table-album__button"
                    :title="`Open ${trackAlbumLabel(track)}`"
                    @click.stop="openBrowseTrackAlbum(track)"
                    @contextmenu="shareTrackSongLink(track, $event, browseDetail)"
                    @keydown.stop
                  >
                    {{ trackAlbumLabel(track) }}
                  </button>
                  <span v-else @contextmenu="shareTrackSongLink(track, $event, browseDetail)">{{ trackAlbumLabel(track) }}</span>
                </span>
                <span class="table-time">{{ track.duration || '—' }}</span>
                <button
                  type="button"
                  class="table-more"
                  :aria-label="`Actions for ${track.title}`"
                  title="Song actions"
                  @click.stop="openSongActionMenu(track, $event, browseDetail)"
                  @keydown.stop
                >
                  <q-icon v-if="isPlayableTrack(track)" name="more_horiz" />
                </button>
              </div>
              </div>

            </section>

            <section
              v-for="section in browseDetailSections"
              :key="`browse-section-${section.key}`"
              class="shelf-section"
            >
              <div class="section-header">
                <h2>{{ section.title }}</h2>
                <div class="section-header__actions">
                  <span>{{ sectionCount(section) }}</span>
                  <button v-if="sectionHasMore(section)" type="button" class="section-more-button" @click="openSectionMore(section)">
                    See all
                  </button>
                  <div class="shelf-nav">
                    <button type="button" :aria-label="`Scroll ${section.title} left`" @click="scrollShelf(section.key, -1)"><q-icon name="chevron_left" /></button>
                    <button type="button" :aria-label="`Scroll ${section.title} right`" @click="scrollShelf(section.key, 1)"><q-icon name="chevron_right" /></button>
                  </div>
                </div>
              </div>

              <div :ref="(element) => setShelfRail(section.key, element)" class="media-rail">
                <article
                  v-for="item in sectionPreviewItems(section)"
                  :key="`browse-card-${section.key}-${item.id || item.browseId || item.title}`"
                  class="media-card"
                  :class="{ 'media-card--artist': item.type === 'artist' }"
                  role="button"
                  tabindex="0"
                  @click="openMedia(item, section.items)"
                  @keydown.enter.prevent="openMedia(item, section.items)"
                  @keydown.space.prevent="openMedia(item, section.items)"
                  @keydown="onSongActionKeydown($event, item, browseDetail)"
                  @contextmenu="shareMediaSongLink(item, $event, browseDetail)"
                >
                  <q-img v-if="mediaThumbnail(item)" :src="mediaThumbnail(item)" class="media-card__art" />
                  <div v-else class="media-card__art media-card__art--empty">
                    <q-icon name="album" />
                  </div>
                  <div class="media-card__title explicit-title">
                    <span class="explicit-title__text">{{ item.title }}</span>
                    <ExplicitBadge :explicit="item.explicit" />
                  </div>
                  <div class="media-card__meta">{{ itemMeta(item) }}</div>
                  <div class="media-card__stat">{{ itemStat(item) }}</div>
                </article>
              </div>
            </section>

            <section
              v-if="browseDetailVideoSection"
              class="shelf-section album-video-section"
            >
              <div class="section-header">
                <h2>{{ browseDetailVideoSection.title }}</h2>
                <div class="section-header__actions">
                  <span>{{ sectionCount(browseDetailVideoSection) }}</span>
                  <div class="shelf-nav">
                    <button type="button" aria-label="Scroll music videos left" @click="scrollShelf(browseDetailVideoSection.key, -1)"><q-icon name="chevron_left" /></button>
                    <button type="button" aria-label="Scroll music videos right" @click="scrollShelf(browseDetailVideoSection.key, 1)"><q-icon name="chevron_right" /></button>
                  </div>
                </div>
              </div>

              <div :ref="(element) => setShelfRail(browseDetailVideoSection.key, element)" class="media-rail album-video-rail">
                <article
                  v-for="item in browseDetailVideoSection.items"
                  :key="`album-video-${item.id || item.title}`"
                  class="media-card album-video-card"
                  role="button"
                  tabindex="0"
                  @click="openMedia(item, browseDetailVideoSection.items)"
                  @keydown.enter.prevent="openMedia(item, browseDetailVideoSection.items)"
                  @keydown.space.prevent="openMedia(item, browseDetailVideoSection.items)"
                  @keydown="onSongActionKeydown($event, item, browseDetail)"
                  @contextmenu="shareMediaSongLink(item, $event, browseDetail)"
                >
                  <q-img v-if="trackCover(item)" :src="trackCover(item)" class="media-card__art album-video-card__art" />
                  <div v-else class="media-card__art media-card__art--empty album-video-card__art">
                    <q-icon name="smart_display" />
                  </div>
                  <div class="media-card__title explicit-title">
                    <span class="explicit-title__text">{{ item.title }}</span>
                    <ExplicitBadge :explicit="item.explicit" />
                  </div>
                  <div class="media-card__meta">{{ itemMeta(item, browseDetail.artist) }}</div>
                  <div class="media-card__stat">{{ itemStat(item) || 'Video' }}</div>
                </article>
              </div>
            </section>
          </template>
        </main>
</template>
