#!/usr/bin/env node
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
