export function installNavigationActions(ctx) {
  const lastPageStorageKey = 'orchard:last-page';
  const validViews = new Set(['home', 'queue', 'history', 'replay', 'releaseRadar', 'podcasts', 'pins', 'search', 'browse', 'sectionMore', 'settings', 'support']);
  const signedOutViews = new Set(['home', 'settings', 'support']);
  let didRestoreStartupPage = false;
  let restoredQueryChangePending = false;

  ctx.createNavigationEntry = function createNavigationEntry() {
    return {
      view: ctx.activeView.value,
      browseOrigin: ctx.browseOrigin.value,
      browseDetail: ctx.browseDetail.value,
      sectionMoreDetail: ctx.sectionMoreDetail.value,
      query: ctx.query.value,
      searchResult: ctx.searchResult.value,
      selectedFilter: ctx.selectedFilter.value
    };
  };

  ctx.safeNavigationEntry = function safeNavigationEntry(entry = ctx.createNavigationEntry(), options = {}) {
    const signedIn = options.signedIn ?? ctx.authState.value.signedIn;
    let view = validViews.has(entry?.view) ? entry.view : 'home';

    if (!signedIn && !signedOutViews.has(view)) view = 'home';
    if (view === 'browse' && !entry.browseDetail) view = entry.browseOrigin || 'home';
    if (view === 'sectionMore' && !entry.sectionMoreDetail) view = entry.browseDetail ? 'browse' : 'home';

    return {
      view: validViews.has(view) ? view : 'home',
      browseOrigin: validViews.has(entry?.browseOrigin) ? entry.browseOrigin : 'home',
      browseDetail: entry?.browseDetail || null,
      sectionMoreDetail: entry?.sectionMoreDetail || null,
      query: typeof entry?.query === 'string' ? entry.query.slice(0, 180) : '',
      searchResult: entry?.searchResult || { sections: [] },
      selectedFilter: entry?.selectedFilter || 'all'
    };
  };

  ctx.readLastPageEntry = function readLastPageEntry(options = {}) {
    if (typeof window === 'undefined') return null;

    try {
      const parsed = JSON.parse(window.localStorage.getItem(lastPageStorageKey) || 'null');
      return parsed ? ctx.safeNavigationEntry(parsed, options) : null;
    } catch {
      return null;
    }
  };

  ctx.writeLastPageEntry = function writeLastPageEntry(entry = ctx.createNavigationEntry()) {
    if (typeof window === 'undefined') return;

    try {
      window.localStorage.setItem(lastPageStorageKey, JSON.stringify(ctx.safeNavigationEntry(entry)));
    } catch {
      // Last-page restore is helpful, but storage failures should not affect navigation.
    }
  };

  ctx.restoreStartupPage = function restoreStartupPage(authState = ctx.authState.value) {
    if (didRestoreStartupPage) return false;
    didRestoreStartupPage = true;

    const entry = ctx.readLastPageEntry({ signedIn: Boolean(authState?.signedIn) });
    if (!entry) return false;

    ctx.restoreNavigationEntry(entry);
    return entry.view !== 'home';
  };

  ctx.navigationEntryKey = function navigationEntryKey(entry) {
    const browseId = entry.browseDetail?.browseId || '';
    const sectionKey = entry.sectionMoreDetail?.key || '';
    const sectionSource = entry.sectionMoreDetail?.sourceTitle || '';
    return `${entry.view}:${browseId}:${sectionKey}:${sectionSource}`;
  };

  ctx.pushNavigationEntry = function pushNavigationEntry(entry = ctx.createNavigationEntry()) {
    const history = ctx.navigationHistory.value;
    const currentKey = ctx.navigationEntryKey(entry);
    if (history.at(-1) && ctx.navigationEntryKey(history.at(-1)) === currentKey) return;

    ctx.navigationHistory.value = [...history, entry].slice(-25);
  };

  ctx.restoreNavigationEntry = function restoreNavigationEntry(entry) {
    ctx.restoringNavigation = true;
    try {
      const nextQuery = entry.query || '';
      ctx.browseOrigin.value = entry.browseOrigin || 'home';
      ctx.browseDetail.value = entry.browseDetail || null;
      ctx.sectionMoreDetail.value = entry.sectionMoreDetail || null;
      restoredQueryChangePending = nextQuery !== ctx.query.value;
      ctx.query.value = nextQuery;
      ctx.searchResult.value = entry.searchResult || { sections: [] };
      ctx.selectedFilter.value = entry.selectedFilter || 'all';
      ctx.activeView.value = entry.view || 'home';
      ctx.errorMessage.value = '';
      ctx.warningMessage.value = '';
    } finally {
      ctx.restoringNavigation = false;
    }
  };

  ctx.consumeRestoredQueryChange = function consumeRestoredQueryChange() {
    if (!restoredQueryChangePending) return false;
    restoredQueryChangePending = false;
    return true;
  };

  ctx.navigateToView = function navigateToView(view, options = {}) {
    if (view !== ctx.activeView.value || options.force) ctx.pushNavigationEntry();
    ctx.activeView.value = view;
    if (view !== 'sectionMore' && options.clearSectionMore !== false) ctx.sectionMoreDetail.value = null;
  };

  ctx.resetNavigation = function resetNavigation(view = 'home') {
    ctx.navigationHistory.value = [];
    ctx.activeView.value = view;
    if (view !== 'sectionMore') ctx.sectionMoreDetail.value = null;
  };

  ctx.goBack = function goBack() {
    const history = [...ctx.navigationHistory.value];
    let entry = history.pop();
    const currentKey = ctx.navigationEntryKey(ctx.createNavigationEntry());

    while (entry && ctx.navigationEntryKey(entry) === currentKey) {
      entry = history.pop();
    }

    ctx.navigationHistory.value = history;
    if (!entry) return;
    ctx.restoreNavigationEntry(entry);
  };
}
