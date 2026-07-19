<script>
export default {
  name: 'LastfmSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return { ...props.app };
  }
};
</script>

<template>
  <div class="settings-row settings-row--options">
    <div class="settings-row__copy">
      <label>Last.fm scrobbling</label>
      <p v-if="lastfmState.status === 'connected'">
        Sending now-playing updates and completed listens as {{ lastfmState.user }}.
      </p>
      <p v-else-if="lastfmState.status === 'pending'">
        Approve Orchard on Last.fm, then finish the connection.
      </p>
      <p v-else>
        Connect an account to add Orchard listening history to Last.fm.
      </p>
      <small v-if="lastfmMessage" class="settings-status-message">{{ lastfmMessage }}</small>
      <small v-if="lastfmState.status === 'connected' && !lastfmState.secureStorage" class="settings-status-message">
        Secure credential storage is unavailable, so this connection lasts until Orchard closes.
      </small>
    </div>
    <div class="settings-actions lastfm-actions">
      <button
        v-if="lastfmState.status === 'pending'"
        type="button"
        class="settings-button"
        @click="completeLastfmConnection"
      >
        Finish connection
      </button>
      <button
        v-else-if="lastfmState.status === 'connected'"
        type="button"
        class="settings-link-button settings-link-button--danger"
        @click="disconnectLastfm"
      >
        Disconnect
      </button>
      <button
        v-else
        type="button"
        class="settings-button"
        :disabled="lastfmState.status === 'loading' || lastfmState.status === 'unavailable'"
        @click="connectLastfm"
      >
        Connect Last.fm
      </button>
    </div>
  </div>
</template>

<style scoped>
.settings-status-message {
  display: block;
  margin-top: 5px;
  color: #929a94;
  font-size: 10px;
  line-height: 1.4;
}

.lastfm-actions {
  justify-content: flex-end;
  padding-top: 0;
}
</style>
