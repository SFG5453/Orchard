import { Dark } from 'quasar';
import { resolvedTheme } from './appearancePreferences.js';

const SYSTEM_THEME_QUERY = '(prefers-color-scheme: dark)';

export function installAppearanceLifecycle(ctx) {
  let systemThemeMedia = null;

  ctx.applyThemePreference = function applyThemePreference() {
    const systemPrefersDark = systemThemeMedia?.matches ?? window.matchMedia(SYSTEM_THEME_QUERY).matches;
    const theme = resolvedTheme(ctx.themePreference.value, systemPrefersDark);
    const root = document.documentElement;

    root.dataset.themePreference = ctx.themePreference.value;
    root.dataset.themeResolved = theme;
    root.style.colorScheme = theme === 'light' ? 'light' : 'dark';
    Dark.set(theme !== 'light');
  };

  ctx.bindSystemThemePreference = function bindSystemThemePreference() {
    systemThemeMedia = window.matchMedia(SYSTEM_THEME_QUERY);
    systemThemeMedia.addEventListener?.('change', ctx.applyThemePreference);
    ctx.applyThemePreference();
  };

  ctx.clearSystemThemePreference = function clearSystemThemePreference() {
    systemThemeMedia?.removeEventListener?.('change', ctx.applyThemePreference);
    systemThemeMedia = null;
  };
}
