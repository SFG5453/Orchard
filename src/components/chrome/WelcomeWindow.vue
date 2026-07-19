<script>
import { computed, onMounted, ref, watch } from 'vue';
import welcomeMusicUrl from '../../assets/welcome-lofi.mp3';
import SupportView from '../views/SupportView.vue';

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
    const steps = [
      { key: 'account', icon: 'music_note', title: 'Welcome to Orchard' },
      { key: 'immersive', icon: 'wallpaper', title: 'Artwork background' },
      { key: 'sound', icon: 'graphic_eq', title: 'Tune playback' },
      { key: 'connected', icon: 'hub', title: 'Stay connected' },
      { key: 'finish', icon: 'check_circle', title: 'Ready to listen' }
    ];
    const currentStep = computed(() => steps[stepIndex.value] || steps[0]);
    const accountReady = computed(() => props.app.authState.value.signedIn);
    const canGoNext = computed(() => stepIndex.value > 0 || accountReady.value);
    const primaryLabel = computed(() => {
      if (stepIndex.value === 0) return accountReady.value ? 'Continue' : 'Sign in';
      if (stepIndex.value === steps.length - 1) return 'Open Orchard';
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
      if (stepIndex.value === 0 && !accountReady.value) {
        await props.app.startLogin();
        return;
      }

      if (stepIndex.value === steps.length - 1) {
        props.app.completeWelcomeSetup();
        return;
      }

      stepIndex.value = Math.min(stepIndex.value + 1, steps.length - 1);
    }

    function previousStep() {
      stepIndex.value = Math.max(0, stepIndex.value - 1);
    }

    function openSupport() {
      supportOpen.value = true;
      void props.app.loadSupportReports();
    }

    function finishIfAlreadyReady() {
      if (props.app.authState.value.signedIn && props.app.setupState.value.welcomeCompleted) {
        window.orchardApp?.finishWelcome?.();
      }
    }

    onMounted(() => {
      finishIfAlreadyReady();
      void playWelcomeMusic();
    });

    watch(() => props.app.authState.value.signedIn, (signedIn) => {
      if (signedIn && stepIndex.value === 0) stepIndex.value = 1;
    });

    watch(() => [
      props.app.authState.value.signedIn,
      props.app.setupState.value.welcomeCompleted
    ], finishIfAlreadyReady);

    return {
      ...props.app,
      accountReady,
      app: props.app,
      audioRef,
      canGoNext,
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
      <button type="button" title="Minimize" @click="minimizeWindow">
        <q-icon name="remove" />
      </button>
      <button type="button" title="Close" @click="closeWindow">
        <q-icon name="close" />
      </button>
    </div>

    <section class="welcome-window__stage" aria-labelledby="welcome-title">
      <div class="welcome-window__progress" aria-label="Setup progress">
        <button
          v-for="(step, index) in steps"
          :key="step.key"
          type="button"
          :aria-label="step.title"
          :class="{
            'welcome-window__dot--active': index === stepIndex,
            'welcome-window__dot--done': index < stepIndex
          }"
          class="welcome-window__dot"
          @click="stepIndex = accountReady || index === 0 ? index : stepIndex"
        ></button>
      </div>

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
                  :disabled="socketState !== 'connected' || authState.status === 'starting'"
                  @click="startLogin"
                >
                  <q-icon :name="accountReady ? 'check_circle' : 'login'" />
                  {{ accountReady ? (authState.user?.name || 'Signed in') : authState.status === 'starting' ? 'Opening sign in' : 'Sign in to YouTube Music' }}
                </button>
                <span>{{ socketState === 'connected' ? 'Desktop bridge connected' : socketState }}</span>
              </div>
              <div v-if="authState.pending" class="welcome-window__device">
                <strong>{{ authState.pending.userCode }}</strong>
                <span>{{ authState.pending.verificationUrl }}</span>
                <div>
                  <button type="button" @click="openVerification">Open link</button>
                  <button type="button" @click="copyLoginText(authState.pending.userCode)">Copy code</button>
                </div>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'immersive'">
              <p>Choose whether Orchard should use album artwork behind the player.</p>
              <div class="welcome-window__setting-row">
                <span>Immersive backgrounds</span>
                <q-toggle v-model="immersiveBackgroundsEnabled" color="primary" aria-label="Immersive backgrounds" />
              </div>
              <div class="welcome-window__options">
                <button
                  v-for="option in immersiveBackgroundMotionOptions"
                  :key="option.value"
                  type="button"
                  class="welcome-window__option"
                  :class="{ 'welcome-window__choice--active': immersiveBackgroundMotion === option.value }"
                  :disabled="!immersiveBackgroundsEnabled"
                  @click="immersiveBackgroundMotion = option.value"
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
                  :class="{ 'welcome-window__choice--active': !crossfadeEnabled }"
                  @click="crossfadeEnabled = false"
                >
                  <q-icon name="block" />
                  <strong>Off</strong>
                  <span>Keep songs separate with no crossfade.</span>
                </button>
                <button
                  v-for="option in crossfadeModeOptions"
                  :key="option.value"
                  type="button"
                  class="welcome-window__sound-card"
                  :class="{ 'welcome-window__choice--active': crossfadeEnabled && crossfadeMode === option.value }"
                  @click="crossfadeEnabled = true; crossfadeMode = option.value"
                >
                  <q-icon :name="option.value === 'smart' ? 'auto_awesome' : 'waves'" />
                  <strong>{{ option.label }}</strong>
                  <span>{{ option.value === 'smart' ? 'Avoids awkward live, speech, and quiet-track blends.' : 'A simple end-of-song blend.' }}</span>
                </button>
              </div>
              <div class="welcome-window__setting-row">
                <span>Autoplay</span>
                <q-toggle v-model="autoplayEnabled" color="primary" aria-label="Autoplay" />
              </div>
              <div class="welcome-window__setting-row">
                <span>Automatic EQ</span>
                <q-toggle
                  :model-value="audioEngineConfig.autoEqEnabled"
                  color="primary"
                  aria-label="Automatic EQ"
                  @update:model-value="setAutoEqEnabled"
                />
              </div>
              <div class="welcome-window__slider">
                <span>Duration</span>
                <q-slider v-model="crossfadeSeconds" :min="1" :max="12" :step="1" :disable="!crossfadeEnabled" color="primary" />
                <output>{{ crossfadeSeconds }}s</output>
              </div>
            </template>

            <template v-else-if="currentStep.key === 'connected'">
              <p>Choose what Orchard shares outside the desktop window.</p>
              <div class="welcome-window__connect-grid">
                <div class="welcome-window__setting-row">
                  <span>Discord Rich Presence</span>
                  <q-toggle v-model="discordRpcEnabled" color="primary" aria-label="Discord Rich Presence" />
                </div>
                <div class="welcome-window__connect-panel">
                  <button type="button" class="welcome-window__secondary" :disabled="!socket?.connected" @click="loadOrchardConnectInfo({ refresh: true })">
                    <q-icon name="qr_code_2" />
                    Pair phone
                  </button>
                  <div class="welcome-window__qr" v-html="orchardConnect.qrSvg"></div>
                </div>
              </div>
            </template>

            <template v-else>
              <p>Setup is saved. Open Orchard and start listening.</p>
              <div class="welcome-window__summary">
                <span><q-icon name="check_circle" /> {{ authState.user?.name || 'Signed in' }}</span>
                <span><q-icon name="wallpaper" /> {{ immersiveBackgroundsEnabled ? `${immersiveBackgroundMotion} backgrounds` : 'backgrounds off' }}</span>
                <span><q-icon name="graphic_eq" /> {{ audioEngineConfig.autoEqEnabled ? 'automatic EQ' : audioEngineConfig.eqEnabled ? 'manual EQ' : 'EQ off' }}</span>
                <span><q-icon name="phonelink" /> {{ orchardConnect.devices.length }} paired</span>
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
          <button type="button" class="welcome-window__primary" :disabled="!canGoNext" @click="primaryAction">{{ primaryLabel }}</button>
        </div>
      </footer>
    </section>
  </main>

  <q-dialog v-model="supportOpen" maximized>
    <div class="welcome-support-dialog">
      <button type="button" class="welcome-support-dialog__close" title="Close support" @click="supportOpen = false"><q-icon name="close" /></button>
      <SupportView :app="app" />
    </div>
  </q-dialog>
</template>
