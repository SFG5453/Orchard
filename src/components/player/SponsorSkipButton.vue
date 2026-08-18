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
  name: 'SponsorSkipButton',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <Transition name="sponsor-skip">
    <button
      v-if="sponsorSkipButtonVisible"
      type="button"
      class="sponsor-skip-button"
      :style="playerBarStyle"
      :aria-label="sponsorSkipLabelText"
      @click="skipSponsorSegment"
    >
      <q-icon name="skip_next" />
      <span>{{ sponsorSkipLabelText }}</span>
    </button>
  </Transition>
</template>
