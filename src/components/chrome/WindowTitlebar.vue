<script>
export default {
  name: 'WindowTitlebar',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>

    <header class="window-titlebar">
      <div class="titlebar-transport">
        <q-btn flat round dense icon="skip_previous" :disable="!activeTrack || buffering" @click="playPrevious" />
        <q-btn
          flat
          round
          dense
          :loading="buffering"
          :disable="!activeTrack"
          :icon="isPlaying ? 'pause' : 'play_arrow'"
          @click="togglePlayback"
        />
        <q-btn flat round dense icon="skip_next" :disable="!queue.length || buffering" @click="playNext" />
      </div>
      <div class="window-titlebar__brand">
        <img class="titlebar-orchard-mark" :src="orchardLogoUrl" alt="Orchard" />
      </div>
      <div class="window-titlebar__controls">
        <button type="button" title="Minimize" @click="minimizeWindow">
          <q-icon name="remove" />
        </button>
        <button type="button" title="Maximize" @click="toggleMaximizeWindow">
          <q-icon name="crop_square" />
        </button>
        <button type="button" title="Close" class="window-titlebar__close" @click="closeWindow">
          <q-icon name="close" />
        </button>
      </div>
    </header>

</template>
