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

// Persists the non-cookie YouTube identity needed to restore a switched account.
import fs from 'node:fs';
import path from 'node:path';

const selectionFilename = 'youtube-browser-account.json';

function cleanString(value, maximumLength) {
  return String(value || '').trim().slice(0, maximumLength);
}

export function normalizeBrowserAccountSelection(value) {
  const candidate = value && typeof value === 'object' ? value : {};
  const rawAccountIndex = Number(candidate.accountIndex);
  return {
    visitorData: cleanString(candidate.visitorData, 2048),
    dataSyncId: cleanString(candidate.dataSyncId, 1024),
    accountIndex: Number.isSafeInteger(rawAccountIndex) && rawAccountIndex >= 0 ? rawAccountIndex : 0
  };
}

export function createBrowserAccountSelectionStore(userDataPath) {
  const selectionPath = path.join(userDataPath, selectionFilename);
  let selection;

  try {
    selection = normalizeBrowserAccountSelection(JSON.parse(fs.readFileSync(selectionPath, 'utf8')));
  } catch {
    selection = normalizeBrowserAccountSelection();
  }

  function cached() {
    return { ...selection };
  }

  function save(value) {
    selection = normalizeBrowserAccountSelection(value);
    try {
      fs.writeFileSync(selectionPath, `${JSON.stringify(selection, null, 2)}\n`, { mode: 0o600 });
    } catch {
      // The selected account still remains active for the current process.
    }
    return cached();
  }

  function clear() {
    selection = normalizeBrowserAccountSelection();
    try {
      fs.rmSync(selectionPath, { force: true });
    } catch {
      // Sign-out should continue even if the cached selection cannot be removed.
    }
  }

  return { cached, clear, save };
}
