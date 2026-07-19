<script>
import { computed, nextTick, ref, watch } from 'vue';

export default {
  name: 'SongShareDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const dialogRef = ref(null);
    const nativeShareAvailable = computed(() => typeof navigator !== 'undefined' && Boolean(navigator.share));
    const dialogItem = computed(() => props.app.songShareDialog.value.collection || props.app.songShareDialog.value.song || props.app.songShareDialog.value.payload || {});
    const itemTitle = computed(() => dialogItem.value.title || 'Shared item');
    const itemSubtitle = computed(() => props.app.songShareDialog.value.collection
      ? [dialogItem.value.subtitle, dialogItem.value.itemCount].filter(Boolean).join(' • ')
      : [dialogItem.value.artist, dialogItem.value.album].filter(Boolean).join(' • '));
    const artworkUrl = computed(() => dialogItem.value.thumbnailUrl || '');
    const emptyIcon = computed(() => props.app.songShareDialog.value.collection ? 'album' : 'music_note');

    watch(() => props.app.songShareDialog.value.open, async (open) => {
      if (!open) return;
      await nextTick();
      dialogRef.value?.querySelector('button:not(:disabled), a[href]')?.focus();
    });

    function onDialogKeydown(event) {
      if (event.key === 'Escape') props.app.closeSongShareDialog();
    }

    return {
      ...props.app,
      artworkUrl,
      dialogRef,
      emptyIcon,
      itemSubtitle,
      itemTitle,
      nativeShareAvailable,
      onDialogKeydown,
      dialogItem
    };
  }
};
</script>

<template>
  <Teleport to="body">
    <div
      v-if="songShareDialog.open"
      class="song-share-dialog-layer"
      @mousedown.self="closeSongShareDialog"
    >
      <section
        ref="dialogRef"
        class="song-share-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="`Share ${itemTitle}`"
        @keydown="onDialogKeydown"
      >
        <button type="button" class="song-share-dialog__close" aria-label="Close" @click="closeSongShareDialog">
          <q-icon name="close" />
        </button>

        <div class="song-share-dialog__track">
          <q-img v-if="artworkUrl" :src="artworkUrl" class="song-share-dialog__cover" />
          <span v-else class="song-share-dialog__cover song-share-dialog__cover--empty">
            <q-icon :name="emptyIcon" />
          </span>
          <span class="song-share-dialog__copy">
            <strong>{{ itemTitle }}</strong>
            <span>{{ itemSubtitle }}</span>
          </span>
        </div>

        <div class="song-share-dialog__actions">
          <button type="button" :disabled="!songShareDialog.shareUrl" @click="copySongShareUrl">
            <q-icon name="content_copy" />
            <span>Copy link</span>
          </button>
          <button type="button" :disabled="!songShareDialog.shareUrl" @click="openSongShareUrl(songShareDialog.shareUrl)">
            <q-icon name="open_in_new" />
            <span>Open page</span>
          </button>
          <button
            v-if="nativeShareAvailable"
            type="button"
            :disabled="!songShareDialog.shareUrl"
            @click="nativeShareSongUrl"
          >
            <q-icon name="ios_share" />
            <span>Share</span>
          </button>
        </div>

        <div v-if="songShareDialog.loading" class="song-share-dialog__status">
          <q-spinner size="18px" />
          <span>Creating link...</span>
        </div>

        <div v-else-if="songShareDialog.error" class="song-share-dialog__status song-share-dialog__status--error">
          <q-icon name="warning" />
          <span>{{ songShareDialog.error }}</span>
        </div>

        <div v-else class="song-share-dialog__services">
          <button
            v-for="link in songShareDialog.links"
            :key="`${link.platform}-${link.url}`"
            type="button"
            class="song-share-dialog__service"
            :class="songSharePlatformClass(link)"
            @click="openSongShareUrl(link.url)"
          >
            <span class="song-share-dialog__service-mark" aria-hidden="true">
              <img v-if="songSharePlatformLogoUrl(link)" :src="songSharePlatformLogoUrl(link)" alt="">
              <span v-else>{{ link.label.slice(0, 1) }}</span>
            </span>
            <span class="song-share-dialog__service-copy">
              <strong>{{ link.label }}</strong>
              <span>{{ songShareLinkKind(link) }}</span>
            </span>
            <span class="song-share-dialog__service-action">{{ songShareActionText(link) }}</span>
          </button>

          <p v-if="!songShareDialog.links.length" class="song-share-dialog__empty">
            Orchard created the share page. Copy the link to send it.
          </p>
        </div>
      </section>
    </div>
  </Teleport>
</template>
