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
import { computed, nextTick, ref, watch } from 'vue';

export default {
  name: 'SmartCrossfadeMixOverlay',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const mix = computed(() => props.app.smartCrossfadeMix.value);
    const isFullscreen = computed(() => props.app.fullscreenPlayerOpen.value);
    const layoutPreset = computed(() => props.app.layoutPreset.value);

    const fullscreenMixStyle = computed(() => ({
      ...props.app.playerBarStyle.value,
      '--smart-mix-duration': `${mix.value.durationMs || 3200}ms`
    }));

    const barMixStyle = computed(() => ({
      ...props.app.playerBarStyle.value,
      '--smart-mix-duration': `${mix.value.fadeDurationMs || mix.value.durationMs || 3200}ms`
    }));

    const transitionDetail = computed(() => {
      if (mix.value.transitionBeats) return `${mix.value.transitionBeats}-beat handoff`;
      if (mix.value.tempoShift) {
        const sign = mix.value.tempoShift > 0 ? '+' : '';
        return `Tempo ${sign}${mix.value.tempoShift}%`;
      }
      return 'Phrase aligned';
    });

    // The canopy titlebar is loaded asynchronously, so `.canopy-readout` may not
    // exist the first time this component renders. A Teleport resolves its
    // target once, when `to` changes -- a selector that misses stays missed, and
    // the banner is silently dropped for the rest of the session. So resolve to
    // the element ourselves and re-resolve whenever a mix starts, which also
    // catches the case where the titlebar remounted and left us holding a
    // detached node.
    const barTarget = ref('body');

    function resolveBarTarget() {
      if (layoutPreset.value !== 'canopy') {
        barTarget.value = 'body';
        return true;
      }
      const readout = document.querySelector('.canopy-readout');
      barTarget.value = readout || 'body';
      return Boolean(readout);
    }

    watch(
      [layoutPreset, () => mix.value.visible],
      async () => {
        if (resolveBarTarget()) return;
        // The titlebar's chunk may still be in flight; body is a usable
        // fallback in the meantime, and one more pass picks it up when it lands.
        await nextTick();
        resolveBarTarget();
      },
      { immediate: true }
    );

    function dismiss() {
      props.app.dismissSmartCrossfadeMix?.();
    }

    return { mix, isFullscreen, layoutPreset, barTarget, fullscreenMixStyle, barMixStyle, transitionDetail, dismiss };
  }
};
</script>

<template>
  <!-- Full-screen overlay: teleported inside .fullscreen-player so it
       remains visible when the browser enters the top layer via
       requestFullscreen(). Only mounted when fullscreen is active. -->
  <Teleport v-if="isFullscreen" to=".fullscreen-player">
    <Transition name="smart-crossfade-mix" appear>
      <section
        v-if="mix.visible"
        :key="`fs-${mix.id}`"
        class="smart-crossfade-mix"
        :style="fullscreenMixStyle"
        role="status"
        aria-live="polite"
        :aria-label="`Smart Crossfade mixing ${mix.from.title} into ${mix.to.title}`"
      >
        <header class="smart-crossfade-mix__header">
          <div class="smart-crossfade-mix__status">
            <q-icon name="graphic_eq" />
            <span>Mixing</span>
          </div>
          <span class="smart-crossfade-mix__style">{{ mix.styleLabel }}</span>
        </header>

        <div class="smart-crossfade-mix__stage">
          <article class="smart-crossfade-mix__track smart-crossfade-mix__track--from">
            <span class="smart-crossfade-mix__track-role">Current</span>
            <div class="smart-crossfade-mix__artwork">
              <img v-if="mix.from.artwork" :src="mix.from.artwork" alt="" />
              <span v-else class="smart-crossfade-mix__artwork-empty">
                <q-icon name="music_note" />
              </span>
            </div>
            <div class="smart-crossfade-mix__track-copy">
              <strong>{{ mix.from.title }}</strong>
              <span>{{ mix.from.artist }}</span>
            </div>
            <div v-if="mix.fromBpm || mix.fromKey" class="smart-crossfade-mix__metadata">
              <span v-if="mix.fromBpm">{{ mix.fromBpm }} BPM</span>
              <span v-if="mix.fromKey">{{ mix.fromKey }}</span>
            </div>
          </article>

          <div class="smart-crossfade-mix__handoff" aria-hidden="true">
            <q-icon name="arrow_forward" />
            <div class="smart-crossfade-mix__fader">
              <span class="smart-crossfade-mix__fader-fill" />
              <i />
            </div>
            <span>{{ transitionDetail }}</span>
          </div>

          <article class="smart-crossfade-mix__track smart-crossfade-mix__track--to">
            <span class="smart-crossfade-mix__track-role">Next</span>
            <div class="smart-crossfade-mix__artwork">
              <img v-if="mix.to.artwork" :src="mix.to.artwork" alt="" />
              <span v-else class="smart-crossfade-mix__artwork-empty">
                <q-icon name="music_note" />
              </span>
            </div>
            <div class="smart-crossfade-mix__track-copy">
              <strong>{{ mix.to.title }}</strong>
              <span>{{ mix.to.artist }}</span>
            </div>
            <div v-if="mix.toBpm || mix.toKey" class="smart-crossfade-mix__metadata">
              <span v-if="mix.toBpm">{{ mix.toBpm }} BPM</span>
              <span v-if="mix.toKey">{{ mix.toKey }}</span>
            </div>
          </article>
        </div>

        <footer class="smart-crossfade-mix__footer">
          <div class="smart-crossfade-mix__progress"><i /></div>
          <span>{{ mix.from.title }}</span>
          <q-icon name="arrow_forward" />
          <strong>{{ mix.to.title }}</strong>
        </footer>
      </section>
    </Transition>
  </Teleport>

  <!-- Compact bar: teleported to canopy readout if preset is canopy, else body -->
  <Teleport :to="barTarget">
    <Transition name="smart-crossfade-bar" appear>
      <div
        v-if="mix.visible && !isFullscreen"
        :key="`bar-${mix.id}`"
        class="smart-crossfade-mix--bar"
        :style="barMixStyle"
        role="status"
        aria-live="polite"
        :aria-label="`Smart Crossfade: ${mix.from.title} into ${mix.to.title}`"
        @click="dismiss"
      >
        <div class="smart-crossfade-bar__content">
          <q-icon name="graphic_eq" class="smart-crossfade-bar__icon" />
          <span class="smart-crossfade-bar__label">Mixing</span>

          <div class="smart-crossfade-bar__track">
            <img v-if="mix.from.artwork" :src="mix.from.artwork" class="smart-crossfade-bar__thumb" alt="" />
            <span v-else class="smart-crossfade-bar__thumb smart-crossfade-bar__thumb--empty">
              <q-icon name="music_note" />
            </span>
            <span class="smart-crossfade-bar__name">{{ mix.from.title }}</span>
          </div>

          <q-icon name="arrow_forward" class="smart-crossfade-bar__arrow" />

          <div class="smart-crossfade-bar__track">
            <img v-if="mix.to.artwork" :src="mix.to.artwork" class="smart-crossfade-bar__thumb" alt="" />
            <span v-else class="smart-crossfade-bar__thumb smart-crossfade-bar__thumb--empty">
              <q-icon name="music_note" />
            </span>
            <span class="smart-crossfade-bar__name smart-crossfade-bar__name--to">{{ mix.to.title }}</span>
          </div>

          <span class="smart-crossfade-bar__style">{{ mix.styleLabel }}</span>
        </div>

        <div class="smart-crossfade-bar__progress"><i /></div>
      </div>
    </Transition>
  </Teleport>
</template>
