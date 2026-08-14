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

export const WELCOME_COMPLETED_KEY = 'orchard:welcome-completed';

function writeRendererCompletion(window) {
  if (!window || window.isDestroyed()) return Promise.resolve(false);

  return window.webContents.executeJavaScript(`(() => {
    const key = 'orchard:setup-state';
    let state = {};
    try { state = JSON.parse(localStorage.getItem(key) || '{}'); } catch {}
    localStorage.setItem(key, JSON.stringify({ ...state, completed: true, welcomeCompleted: true }));
    return true;
  })()`).catch(() => false);
}

export function setWelcomeCompleted(store, completed) {
  if (!store?.set(WELCOME_COMPLETED_KEY, Boolean(completed))) return false;
  store.flush?.();
  return true;
}

// The main-process marker is authoritative because it is shared by every
// renderer and flushed before the welcome window is closed. Existing installs
// are migrated once from the renderer-owned setup state.
export async function welcomeRequiredAtLaunch(window, store) {
  const stored = store?.get(WELCOME_COMPLETED_KEY);
  if (stored === true) {
    // Keep the renderer copy coherent for settings, backups, and upgrade prompts.
    await writeRendererCompletion(window);
    return false;
  }
  if (stored === false) return true;
  if (!window || window.isDestroyed()) return true;

  try {
    const completed = await window.webContents.executeJavaScript(`(() => {
      try {
        return Boolean(JSON.parse(localStorage.getItem('orchard:setup-state') || '{}').welcomeCompleted);
      } catch {
        return false;
      }
    })()`);
    if (completed) setWelcomeCompleted(store, true);
    return !completed;
  } catch {
    return true;
  }
}
