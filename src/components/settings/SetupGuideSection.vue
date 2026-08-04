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
