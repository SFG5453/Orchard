<script>
export default {
  name: 'ChangelogDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    function changelogSectionIcon(title = '') {
      const normalized = String(title).toLowerCase();
      if (normalized.includes('new') || normalized.includes('add')) return 'add_circle';
      if (normalized.includes('fix')) return 'task_alt';
      if (normalized.includes('security')) return 'shield';
      if (normalized.includes('remove')) return 'remove_circle';
      return 'tune';
    }

    return {
      ...props.app,
      changelogSectionIcon
    };
  }
};
</script>

<template>
  <q-dialog v-model="changelogDialogOpen" aria-label="What's new in Orchard">
    <q-card class="changelog-dialog" :class="{ 'changelog-dialog--single': !hasMultipleChangelogTabs }">
      <header class="changelog-dialog__header">
        <div class="changelog-dialog__heading">
          <div>
            <div class="changelog-dialog__title">What's new</div>
            <div class="changelog-dialog__subtitle">
              <template v-if="selectedChangelogEntry">
                Orchard {{ changelogReleaseLabel(selectedChangelogEntry) }} · {{ selectedChangelogEntry.date }}
              </template>
              <template v-else>Orchard release notes</template>
            </div>
          </div>
          <div v-if="selectedChangelogEntry" class="changelog-dialog__count">
            <strong>{{ selectedChangelogItemCount }}</strong>
            <span>{{ selectedChangelogItemCount === 1 ? 'change' : 'changes' }}</span>
          </div>
        </div>
        <q-btn v-close-popup flat round dense icon="close" aria-label="Close release notes" />
      </header>

      <div class="changelog-dialog__content">
        <nav
          v-if="hasMultipleChangelogTabs"
          class="changelog-tabs"
          role="tablist"
          aria-label="Release history"
        >
          <button
            v-for="(tab, index) in changelogTabs"
            :id="`changelog-tab-${tab.key}`"
            :key="tab.key"
            type="button"
            role="tab"
            :aria-controls="`changelog-panel-${tab.key}`"
            :aria-selected="activeChangelogTab === tab.key"
            :class="{ 'changelog-tab--active': activeChangelogTab === tab.key }"
            :tabindex="activeChangelogTab === tab.key ? 0 : -1"
            @click="selectChangelogTab(tab.key)"
            @keydown="onChangelogTabKeydown($event, index)"
          >
            <span class="changelog-tab__version">Orchard {{ changelogReleaseLabel(tab.entry) }}</span>
            <span class="changelog-tab__date">
              {{ tab.entry.kind === 'update' ? 'Available now' : tab.entry.date }}
            </span>
          </button>
        </nav>

        <div class="changelog-dialog__body">
          <section
            v-if="selectedChangelogEntry"
            :id="hasMultipleChangelogTabs ? `changelog-panel-${activeChangelogTab}` : undefined"
            :key="activeChangelogTab"
            :role="hasMultipleChangelogTabs ? 'tabpanel' : undefined"
            :aria-labelledby="hasMultipleChangelogTabs ? `changelog-tab-${activeChangelogTab}` : undefined"
            class="changelog-release"
          >
            <header class="changelog-release__summary">
              <div>
                <strong>Orchard {{ changelogReleaseLabel(selectedChangelogEntry) }}</strong>
                <span>{{ selectedChangelogEntry.date }}</span>
              </div>
              <span v-if="selectedChangelogEntry.kind === 'update'" class="changelog-release__status">
                Update available
              </span>
            </header>

            <div class="changelog-release__sections">
              <article
                v-for="section in selectedChangelogEntry.sections"
                :key="section.title"
                class="changelog-release__section"
              >
                <div class="changelog-release__section-heading">
                  <q-icon :name="changelogSectionIcon(section.title)" />
                  <strong>{{ section.title }}</strong>
                  <span>{{ section.items.length }}</span>
                </div>
                <ul class="changelog-release__items">
                  <li v-for="item in section.items" :key="item">{{ item }}</li>
                </ul>
              </article>
            </div>
          </section>
        </div>
      </div>
    </q-card>
  </q-dialog>
</template>
