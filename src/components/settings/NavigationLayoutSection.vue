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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
-->

<script>
export default {
  name: 'NavigationLayoutSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section id="settings-layout" class="settings-section" aria-labelledby="settings-layout-title">
    <div class="settings-section__heading">
      <h2 id="settings-layout-title">Home &amp; sidebar</h2>
      <p>Put the music you use most within easy reach. Changes appear immediately.</p>
    </div>

    <div class="layout-editor" aria-labelledby="settings-home-layout-title">
      <div class="layout-editor__heading">
        <div>
          <h3 id="settings-home-layout-title">Home shelves</h3>
          <p>Move shelves into your preferred order or hide them from Home.</p>
        </div>
        <button type="button" class="layout-editor__reset" @click="resetHomeLayout">Reset</button>
      </div>

      <ol class="layout-editor__list">
        <li
          v-for="(item, index) in homeLayoutItems"
          :key="`home-layout-${item.id}`"
          class="layout-editor__item"
          :class="{ 'layout-editor__item--hidden': !item.visible }"
        >
          <span class="layout-editor__position" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="layout-editor__copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <span class="layout-editor__moves">
            <button
              type="button"
              :disabled="index === 0"
              :aria-label="`Move ${item.label} up`"
              :title="`Move ${item.label} up`"
              @click="moveHomeSection(item.id, -1)"
            >
              <q-icon name="arrow_upward" />
            </button>
            <button
              type="button"
              :disabled="index === homeLayoutItems.length - 1"
              :aria-label="`Move ${item.label} down`"
              :title="`Move ${item.label} down`"
              @click="moveHomeSection(item.id, 1)"
            >
              <q-icon name="arrow_downward" />
            </button>
          </span>
          <q-toggle
            :model-value="item.visible"
            color="primary"
            :aria-label="`Show ${item.label} on Home`"
            @update:model-value="setHomeSectionVisible(item.id, $event)"
          />
        </li>
      </ol>
    </div>

    <div class="layout-editor" aria-labelledby="settings-sidebar-layout-title">
      <div class="layout-editor__heading">
        <div>
          <h3 id="settings-sidebar-layout-title">Sidebar</h3>
          <p>Arrange links within each section or hide links you do not use.</p>
        </div>
        <button type="button" class="layout-editor__reset" @click="resetSidebarLayout">Reset</button>
      </div>

      <div v-for="group in sidebarLayoutGroups" :key="`layout-group-${group.id}`" class="layout-editor__group">
        <h4>{{ group.label }}</h4>
        <ol class="layout-editor__list">
          <li
            v-for="(item, index) in group.items"
            :key="`sidebar-layout-${item.id}`"
            class="layout-editor__item layout-editor__item--compact"
            :class="{ 'layout-editor__item--hidden': !item.visible }"
          >
            <span class="layout-editor__position" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
            <q-icon :name="item.icon" class="layout-editor__icon" />
            <span class="layout-editor__copy"><strong>{{ item.label }}</strong></span>
            <span class="layout-editor__moves">
              <button
                type="button"
                :disabled="index === 0"
                :aria-label="`Move ${item.label} up`"
                :title="`Move ${item.label} up`"
                @click="moveSidebarItem(group.id, item.id, -1)"
              >
                <q-icon name="arrow_upward" />
              </button>
              <button
                type="button"
                :disabled="index === group.items.length - 1"
                :aria-label="`Move ${item.label} down`"
                :title="`Move ${item.label} down`"
                @click="moveSidebarItem(group.id, item.id, 1)"
              >
                <q-icon name="arrow_downward" />
              </button>
            </span>
            <q-toggle
              :model-value="item.visible"
              color="primary"
              :aria-label="`Show ${item.label} in the sidebar`"
              @update:model-value="setSidebarItemVisible(item.id, $event)"
            />
          </li>
        </ol>
      </div>
    </div>
  </section>
</template>
