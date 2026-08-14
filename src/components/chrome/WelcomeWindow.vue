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
import { computed, onMounted, ref, watch, defineAsyncComponent } from 'vue';
import welcomeMusicUrl from '../../assets/welcome-lofi.mp3';

const SupportView = defineAsyncComponent(() => import('../views/SupportView.vue'));

export default {
  name: 'WelcomeWindow',
  components: { SupportView },
  props: { app: { type: Object, required: true } },
  setup(props) {
    const audioRef = ref(null);
    const musicMuted = ref(false);
    const musicBlocked = ref(false);
    const stepIndex = ref(0);
    const supportOpen = ref(false);
    const completionError = ref('');
    const completionPending = ref(false);
    const FULL_STEPS = [
      { key: 'account', icon: 'music_note', title: 'Welcome to Orchard' },
      { key: 'layout', icon: 'view_quilt', title: 'Interface design' },
      { key: 'immersive', icon: 'wallpaper', title: 'Artwork background' },
      { key: 'sound', icon: 'graphic_eq', title: 'Tune playback' },
      { key: 'connected', icon: 'hub', title: 'Stay connected' },
      { key: 'finish', icon: 'check_circle', title: 'Ready to listen' }
    ];
    // Upgrading from 3.x is not a fresh setup: the listener is already signed
    // in and already configured, so the only thing worth their attention is the
    // layout that did not exist when they last chose one.
    const canopyUpgrade = computed(() => props.app.welcomeMode.value === 'canopy-upgrade');
    const steps = computed(() => (canopyUpgrade.value
      ? [{ key: 'layout', icon: 'view_quilt', title: 'A new look for Orchard' }]
      : FULL_STEPS));
    const currentStep = computed(() => steps.value[stepIndex.value] || steps.value[0]);
    const accountReady = computed(() => props.app.authState.value.signedIn);
    const canGoNext = computed(() => canopyUpgrade.value || stepIndex.value > 0 || accountReady.value);
    const primaryLabel = computed(() => {
      if (stepIndex.value === steps.value.length - 1) return 'Open Orchard';
      if (stepIndex.value === 0) return accountReady.value ? 'Continue' : 'Sign in';
      return 'Next';
    });

    async function playWelcomeMusic() {
      const audio = audioRef.value;
      if (!audio) return;

      audio.volume = 0.28;
      audio.muted = musicMuted.value;
      try {
        await audio.play();
        musicBlocked.value = false;
      } catch {
        musicBlocked.value = true;
      }
    }

    function toggleMusicMuted() {
      musicMuted.value = !musicMuted.value;
      if (audioRef.value) audioRef.value.muted = musicMuted.value;
      if (!musicMuted.value) void playWelcomeMusic();
    }

    async function primaryAction() {
      if (stepIndex.value === steps.value.length - 1) {
        completionError.value = '';
        completionPending.value = true;
        const completed = await props.app.completeWelcomeSetup();
        completionPending.value = false;
        if (!completed) completionError.value = props.app.errorMessage.value || 'Could not open Orchard.';
        return;
      }

      if (stepIndex.value === 0 && !accountReady.value) {
        await props.app.startLogin();
        return;
      }

      stepIndex.value = Math.min(stepIndex.value + 1, steps.value.length - 1);
    }

    function previousStep() {
      stepIndex.value = Math.max(0, stepIndex.value - 1);
    }

    function openSupport() {
      supportOpen.value = true;
      void props.app.loadSupportReports();
    }

    function finishIfAlreadyReady() {
      // The upgrade prompt is shown to listeners who have already completed
      // setup, so this shortcut would close the window before they see it.
      if (canopyUpgrade.value) return;
      if (props.app.authState.value.signedIn && props.app.setupState.value.welcomeCompleted) {
        window.orchardApp?.finishWelcome?.();
      }
    }

    onMounted(() => {
      finishIfAlreadyReady();
      void playWelcomeMusic();
    });

    watch(() => props.app.authState.value.signedIn, (signedIn) => {
      if (signedIn && !canopyUpgrade.value && stepIndex.value === 0) stepIndex.value = 1;
    });

    watch(() => [
      props.app.authState.value.signedIn,
      props.app.setupState.value.welcomeCompleted
    ], finishIfAlreadyReady);

    return {
      accountReady,
      app: props.app,
      audioRef,
      canGoNext,
      canopyUpgrade,
      completionError,
      completionPending,
      currentStep,
      musicBlocked,
      musicMuted,
      openSupport,
      playWelcomeMusic,
      previousStep,
      primaryAction,
      primaryLabel,
      stepIndex,
      steps,
      supportOpen,
      toggleMusicMuted,
      welcomeMusicUrl
    };
  }
};
</script>

