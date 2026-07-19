<script>
import { ref, watch } from 'vue';

export default {
  name: 'ListeningPartyDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;

    const userName = ref(localStorage.getItem('orchard:listening-party-name') || app.authState?.value?.user?.name || 'Listener');
    const roomId = ref(app.initialListeningPartyRoomId || '');
    const localError = ref('');
    const actionPending = ref(false);

    watch(() => app.authState?.value?.user?.name, (newName) => {
      if (newName && (!userName.value || userName.value === 'Listener')) {
        userName.value = newName;
      }
    });

    const saveName = () => {
      const name = userName.value.trim() || 'Listener';
      localStorage.setItem('orchard:listening-party-name', name);
      return name;
    };

    const handleCreateParty = async () => {
      localError.value = '';
      actionPending.value = true;
      try {
        const name = saveName();
        await app.createListeningParty({ name });
      } catch (err) {
        localError.value = err.message || 'Failed to create listening party';
      } finally {
        actionPending.value = false;
      }
    };

    const handleJoinParty = async () => {
      const cleanRoom = roomId.value.trim().toUpperCase().replace(/[^A-Z2-9]/g, '');
      if (!cleanRoom) {
        localError.value = 'Please enter a valid room code';
        return;
      }
      localError.value = '';
      actionPending.value = true;
      try {
        const name = saveName();
        await app.joinListeningParty(cleanRoom, { name });
      } catch (err) {
        localError.value = err.message || 'Failed to join listening party';
      } finally {
        actionPending.value = false;
      }
    };

    const handleLeaveParty = () => {
      app.leaveListeningParty();
      const url = new URL(window.location.href);
      url.searchParams.delete('party');
      url.searchParams.delete('room');
      window.history.replaceState({}, '', url.pathname + url.search);
      if (app.initialListeningPartyRoomId) {
        app.initialListeningPartyRoomId = '';
      }
    };

    const copyInviteUrl = async () => {
      if (!app.listeningPartyInviteUrl.value) return;
      localError.value = '';
      try {
        await app.copyListeningPartyInviteUrl();
      } catch (err) {
        localError.value = err.message || 'Failed to copy link';
      }
    };

    return {
      ...app,
      app,
      userName,
      roomId,
      localError,
      actionPending,
      handleCreateParty,
      handleJoinParty,
      handleLeaveParty,
      copyInviteUrl
    };
  }
};
</script>

<template>
  <q-dialog v-model="listeningPartyDialogOpen" persistent aria-label="Listening Party">
    <q-card class="listening-party-dialog">
      <header class="listening-party-dialog__header">
        <div class="listening-party-dialog__title-wrap">
          <q-icon :name="listeningParty.status === 'connected' ? 'groups' : 'group_add'" class="listening-party-dialog__title-icon" />
          <span class="listening-party-dialog__title">Listening Party</span>
        </div>
        <q-btn
          v-close-popup
          flat
          round
          dense
          icon="close"
          aria-label="Close Listening Party Dialog"
          :disable="actionPending"
        />
      </header>

      <div v-if="listeningParty.status === 'connected'" class="listening-party-dialog__active">
        <div class="listening-party-dialog__room-info">
          <div class="listening-party-dialog__section-label">ROOM CODE</div>
          <div class="listening-party-dialog__room-code">{{ listeningParty.room?.id }}</div>
          <div class="listening-party-dialog__room-byline">
            Role: <strong>{{ listeningPartyIsHost ? 'Host (Broadcasting)' : 'Guest (Synced)' }}</strong>
          </div>
        </div>

        <div class="listening-party-dialog__actions-row">
          <button type="button" class="listening-party-dialog__btn listening-party-dialog__btn--primary" @click="copyInviteUrl">
            <q-icon :name="listeningPartyInviteCopied ? 'check' : 'content_copy'" />
            <span>{{ listeningPartyInviteCopied ? 'Copied!' : 'Copy Invite Link' }}</span>
          </button>
        </div>

        <div class="listening-party-dialog__peers-info">
          <div class="listening-party-dialog__section-label">
            PEERS CONNECTED ({{ listeningParty.peers?.length || 0 }})
          </div>
          <div v-if="listeningParty.peers?.length > 0" class="listening-party-dialog__peer-list">
            <div
              v-for="peer in listeningParty.peers"
              :key="peer.id"
              class="listening-party-dialog__peer-item"
            >
              <q-icon name="fiber_manual_record" :color="peer.open ? 'green' : 'orange'" class="q-mr-xs" />
              <span>{{ peer.name || `Listener ${peer.id.slice(0, 4)}` }}</span>
              <span v-if="peer.role === 'host'" class="listening-party-dialog__peer-role">Host</span>
              <span class="listening-party-dialog__peer-status">({{ peer.state }})</span>
            </div>
          </div>
          <div v-else class="listening-party-dialog__empty-peers">
            Waiting for friends to join...
          </div>
        </div>

        <div class="listening-party-dialog__footer">
          <button type="button" class="listening-party-dialog__btn listening-party-dialog__btn--danger" @click="handleLeaveParty">
            <q-icon :name="listeningPartyIsHost ? 'power_settings_new' : 'logout'" />
            <span>{{ listeningPartyIsHost ? 'End Party' : 'Leave Party' }}</span>
          </button>
        </div>
      </div>

      <div v-else-if="listeningParty.status === 'connecting' || actionPending" class="listening-party-dialog__status-screen">
        <q-spinner color="primary" size="48px" />
        <div class="listening-party-dialog__status-text">Connecting to room...</div>
        <button type="button" class="listening-party-dialog__btn listening-party-dialog__btn--secondary q-mt-md" @click="handleLeaveParty">
          Cancel
        </button>
      </div>

      <div v-else class="listening-party-dialog__setup">
        <div v-if="listeningParty.error || localError" class="listening-party-dialog__error" role="alert">
          <q-icon name="warning" />
          <span>{{ listeningParty.error || localError }}</span>
        </div>

        <div class="listening-party-dialog__form-group">
          <label for="lp-username">YOUR NAME</label>
          <input
            id="lp-username"
            v-model="userName"
            type="text"
            maxlength="40"
            placeholder="Listener Name"
            autocomplete="off"
          />
        </div>

        <div class="listening-party-dialog__split">
          <div class="listening-party-dialog__split-col">
            <div class="listening-party-dialog__col-title">Start a Party</div>
            <button type="button" class="listening-party-dialog__btn listening-party-dialog__btn--primary" @click="handleCreateParty">
              <q-icon name="wifi_tethering" />
              <span>Start Party</span>
            </button>
          </div>

          <div class="listening-party-dialog__divider" />

          <div class="listening-party-dialog__split-col">
            <div class="listening-party-dialog__col-title">Join a Party</div>
            <div class="listening-party-dialog__join-row">
              <input
                v-model="roomId"
                type="text"
                maxlength="8"
                placeholder="ROOM CODE"
                class="listening-party-dialog__room-input"
                autocomplete="off"
              />
              <button type="button" class="listening-party-dialog__btn listening-party-dialog__btn--primary" :disabled="!roomId.trim()" @click="handleJoinParty">
                Join
              </button>
            </div>
          </div>
        </div>
      </div>
    </q-card>
  </q-dialog>
</template>
