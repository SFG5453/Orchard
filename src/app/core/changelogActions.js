import { computed, nextTick, ref } from 'vue';
import { LATEST_CHANGELOG_VERSION, ORCHARD_RELEASES } from '../../data/changelog.js';

const LAST_SEEN_CHANGELOG_KEY = 'orchard:last-seen-changelog';

function storageAvailable() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

function normalizedVersion(value = '') {
  return String(value || '').trim().replace(/^v/i, '');
}

function releaseLabel(release = {}) {
  return release.codename ? `${release.version} "${release.codename}"` : release.version;
}

function plainReleaseNoteText(value = '') {
  return String(value)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)(.*?)\1/g, '$2')
    .replace(/<[^>]+>/g, '')
    .trim();
}

export function parseReleaseNoteSections(notes) {
  const lines = (Array.isArray(notes) ? notes : [notes])
    .flatMap((entry) => String(entry || '').split(/\r?\n/))
    .map((line) => line.trim())
    .filter(Boolean);
  const sections = [];
  let currentSection = null;

  function ensureSection(title = 'Update') {
    const cleanTitle = plainReleaseNoteText(title) || 'Update';
    currentSection = sections.find((section) => section.title === cleanTitle);
    if (!currentSection) {
      currentSection = { title: cleanTitle, items: [] };
      sections.push(currentSection);
    }
    return currentSection;
  }

  for (const line of lines) {
    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      if (heading[1].length === 1) continue;
      ensureSection(heading[2]);
      continue;
    }

    const item = plainReleaseNoteText(line.replace(/^\s*(?:[-*+] |\d+[.)]\s+)/, ''));
    if (item) (currentSection || ensureSection()).items.push(item);
  }

  return sections.filter((section) => section.items.length > 0);
}

export function installChangelogActions(ctx) {
  ctx.changelogReleases = ORCHARD_RELEASES;
  ctx.selectedChangelogTab = ref('');

  ctx.currentRelease = computed(() => (
    ORCHARD_RELEASES.find((release) => normalizedVersion(release.version) === normalizedVersion(ctx.appVersion)) || null
  ));

  ctx.currentReleaseLabel = computed(() => (
    ctx.currentRelease.value ? releaseLabel(ctx.currentRelease.value) : ctx.appVersion
  ));

  ctx.changelogReleaseLabel = function changelogReleaseLabel(release) {
    return release ? releaseLabel(release) : '';
  };

  ctx.updateReleaseNotes = computed(() => {
    const notes = ctx.updateState.value?.releaseNotes;
    return Array.isArray(notes) ? notes.filter(Boolean) : [];
  });

  ctx.hasAvailableUpdate = computed(() => (
    ['available', 'downloading', 'downloaded'].includes(ctx.updateState.value?.status) &&
    Boolean(normalizedVersion(ctx.updateState.value?.availableVersion))
  ));

  ctx.updateReleaseNoteSections = computed(() => parseReleaseNoteSections(ctx.updateReleaseNotes.value));

  ctx.hasUpdateReleaseNotes = computed(() => (
    ctx.hasAvailableUpdate.value && ctx.updateReleaseNoteSections.value.length > 0
  ));

  ctx.changelogTabs = computed(() => {
    const updateVersion = normalizedVersion(ctx.updateState.value.availableVersion);
    let matchedUpdate = false;
    const tabs = ctx.changelogReleases.map((release) => {
      const isUpdate = ctx.hasAvailableUpdate.value && normalizedVersion(release.version) === updateVersion;
      if (isUpdate) matchedUpdate = true;

      return {
        key: `release-${release.version}`,
        label: release.version,
        entry: {
          ...release,
          kind: isUpdate ? 'update' : 'release',
          sections: isUpdate && ctx.hasUpdateReleaseNotes.value
            ? ctx.updateReleaseNoteSections.value
            : release.sections
        }
      };
    });

    if (ctx.hasAvailableUpdate.value && !matchedUpdate) {
      tabs.unshift({
        key: `update-${updateVersion}`,
        label: updateVersion,
        entry: {
          kind: 'update',
          version: updateVersion,
          date: 'Available now',
          sections: ctx.hasUpdateReleaseNotes.value
            ? ctx.updateReleaseNoteSections.value
            : [{ title: 'Update', items: ['A newer Orchard release is available.'] }]
        }
      });
    }

    return tabs.sort((left, right) => Number(right.entry.kind === 'update') - Number(left.entry.kind === 'update'));
  });

  ctx.activeChangelogTab = computed(() => {
    const tabs = ctx.changelogTabs.value;
    return tabs.some((tab) => tab.key === ctx.selectedChangelogTab.value)
      ? ctx.selectedChangelogTab.value
      : tabs[0]?.key || '';
  });

  ctx.selectedChangelogEntry = computed(() => (
    ctx.changelogTabs.value.find((tab) => tab.key === ctx.activeChangelogTab.value)?.entry || null
  ));

  ctx.selectedChangelogItemCount = computed(() => (
    ctx.selectedChangelogEntry.value?.sections.reduce((total, section) => total + section.items.length, 0) || 0
  ));

  ctx.hasMultipleChangelogTabs = computed(() => ctx.changelogTabs.value.length > 1);

  ctx.selectChangelogTab = function selectChangelogTab(key) {
    ctx.selectedChangelogTab.value = key;
  };

  ctx.onChangelogTabKeydown = function onChangelogTabKeydown(event, index) {
    const tabs = ctx.changelogTabs.value;
    let nextIndex = index;
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') nextIndex = 0;
    else if (event.key === 'End') nextIndex = tabs.length - 1;
    else return;

    event.preventDefault();
    ctx.selectedChangelogTab.value = tabs[nextIndex]?.key || '';
    nextTick(() => document.querySelectorAll('.changelog-tabs [role="tab"]')[nextIndex]?.focus());
  };

  ctx.markChangelogSeen = function markChangelogSeen() {
    if (!storageAvailable() || !LATEST_CHANGELOG_VERSION) return;

    try {
      window.localStorage.setItem(LAST_SEEN_CHANGELOG_KEY, LATEST_CHANGELOG_VERSION);
    } catch {
      // Changelog display should still work when persistent storage is unavailable.
    }
  };

  ctx.openChangelog = function openChangelog() {
    ctx.selectedChangelogTab.value = ctx.changelogTabs.value[0]?.key || '';
    ctx.changelogDialogOpen.value = true;
    ctx.markChangelogSeen();
  };

  ctx.showChangelogAfterUpgrade = function showChangelogAfterUpgrade() {
    if (!storageAvailable() || !LATEST_CHANGELOG_VERSION) return;

    try {
      if (window.localStorage.getItem(LAST_SEEN_CHANGELOG_KEY) === LATEST_CHANGELOG_VERSION) return;
      ctx.openChangelog();
    } catch {
      // Do not interrupt startup if local storage is blocked.
    }
  };
}
