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
export default {
  name: 'DownloadIndicator',
  props: {
    downloaded: { type: Boolean, default: false },
    downloading: { type: Boolean, default: false },
    size: { type: String, default: '14px' }
  }
};
</script>

<template>
  <span
    v-if="downloading"
    class="download-indicator download-indicator--downloading"
    :style="{ fontSize: size }"
    aria-label="Downloading offline…"
    title="Downloading offline…"
  >
    <q-icon name="downloading" />
  </span>
  <span
    v-else-if="downloaded"
    class="download-indicator download-indicator--downloaded"
    :style="{ fontSize: size }"
    aria-label="Downloaded offline"
    title="Downloaded offline"
  >
    <q-icon name="download_done" />
  </span>
</template>
