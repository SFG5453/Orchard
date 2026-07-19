<script>
export default {
  name: 'DiagnosticsSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section id="settings-diagnostics" class="settings-section diagnostics-section" aria-labelledby="settings-diagnostics-title">
    <div class="settings-section__heading">
      <h2 id="settings-diagnostics-title">Diagnostics</h2>
      <p>{{ diagnostics.generatedAt ? `Last refreshed ${new Date(diagnostics.generatedAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : 'Check Orchard before release or support.' }}</p>
    </div>

    <div class="diagnostics-section__actions">
      <button type="button" class="settings-button" @click="collectDiagnostics">
        <q-icon name="refresh" />
        Refresh
      </button>
      <button type="button" class="settings-button" :disabled="!diagnostics.report" @click="copyDiagnostics">
        <q-icon name="content_copy" />
        Copy report
      </button>
      <span v-if="diagnosticsMessage">{{ diagnosticsMessage }}</span>
    </div>

    <div class="diagnostics-list">
      <div v-for="item in diagnostics.items" :key="item.label" class="diagnostics-list__item">
        <div class="settings-row__copy">
          <span>{{ item.label }}</span>
          <p>{{ item.detail }}</p>
        </div>
        <span class="diagnostics-list__status" :class="`diagnostics-list__status--${item.status}`">
          {{ diagnosticTone(item.status) }}
        </span>
      </div>
      <div v-if="!diagnostics.items.length" class="settings-connect-empty">
        No diagnostics collected yet.
      </div>
    </div>
  </section>
</template>
