/*
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
 */

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

  ctx.applyLayoutPreset = function applyLayoutPreset() {
    document.documentElement.dataset.layoutPreset = ctx.layoutPreset.value;
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
