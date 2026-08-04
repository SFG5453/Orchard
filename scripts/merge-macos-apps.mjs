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

import path from 'node:path';

const [x64Input, arm64Input, output] = process.argv.slice(2);

if (!x64Input || !arm64Input || !output) {
  throw new Error('Usage: merge-macos-apps.mjs <x64.app> <arm64.app> <output.app>');
}

// @electron/universal only guards on the host platform; its merge work is
// portable when an OSXCross-compatible `lipo` command is available on PATH.
Object.defineProperty(process, 'platform', { value: 'darwin' });
const { makeUniversalApp } = await import('@electron/universal');

await makeUniversalApp({
  x64AppPath: path.resolve(x64Input),
  arm64AppPath: path.resolve(arm64Input),
  outAppPath: path.resolve(output),
  force: true,
  mergeASARs: true
});
