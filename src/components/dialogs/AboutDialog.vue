<script>
import sfg545AvatarUrl from '../../assets/sfg545.gif';
import reallyUnusualAvatarUrl from '../../assets/ReallyUnusual.png';

export default {
  name: 'AboutDialog',
  props: { app: { type: Object, required: true } },
  data() {
    return {
      sfg545AvatarUrl,
      reallyUnusualAvatarUrl
    };
  },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <q-dialog v-model="aboutDialogOpen" aria-label="About Orchard">
    <q-card class="about-dialog-card">
      <q-btn
        v-close-popup
        flat
        round
        dense
        icon="close"
        class="about-dialog__close"
        aria-label="Close About dialog"
      />

      <div class="about-dialog__content">
        <img class="about-dialog__logo" :src="orchardLogoUrl" alt="Orchard" />
        <div class="about-dialog__name">Orchard</div>
        <div class="about-dialog__version">Version {{ currentReleaseLabel }}</div>
        <div class="about-dialog__license">
          <span>License</span>
          <strong>MIT</strong>
          <p>Copyright 2025–2026 SFG545.</p>
        </div>
        <div class="about-dialog__contributors-label">Contributors</div>
        <div class="about-dialog__contributors">
          <div
            class="about-dialog__contributor"
            tabindex="0"
            aria-label="SFG545 - Founder"
          >
            <img :src="sfg545AvatarUrl" alt="" />
            <span>SFG545 - Founder</span>
          </div>
          <div
            class="about-dialog__contributor"
            tabindex="0"
            aria-label="ReallyUnusual - Primary (and only) Tester"
          >
            <img :src="reallyUnusualAvatarUrl" alt="" />
            <span>ReallyUnusual - Primary (and only) Tester</span>
          </div>
        </div>
        <q-btn
          flat
          dense
          icon="new_releases"
          label="Release notes"
          class="about-dialog__release-notes"
          @click="aboutDialogOpen = false; openChangelog()"
        />
        <a
          class="about-dialog__website"
          href="https://sfg545.dev/orchard"
          target="_blank"
          rel="noopener noreferrer"
        >sfg545.dev/orchard</a>
      </div>
    </q-card>
  </q-dialog>
</template>
