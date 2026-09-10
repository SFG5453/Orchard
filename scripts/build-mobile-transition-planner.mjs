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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Keep Android on the authoritative desktop planner. Gradle generates this resource on build.
import { build } from 'vite';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';
import { transformAsync } from '@babel/core';
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
await build({
  root, configFile: false, logLevel: 'warn',
  build: {
    outDir: path.resolve(root, process.argv[2] || 'mobile/android/app/build/generated/transitionPlanner'),
    emptyOutDir: false, target: 'es2015', minify: false,
    lib: {
      entry: path.join(root, 'mobile/tools/transition-planner/index.js'),
      formats: ['iife'], name: 'OrchardTransitionPlanner',
      fileName: () => 'transition-planner.js'
    }
  }
});

// Rhino supports the required built-ins, but its parser needs ES5 syntax for loop bindings.
const output = path.resolve(root, process.argv[2] || 'mobile/android/app/build/generated/transitionPlanner', 'transition-planner.js');
const transformed = await transformAsync(await readFile(output, 'utf8'), {
  configFile: false, babelrc: false,
  presets: [['@babel/preset-env', { targets: { ie: '11' }, modules: false }]]
});
await writeFile(output, transformed.code + '\n');
