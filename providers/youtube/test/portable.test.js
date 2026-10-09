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

import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import test from 'node:test';

async function JavaScriptFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const child = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory);
    if (entry.isDirectory()) files.push(...await JavaScriptFiles(child));
    else if (entry.name.endsWith('.js')) files.push(child);
  }
  return files;
}

test('YouTube provider runtime has no Node or Electron imports', async () => {
  for (const file of await JavaScriptFiles(new URL('../src/', import.meta.url))) {
    const source = await readFile(file, 'utf8');
    assert.doesNotMatch(source, /from\s+['"]node:|import\s*\(\s*['"]electron|require\s*\(|from\s+['"]electron/, file.pathname);
  }
});

