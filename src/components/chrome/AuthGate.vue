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
