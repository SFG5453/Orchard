/*
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
 */

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createDesktopTransport } from '../../platform/desktop/transport.js';
import {
  IMMERSIVE_BACKGROUND_MOTION_OPTIONS,
  LAYOUT_PRESET_OPTIONS,
  normalizeImmersiveBackgroundMotion,
  normalizeLayoutPreset
} from '../appearance/appearancePreferences.js';
import { copyTextToClipboard } from '../platform/clipboardText.js';
import { installSupportActions } from '../platform/supportActions.js';

const USER_PREFERENCES_STORAGE_KEY = 'orchard:user-preferences';
const AUDIO_ENGINE_STORAGE_KEY = 'orchard:audio-engine';
const SETUP_STORAGE_KEY = 'orchard:setup-state';
const WELCOME_MODE_STORAGE_KEY = 'orchard:welcome-mode';
const DEFAULT_AUDIO_ENGINE_CONFIG = {
  enabled: true,
  autoEqEnabled: false,
  eqEnabled: false,
  gains: Array(10).fill(0),
  preampDb: 0,
  outputGainDb: 0,
  q: 1.1,
  balance: 0,
  outputDeviceId: 'default'
};

function readStoredJson(key, fallback) {
  try {
    return JSON.parse(window.localStorage.getItem(key) || JSON.stringify(fallback));
  } catch {
    return fallback;
  }
}

function writeStoredJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Setup remains usable when storage is unavailable; completion will report
    // a bridge error if the settings cannot be handed to the main window.
  }
}

function clampCrossfadeSeconds(value) {
  const number = Math.round(Number(value));
  return Number.isFinite(number) ? Math.max(1, Math.min(12, number)) : 6;
}

function normalizeCrossfadeMode(value) {
  return value === 'smart' ? 'smart' : 'standard';
}

function readAudioEngineConfig() {
  const stored = readStoredJson(AUDIO_ENGINE_STORAGE_KEY, {});
  const config = stored?.config && typeof stored.config === 'object' ? stored.config : {};
  return {
    ...DEFAULT_AUDIO_ENGINE_CONFIG,
    ...config,
    autoEqEnabled: Boolean(config.autoEqEnabled),
    eqEnabled: config.autoEqEnabled ? false : Boolean(config.eqEnabled),
    gains: Array.from({ length: 10 }, (_, index) => Number(config.gains?.[index]) || 0)
  };
}

/**
 * Creates only the state required by the standalone welcome renderer. Keeping
 * this independent from createOrchardApp prevents playback and browse modules
 * from becoming eager dependencies of the welcome window.
 */
