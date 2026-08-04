<!--
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
-->

<script>
import sfg545AvatarUrl from '../../assets/sfg545.gif';
import reallyUnusualAvatarUrl from '../../assets/ReallyUnusual.png';
import julianRamierezAvatarUrl from '../../assets/julian-ramierez.jpg';

export default {
  name: 'AboutDialog',
  props: { app: { type: Object, required: true } },
  data() {
    return {
      sfg545AvatarUrl,
      reallyUnusualAvatarUrl,
      julianRamierezAvatarUrl
    };
  },
  methods: {
    async viewLicense() {
      try {
        await window.orchardApp?.viewLicense?.();
      } catch (error) {
        console.warn('Could not open the Orchard license.', error);
      }
    }
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
          <strong>AGPL-3.0-or-later</strong>
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
            aria-label="ReallyUnusual - Primary Tester"
          >
            <img :src="reallyUnusualAvatarUrl" alt="" />
            <span>ReallyUnusual - Primary Tester</span>
          </div>
          <div
            class="about-dialog__contributor"
            tabindex="0"
            aria-label="Julian Ramierez - Tester and Mobile App Developer"
          >
            <img :src="julianRamierezAvatarUrl" alt="" />
            <span>Julian Ramierez - Tester and Mobile App Developer</span>
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
        <q-btn
          flat
          dense
          icon="description"
          label="View license"
          class="about-dialog__license-button"
          @click="viewLicense"
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
