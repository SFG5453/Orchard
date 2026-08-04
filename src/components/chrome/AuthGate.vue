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
  name: 'AuthGate',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
        <main v-if="showAuthGate" class="auth-gate" aria-live="polite">
          <section class="auth-gate__panel">
            <div class="auth-gate__mark">
              <img :src="orchardLogoUrl" alt="Orchard" />
            </div>
            <div class="auth-gate__copy">
              <h2>Sign in to YouTube Music</h2>
              <p>{{ authState.status === 'starting' ? 'Opening sign in...' : authState.status === 'pending' ? 'Finish sign in to continue.' : 'Signed out.' }}</p>
            </div>
            <q-btn
              unelevated
              color="primary"
              icon="login"
              label="Sign in"
              class="auth-gate__button"
              :loading="authState.status === 'starting'"
              :disable="socketState !== 'connected'"
              @click="startLogin"
            />
            <div class="auth-gate__status">
              <q-icon :name="socketState === 'connected' ? 'link' : 'link_off'" />
              <span>{{ socketState }}</span>
            </div>
            <div v-if="authState.pending" class="auth-gate__device">
              <div class="auth-gate__code">{{ authState.pending.userCode }}</div>
              <div class="auth-gate__url">{{ authState.pending.verificationUrl }}</div>
              <div class="auth-gate__actions">
                <button type="button" @click="openVerification">Open link</button>
                <button type="button" @click="copyLoginText(authState.pending.userCode)">Copy code</button>
              </div>
            </div>
          </section>
        </main>

</template>