<template>
  <main class="welcome-window" :class="`welcome-window--${currentStep.key}`">
    <audio ref="audioRef" :src="welcomeMusicUrl" loop autoplay preload="auto"></audio>

    <div class="welcome-window__chrome">
      <button type="button" title="Minimize" @click="app.minimizeWindow">
        <q-icon name="remove" />
      </button>
      <button type="button" title="Close" @click="app.closeWindow">
        <q-icon name="close" />
      </button>
    </div>

    <section class="welcome-window__hero">
      <div class="welcome-window__hero-bg"></div>
      <div class="welcome-window__hero-content">
        <div class="welcome-window__brand">
          <q-icon name="album" />
          <h2>Orchard</h2>
        </div>
        
        <div v-if="!canopyUpgrade" class="welcome-window__tracker" aria-label="Setup progress">
          <button
            v-for="(step, index) in steps"
            :key="step.key"
            type="button"
            class="welcome-window__tracker-step"
            :class="{
              'welcome-window__tracker-step--active': index === stepIndex,
              'welcome-window__tracker-step--done': index < stepIndex
            }"
            @click="stepIndex = accountReady || index === 0 ? index : stepIndex"
          >
            <q-icon :name="index < stepIndex ? 'check_circle' : step.icon" />
            <span>{{ step.title }}</span>
          </button>
        </div>
      </div>
    </section>

    <section class="welcome-window__panel" aria-labelledby="welcome-title">
      <button
        type="button"
        class="welcome-window__sound"
        :title="musicBlocked ? 'Start welcome music' : musicMuted ? 'Unmute welcome music' : 'Mute welcome music'"
        @click="musicBlocked ? playWelcomeMusic() : toggleMusicMuted()"
      >
        <q-icon :name="musicBlocked ? 'play_arrow' : musicMuted ? 'volume_off' : 'volume_up'" />
      </button>

      <div class="welcome-window__content-wrapper">
        <Transition name="fade-step" mode="out-in">
          <div :key="currentStep.key" class="welcome-window__content">
            <q-icon class="welcome-window__step-icon" :name="currentStep.icon" />
            <h1 id="welcome-title">{{ currentStep.title }}</h1>

            <template v-if="currentStep.key === 'account'">
              <p>Sign in first. Orchard will keep the main player out of the way until your setup is ready.</p>
              <div class="welcome-window__signin">
                <button
                  type="button"
                  class="welcome-window__primary"
                  :disabled="app.authState.status === 'starting'"
                  @click="app.startLogin"
                >
                  <q-icon :name="accountReady ? 'check_circle' : 'login'" />
                  {{ accountReady ? (app.authState.user?.name || 'Signed in') : app.authState.status === 'starting' ? 'Opening sign in' : 'Sign in to YouTube Music' }}
                </button>
              </div>
              <div v-if="app.authState.pending" class="welcome-window__device">
                <strong>{{ app.authState.pending.userCode }}</strong>
                <span>{{ app.authState.pending.verificationUrl }}</span>
                <div>
                  <button type="button" @click="app.openVerification">Open link</button>
                  <button type="button" @click="app.copyLoginText(app.authState.pending.userCode)">Copy code</button>
                </div>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'layout'">
              <p v-if="canopyUpgrade">
                Orchard 4 adds Canopy, a new interface layout. Grove is still here and still your
                current setting -- nothing else about your setup has changed.
              </p>
              <p v-else>Choose the interface layout that fits you best.</p>
              <div class="welcome-window__connect-grid">
                <button
                  v-for="option in app.layoutPresetOptions"
                  :key="option.value"
                  type="button"
                  class="welcome-window__sound-card"
                  :class="{ 'welcome-window__choice--active': app.layoutPreset === option.value }"
                  @click="app.layoutPreset = option.value"
                >
                  <q-icon :name="option.value === 'canopy' ? 'view_compact' : 'view_sidebar'" />
                  <strong>{{ option.label }}</strong>
                  <span>{{ option.description }}</span>
                </button>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'immersive'">
              <p>Choose whether Orchard should use album artwork behind the player.</p>
              <div class="welcome-window__setting-row">
                <span>Immersive backgrounds</span>
                <q-toggle v-model="app.immersiveBackgroundsEnabled" color="primary" aria-label="Immersive backgrounds" />
              </div>
              <div class="welcome-window__options">
                <button
                  v-for="option in app.immersiveBackgroundMotionOptions"
                  :key="option.value"
                  type="button"
                  class="welcome-window__option"
                  :class="{ 'welcome-window__choice--active': app.immersiveBackgroundMotion === option.value }"
                  :disabled="!app.immersiveBackgroundsEnabled"
                  @click="app.immersiveBackgroundMotion = option.value"
                >
                  {{ option.label }}
                </button>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'sound'">
              <p>Pick how Orchard should move through songs.</p>
              <div class="welcome-window__sound-grid">
                <button
                  type="button"
                  class="welcome-window__sound-card"
                  :class="{ 'welcome-window__choice--active': !app.crossfadeEnabled }"
                  @click="app.crossfadeEnabled = false"
                >
                  <q-icon name="block" />
                  <strong>Off</strong>
                  <span>Keep songs separate with no crossfade.</span>
                </button>
                <button
                  v-for="option in app.crossfadeModeOptions"
                  :key="option.value"
                  type="button"
                  class="welcome-window__sound-card"
                  :class="{ 'welcome-window__choice--active': app.crossfadeEnabled && app.crossfadeMode === option.value }"
                  @click="app.crossfadeEnabled = true; app.crossfadeMode = option.value"
                >
                  <q-icon :name="option.value === 'smart' ? 'auto_awesome' : 'waves'" />
                  <strong>{{ option.label }}</strong>
                  <span>{{ option.value === 'smart' ? 'Avoids awkward live, speech, and quiet-track blends.' : 'A simple end-of-song blend.' }}</span>
                </button>
              </div>
              <div class="welcome-window__setting-row">
                <span>Autoplay</span>
                <q-toggle v-model="app.autoplayEnabled" color="primary" aria-label="Autoplay" />
              </div>
              <div class="welcome-window__setting-row">
                <span>Automatic EQ</span>
                <q-toggle
                  :model-value="app.audioEngineConfig.autoEqEnabled"
                  color="primary"
                  aria-label="Automatic EQ"
                  @update:model-value="app.setAutoEqEnabled"
                />
              </div>
              <div class="welcome-window__slider">
                <span>Duration</span>
                <q-slider v-model="app.crossfadeSeconds" :min="1" :max="12" :step="1" :disable="!app.crossfadeEnabled" color="primary" />
                <output>{{ app.crossfadeSeconds }}s</output>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'connected'">
              <p>Choose what Orchard shares outside the desktop window.</p>
              <div class="welcome-window__connect-grid">
                <div class="welcome-window__setting-row">
                  <span>Discord Rich Presence</span>
                  <q-toggle v-model="app.discordRpcEnabled" color="primary" aria-label="Discord Rich Presence" />
                </div>
                <div class="welcome-window__connect-panel">
                  <button type="button" class="welcome-window__secondary" :disabled="app.socketState !== 'connected'" @click="app.loadOrchardConnectInfo({ refresh: true })">
                    <q-icon name="qr_code_2" />
                    Pair phone
                  </button>
                  <div class="welcome-window__qr" v-html="app.orchardConnect.qrSvg"></div>
                  <span class="welcome-window__bridge-status">
                    Bridge: {{ app.socketState === 'connected' ? 'Online' : app.socketState }}
                  </span>
                </div>
              </div>
            </template>

            <template v-else>
              <p>Setup is saved. Open Orchard and start listening.</p>
              <div class="welcome-window__summary">
                <span><q-icon name="check_circle" /> {{ app.authState.user?.name || 'Signed in' }}</span>
                <span><q-icon name="view_quilt" /> {{ app.layoutPreset === 'canopy' ? 'Canopy layout' : 'Grove layout' }}</span>
                <span><q-icon name="wallpaper" /> {{ app.immersiveBackgroundsEnabled ? `${app.immersiveBackgroundMotion} backgrounds` : 'backgrounds off' }}</span>
                <span><q-icon name="graphic_eq" /> {{ app.audioEngineConfig.autoEqEnabled ? 'automatic EQ' : app.audioEngineConfig.eqEnabled ? 'manual EQ' : 'EQ off' }}</span>
                <span><q-icon name="phonelink" /> {{ app.orchardConnect.devices.length }} paired</span>
              </div>
              <p v-if="musicBlocked" class="welcome-window__note">Your system blocked automatic welcome music. The top-right audio button will start it.</p>
            </template>
          </div>
        </Transition>
      </div>

      <footer class="welcome-window__actions">
        <button type="button" class="welcome-window__secondary" @click="openSupport">Support</button>
        <div>
          <button type="button" class="welcome-window__secondary" :disabled="stepIndex === 0" @click="previousStep">Previous</button>
          <button type="button" class="welcome-window__primary" :disabled="!canGoNext || completionPending" @click="primaryAction">
            {{ completionPending ? 'Opening Orchard…' : primaryLabel }}
          </button>
        </div>
      </footer>
      <p v-if="completionError" class="welcome-window__completion-error" role="alert">{{ completionError }}</p>
    </section>
  </main>

  <q-dialog v-model="supportOpen" maximized>
    <div class="welcome-support-dialog">
      <button type="button" class="welcome-support-dialog__close" title="Close support" @click="supportOpen = false"><q-icon name="close" /></button>
      <SupportView :app="app" />
    </div>
  </q-dialog>
</template>
