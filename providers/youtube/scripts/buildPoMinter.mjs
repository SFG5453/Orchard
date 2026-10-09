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

import { build } from 'esbuild';
import { builtinModules } from 'node:module';
import { fileURLToPath } from 'node:url';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';

const output = process.argv.find(arg => arg.startsWith('--outfile='))?.slice(10);
if (!output) throw new Error('--outfile is required');
const builtins = new Set(builtinModules.map(name => name.replace(/^node:/, '')));
const unavailable = new Set(['fs', 'vm', 'crypto', 'child_process', 'worker_threads',
  'tls', 'dns', 'dgram', 'canvas', 'ws']);
// Initialize web globals before any DOM dependency executes. An injected
// module alone creates a cycle through the Buffer/text-encoding shims.
const prelude = await build({
  entryPoints: ['src/runtime/poPrelude.js'], bundle: true, platform: 'browser',
  format: 'iife', minify: true, write: false, legalComments: 'eof', metafile: true
});

const result = await build({
  entryPoints: ['src/runtime/poMinter.js'], outfile: output,
  bundle: true, platform: 'browser', format: 'iife', target: 'es2023',
  minify: true, legalComments: 'external', metafile: true,
  banner: { js: prelude.outputFiles[0].text },
  inject: ['scripts/poNodeGlobals.js'],
  plugins: [{
    name: 'quickjs-dom-dependencies',
    setup(builder) {
      builder.onResolve({ filter: /^[\w@]/ }, ({ path }) => {
        const name = path.replace(/^node:/, '');
        // The DOM must never introduce another networking or execution host.
        if (unavailable.has(name)) return { path: name, namespace: 'unavailable' };
        if (builtins.has(name)) return {
          path: fileURLToPath(new URL(`../node_modules/@jspm/core/nodelibs/browser/${name}.js`, import.meta.url))
        };
      });
      builder.onLoad({ filter: /.*/, namespace: 'unavailable' }, () => ({ contents: 'module.exports = {};' }));
    }
  }]
});

// Bytecode drops comments, so ship dependency notices as a separate resource.
const packages = new Set();
for (const input of Object.keys({ ...prelude.metafile.inputs, ...result.metafile.inputs })) {
  const parts = input.replaceAll('\\', '/').split('node_modules/').pop()?.split('/');
  if (!input.includes('node_modules/') || !parts) continue;
  packages.add(parts[0].startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]);
}
const notices = [await readFile(`${output}.LEGAL.txt`, 'utf8')];
for (const name of [...packages].sort()) {
  const root = resolve('node_modules', name);
  const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  notices.push(`\n${name} ${manifest.version} (${manifest.license || 'see notice'})\n`);
  for (const filename of await readdir(root)) {
    if (/^(license|licence|copying|notice)(\.|$)/i.test(filename)) {
      notices.push(await readFile(join(root, filename), 'utf8'));
    }
  }
}
await writeFile(join(dirname(output), 'youtube-po-minter-licenses.txt'), notices.join('\n'));
