#!/usr/bin/env node
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

import { spawn } from 'node:child_process';
import electronPath from 'electron';

const env = { ...process.env };
const electronArgs = ['.'];

delete env.ELECTRON_RUN_AS_NODE;
delete env.ELECTRON_NO_ATTACH_CONSOLE;

for (const arg of process.argv.slice(2)) {
  if (arg.startsWith('--dev-server=')) {
    env.VITE_DEV_SERVER_URL = arg.slice('--dev-server='.length);
  } else {
    electronArgs.push(arg);
  }
}

const child = spawn(electronPath, electronArgs, {
  env,
  stdio: 'inherit',
  shell: false
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (!child.killed) child.kill(signal);
  });
}

child.on('exit', (code, signal) => {
  if (signal) {
    process.exit(1);
  }

  process.exit(code ?? 0);
});