export function createWelcomeApp() {
  const ctx = {};
  const storedPreferences = readStoredJson(USER_PREFERENCES_STORAGE_KEY, {});

  ctx.appVersion = __APP_VERSION__;
  ctx.errorMessage = ref('');
  ctx.authState = ref({ signedIn: false, status: 'signed_out', pending: null, error: '', user: null });
  ctx.socket = ref(null);
  ctx.socketState = ref('connecting');
  ctx.orchardConnect = ref({
    status: 'idle',
    serverUrl: '',
    pairUrl: '',
    qrSvg: '',
    expiresAt: 0,
    pending: [],
    devices: []
  });
  ctx.setupState = ref({
    completed: false,
    welcomeCompleted: false,
    ...readStoredJson(SETUP_STORAGE_KEY, {})
  });
  ctx.welcomeMode = ref(window.localStorage.getItem(WELCOME_MODE_STORAGE_KEY) || '');

  ctx.layoutPresetOptions = LAYOUT_PRESET_OPTIONS;
  ctx.immersiveBackgroundMotionOptions = IMMERSIVE_BACKGROUND_MOTION_OPTIONS;
  ctx.crossfadeModeOptions = [
    { label: 'Standard', value: 'standard' },
    { label: 'Smart', value: 'smart' }
  ];
  ctx.layoutPreset = ref(normalizeLayoutPreset(storedPreferences.layoutPreset));
  ctx.immersiveBackgroundsEnabled = ref(storedPreferences.immersiveBackgroundsEnabled !== false);
  ctx.immersiveBackgroundMotion = ref(normalizeImmersiveBackgroundMotion(storedPreferences.immersiveBackgroundMotion));
  ctx.autoplayEnabled = ref(storedPreferences.autoplayEnabled !== false);
  ctx.crossfadeEnabled = ref(storedPreferences.crossfadeEnabled !== false);
  ctx.crossfadeMode = ref(normalizeCrossfadeMode(storedPreferences.crossfadeMode));
  ctx.crossfadeSeconds = ref(clampCrossfadeSeconds(storedPreferences.crossfadeSeconds));
  ctx.discordRpcEnabled = ref(storedPreferences.discordRpcEnabled !== false);
  ctx.audioEngineConfig = ref(readAudioEngineConfig());
  ctx.diagnostics = ref({ generatedAt: 0, items: [], report: null });

  ctx.persistPreferences = function persistPreferences() {
    writeStoredJson(USER_PREFERENCES_STORAGE_KEY, {
      ...readStoredJson(USER_PREFERENCES_STORAGE_KEY, {}),
      layoutPreset: ctx.layoutPreset.value,
      immersiveBackgroundsEnabled: ctx.immersiveBackgroundsEnabled.value,
      immersiveBackgroundMotion: ctx.immersiveBackgroundMotion.value,
      autoplayEnabled: ctx.autoplayEnabled.value,
      crossfadeEnabled: ctx.crossfadeEnabled.value,
      crossfadeMode: ctx.crossfadeMode.value,
      crossfadeSeconds: clampCrossfadeSeconds(ctx.crossfadeSeconds.value),
      discordRpcEnabled: ctx.discordRpcEnabled.value
    });
  };

  ctx.persistAudioEngine = function persistAudioEngine() {
    writeStoredJson(AUDIO_ENGINE_STORAGE_KEY, {
      ...readStoredJson(AUDIO_ENGINE_STORAGE_KEY, {}),
      config: ctx.audioEngineConfig.value
    });
  };

  ctx.setAutoEqEnabled = function setAutoEqEnabled(enabled) {
    ctx.audioEngineConfig.value = {
      ...ctx.audioEngineConfig.value,
      enabled: enabled ? true : ctx.audioEngineConfig.value.enabled,
      autoEqEnabled: Boolean(enabled),
      eqEnabled: enabled ? false : ctx.audioEngineConfig.value.eqEnabled
    };
  };

  ctx.minimizeWindow = () => window.orchardWindow?.minimize();
  ctx.closeWindow = () => window.orchardWindow?.close();

  ctx.emitWithReply = function emitWithReply(event, payload = {}) {
    return new Promise((resolve, reject) => {
      if (!ctx.socket.value?.connected) {
        reject(new Error('The Orchard bridge is offline.'));
        return;
      }
      ctx.socket.value.emit(event, payload, (response) => {
        if (response?.ok) resolve(response.data);
        else reject(new Error(response?.error || 'Request failed'));
      });
    });
  };

  ctx.syncAuthState = function syncAuthState(state = {}) {
    ctx.authState.value = { ...ctx.authState.value, ...state };
    if (ctx.authState.value.error) ctx.errorMessage.value = ctx.authState.value.error;
  };

  ctx.startLogin = async function startLogin() {
    if (!ctx.socket.value?.connected) return;
    ctx.errorMessage.value = '';
    try {
      ctx.syncAuthState(await ctx.emitWithReply('auth:login'));
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.openVerification = function openVerification() {
    const url = ctx.authState.value.pending?.verificationUrl;
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
  };

  ctx.copyLoginText = async function copyLoginText(value) {
    if (!value) return;
    try {
      await copyTextToClipboard(value);
    } catch {
      ctx.errorMessage.value = `Copy failed. Use ${value}`;
    }
  };

  ctx.loadOrchardConnectInfo = async function loadOrchardConnectInfo({ refresh = false } = {}) {
    const event = refresh ? 'connect:pairing-refresh' : 'connect:pairing-info';
    const state = await ctx.emitWithReply(event);
    ctx.orchardConnect.value = { ...ctx.orchardConnect.value, ...state };
    return state;
  };

  ctx.updateSetupState = function updateSetupState(patch = {}) {
    ctx.setupState.value = { ...ctx.setupState.value, ...patch };
    writeStoredJson(SETUP_STORAGE_KEY, ctx.setupState.value);
  };

  ctx.completeWelcomeSetup = async function completeWelcomeSetup() {
    if (!ctx.authState.value.signedIn) {
      ctx.errorMessage.value = 'Sign in before opening Orchard.';
      return false;
    }
    ctx.persistPreferences();
    ctx.persistAudioEngine();
    ctx.updateSetupState({ completed: true, welcomeCompleted: true });
    window.localStorage.removeItem(WELCOME_MODE_STORAGE_KEY);
    try {
      if (typeof window.orchardApp?.finishWelcome !== 'function') {
        throw new Error('The desktop bridge is unavailable.');
      }
      await window.orchardApp.finishWelcome({
        userPreferences: window.localStorage.getItem(USER_PREFERENCES_STORAGE_KEY),
        audioEngine: window.localStorage.getItem(AUDIO_ENGINE_STORAGE_KEY)
      });
      return true;
    } catch (error) {
      ctx.errorMessage.value = error?.message || 'Could not open Orchard.';
      return false;
    }
  };

  installSupportActions(ctx);
  ctx.collectDiagnostics = async function collectDiagnostics() {
    const appInfo = await window.orchardApp?.diagnostics?.().catch?.(() => ({})) || {};
    const report = {
      generatedAt: new Date().toISOString(),
      app: {
        version: ctx.appVersion,
        platform: appInfo.platform || navigator.platform,
        chrome: appInfo.chrome || '',
        electron: appInfo.electron || '',
        node: appInfo.node || '',
        dev: Boolean(appInfo.dev)
      },
      state: {
        window: 'welcome',
        socket: ctx.socketState.value,
        auth: ctx.authState.value.status,
        connectDevices: ctx.orchardConnect.value.devices.length
      }
    };
    ctx.diagnostics.value = { generatedAt: Date.now(), items: [], report };
  };

  watch([
    ctx.layoutPreset,
    ctx.immersiveBackgroundsEnabled,
    ctx.immersiveBackgroundMotion,
    ctx.autoplayEnabled,
    ctx.crossfadeEnabled,
    ctx.crossfadeMode,
    ctx.crossfadeSeconds,
    ctx.discordRpcEnabled
  ], ctx.persistPreferences, { immediate: true });
  watch(ctx.audioEngineConfig, ctx.persistAudioEngine, { deep: true, immediate: true });

  onMounted(async () => {
    const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '0';
    ctx.socket.value = await createDesktopTransport(socketPort);
    ctx.socket.value.on('connect', async () => {
      ctx.socketState.value = 'connected';
      try {
        ctx.syncAuthState(await ctx.emitWithReply('auth:status'));
        await ctx.loadOrchardConnectInfo();
      } catch (error) {
        ctx.errorMessage.value = error.message;
      }
    });
    ctx.socket.value.on('auth:state', ctx.syncAuthState);
    ctx.socket.value.on('connect:pairing-state', (state = {}) => {
      ctx.orchardConnect.value = { ...ctx.orchardConnect.value, ...state };
    });
    ctx.socket.value.on('disconnect', () => { ctx.socketState.value = 'offline'; });
    ctx.socket.value.on('connect_error', (error) => {
      ctx.socketState.value = 'offline';
      ctx.errorMessage.value = error.message;
    });
  });

  onBeforeUnmount(() => {
    ctx.stopSupportPolling();
    ctx.socket.value?.disconnect();
  });

  return ctx;
}
