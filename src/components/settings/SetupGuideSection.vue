<script>
export default {
  name: 'SetupGuideSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section id="settings-setup" class="settings-section setup-guide" aria-labelledby="settings-setup-title">
    <div class="settings-section__heading">
      <h2 id="settings-setup-title">Setup</h2>
      <p>{{ setupProgress.label }} ready for Orchard 1.0</p>
    </div>

    <div v-if="setupPanelOpen" class="setup-guide__body">
      <div class="setup-guide__meter" aria-hidden="true">
        <span
          v-for="item in setupItems"
          :key="item.key"
          :class="{ 'setup-guide__tick--done': item.done }"
          class="setup-guide__tick"
        ></span>
      </div>

      <div class="setup-guide__items">
        <button
          v-for="item in setupItems"
          :key="item.key"
          type="button"
          class="setup-guide__item"
          :class="{ 'setup-guide__item--done': item.done }"
          @click="item.action"
        >
          <q-icon :name="item.done ? 'check_circle' : item.icon" />
          <span>
            <strong>{{ item.title }}</strong>
            <small>{{ item.detail }}</small>
          </span>
        </button>
      </div>

      <div class="settings-actions">
        <button type="button" class="settings-button" @click="finishSetup">
          <q-icon name="done_all" />
          Finish setup
        </button>
        <button type="button" class="settings-link-button" @click="collectDiagnostics">
          <q-icon name="fact_check" />
          Refresh diagnostics
        </button>
      </div>
    </div>

    <div v-else class="settings-action-row">
      <div class="settings-row__copy">
        <span>Setup complete</span>
        <p>Reopen the checklist any time before packaging or moving to another machine.</p>
      </div>
      <button type="button" class="settings-button" @click="reopenSetup">
        <q-icon name="checklist" />
        Reopen
      </button>
    </div>
  </section>
</template>
