<script>
import { ref } from 'vue';

export default {
  name: 'BackupRestoreSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const fileInput = ref(null);

    async function importBackup(event) {
      const file = event.target.files?.[0];
      if (!file) return;

      try {
        await props.app.importOrchardBackup(file);
      } catch (error) {
        props.app.backupMessage.value = error.message || 'Could not restore that backup.';
      } finally {
        event.target.value = '';
      }
    }

    return { ...props.app, fileInput, importBackup };
  }
};
</script>

<template>
  <section id="settings-backup" class="settings-section backup-section" aria-labelledby="settings-backup-title">
    <div class="settings-section__heading">
      <h2 id="settings-backup-title">Backup</h2>
      <p>Move local Orchard settings and listening data between installs.</p>
    </div>

    <div class="settings-action-row backup-section__row">
      <div class="settings-row__copy">
        <span>Export data</span>
        <p>Pins, preferences, queue state, Replay, session history, and trusted Connect devices.</p>
      </div>
      <button type="button" class="settings-button" @click="exportOrchardBackup">
        <q-icon name="archive" />
        Export
      </button>
    </div>

    <div class="settings-action-row backup-section__row">
      <div class="settings-row__copy">
        <span>Restore data</span>
        <p>Imports an Orchard backup into this install. Restart after restoring to fully reload saved playback state.</p>
      </div>
      <button type="button" class="settings-button" @click="fileInput?.click()">
        <q-icon name="upload_file" />
        Import
      </button>
      <input ref="fileInput" class="backup-section__input" type="file" accept="application/json,.json" @change="importBackup" />
    </div>

    <p v-if="backupMessage" class="backup-section__message">{{ backupMessage }}</p>
  </section>
</template>
