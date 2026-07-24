import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  GRAPHICS_MODES,
  integratedGpuSelectionSupported,
  normalizeGraphicsMode
} from '../shared/graphicsMode.js';
import {
  applyStartupGraphicsMode,
  configureLinuxIntegratedGpuEnvironment,
  createGraphicsModeController,
  linuxDriPrimeSelector,
  linuxRenderNodePath,
  readStoredGraphicsMode,
  selectLinuxIntegratedGpu,
  writeStoredGraphicsMode
} from '../electron/main/graphicsMode.js';

function temporaryPreferencesPath(t) {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'orchard-graphics-mode-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return path.join(directory, 'preferences', 'graphics-mode.json');
}

function fakeApp() {
  const calls = [];
  return {
    calls,
    commandLine: {
      appendSwitch: (name, value) => calls.push(
        value === undefined ? ['appendSwitch', name] : ['appendSwitch', name, value]
      )
    },
    quit: () => calls.push(['quit']),
    relaunch: () => calls.push(['relaunch'])
  };
}

test('graphics mode normalization defaults unknown values to Automatic', () => {
  assert.equal(normalizeGraphicsMode('automatic'), GRAPHICS_MODES.AUTOMATIC);
  assert.equal(normalizeGraphicsMode('integrated'), GRAPHICS_MODES.INTEGRATED);
  assert.equal(normalizeGraphicsMode('software'), GRAPHICS_MODES.AUTOMATIC);
  assert.equal(normalizeGraphicsMode('disable-gpu'), GRAPHICS_MODES.AUTOMATIC);
  assert.equal(normalizeGraphicsMode(null), GRAPHICS_MODES.AUTOMATIC);
});

test('integrated GPU selection reports only implemented desktop platforms', () => {
  assert.equal(integratedGpuSelectionSupported('win32'), true);
  assert.equal(integratedGpuSelectionSupported('darwin'), true);
  assert.equal(integratedGpuSelectionSupported('linux'), false);
  assert.equal(integratedGpuSelectionSupported('freebsd'), false);
});

test('graphics mode storage defaults missing or corrupt files to Automatic', (t) => {
  const filePath = temporaryPreferencesPath(t);
  assert.equal(readStoredGraphicsMode(filePath), GRAPHICS_MODES.AUTOMATIC);

  mkdirSync(path.dirname(filePath), { recursive: true });
  writeFileSync(filePath, '{broken json');
  assert.equal(readStoredGraphicsMode(filePath), GRAPHICS_MODES.AUTOMATIC);
});

test('graphics mode storage writes normalized state', (t) => {
  const filePath = temporaryPreferencesPath(t);
  assert.equal(writeStoredGraphicsMode(filePath, 'integrated'), GRAPHICS_MODES.INTEGRATED);
  assert.deepEqual(JSON.parse(readFileSync(filePath, 'utf8')), { graphicsMode: 'integrated' });
  assert.equal(readStoredGraphicsMode(filePath), GRAPHICS_MODES.INTEGRATED);

  assert.equal(writeStoredGraphicsMode(filePath, 'software'), GRAPHICS_MODES.AUTOMATIC);
  assert.equal(readStoredGraphicsMode(filePath), GRAPHICS_MODES.AUTOMATIC);
});

test('integrated mode applies Electron low-power selection on Windows and macOS', () => {
  for (const platform of ['win32', 'darwin']) {
    const app = fakeApp();
    const applied = applyStartupGraphicsMode({
      app,
      graphicsMode: GRAPHICS_MODES.INTEGRATED,
      platform
    });

    assert.equal(applied, GRAPHICS_MODES.INTEGRATED);
    assert.deepEqual(app.calls, [['appendSwitch', 'force_low_power_gpu']]);
  }
});

test('Linux integrated detection identifies AMD APUs and Intel processor graphics', () => {
  const amdApu = {
    bootVga: false,
    deviceId: '0x1638',
    pciAddress: '0000:09:00.0',
    renderNodePath: '/dev/dri/renderD129',
    subsystemVendorId: '0x1458',
    topology: ['0000:00:08.1', '0000:09:00.0'],
    vendorId: '0x1002'
  };
  const amdDiscrete = {
    bootVga: true,
    deviceId: '0x731f',
    pciAddress: '0000:03:00.0',
    renderNodePath: '/dev/dri/renderD128',
    subsystemVendorId: '0x1002',
    topology: ['0000:00:01.1', '0000:01:00.0', '0000:02:00.0', '0000:03:00.0'],
    vendorId: '0x1002'
  };
  const intelIntegrated = {
    bootVga: true,
    deviceId: '0x46a6',
    pciAddress: '0000:00:02.0',
    renderNodePath: '/dev/dri/renderD128',
    subsystemVendorId: '0x17aa',
    topology: ['0000:00:02.0'],
    vendorId: '0x8086'
  };

  assert.equal(selectLinuxIntegratedGpu([amdDiscrete, amdApu])?.pciAddress, '0000:09:00.0');
  assert.equal(selectLinuxIntegratedGpu([intelIntegrated])?.pciAddress, '0000:00:02.0');
  assert.equal(selectLinuxIntegratedGpu([amdDiscrete]), null);
  assert.equal(linuxDriPrimeSelector(amdApu), 'pci-0000_09_00_0');
  assert.equal(linuxRenderNodePath(amdApu), '/dev/dri/renderD129');
});

test('Linux integrated mode explicitly selects the detected PCI device', () => {
  const integratedGpu = {
    pciAddress: '0000:09:00.0',
    renderNodePath: '/dev/dri/renderD129'
  };
  const environment = {
    DRI_PRIME: '1',
    KEEP_ME: 'yes',
    __GLX_VENDOR_LIBRARY_NAME: 'nvidia',
    __NV_PRIME_RENDER_OFFLOAD: '1',
    __NV_PRIME_RENDER_OFFLOAD_PROVIDER: 'NVIDIA-G0',
    __VK_LAYER_NV_optimus: 'NVIDIA_only'
  };
  const app = fakeApp();

  assert.equal(
    configureLinuxIntegratedGpuEnvironment(integratedGpu, environment),
    'pci-0000_09_00_0'
  );
  assert.deepEqual(environment, {
    DRI_PRIME: 'pci-0000_09_00_0',
    KEEP_ME: 'yes'
  });
  assert.equal(applyStartupGraphicsMode({
    app,
    environment,
    graphicsMode: GRAPHICS_MODES.INTEGRATED,
    linuxIntegratedGpu: integratedGpu,
    platform: 'linux'
  }), GRAPHICS_MODES.INTEGRATED);
  assert.equal(environment.DRI_PRIME, 'pci-0000_09_00_0');
  assert.deepEqual(app.calls, [
    ['appendSwitch', 'render-node-override', '/dev/dri/renderD129'],
    ['appendSwitch', 'force_low_power_gpu']
  ]);
});

test('Linux graphics controller applies a detected AMD APU instead of the boot GPU', (t) => {
  const filePath = temporaryPreferencesPath(t);
  writeStoredGraphicsMode(filePath, GRAPHICS_MODES.INTEGRATED);
  const environment = {};
  const app = fakeApp();
  const controller = createGraphicsModeController({
    app,
    environment,
    filePath,
    linuxGraphicsDevices: [
      {
        bootVga: true,
        deviceId: '0x731f',
        pciAddress: '0000:03:00.0',
        renderNodePath: '/dev/dri/renderD128',
        subsystemVendorId: '0x1002',
        topology: ['0000:00:01.1', '0000:01:00.0', '0000:02:00.0', '0000:03:00.0'],
        vendorId: '0x1002'
      },
      {
        bootVga: false,
        deviceId: '0x1638',
        pciAddress: '0000:09:00.0',
        renderNodePath: '/dev/dri/renderD129',
        subsystemVendorId: '0x1458',
        topology: ['0000:00:08.1', '0000:09:00.0'],
        vendorId: '0x1002'
      }
    ],
    platform: 'linux'
  });

  assert.deepEqual(controller.state(), {
    appliedMode: GRAPHICS_MODES.INTEGRATED,
    integratedGpuDevice: '1002:1638@0000:09:00.0',
    integratedGpuSupported: true,
    platform: 'linux',
    restartRequired: false,
    selectedMode: GRAPHICS_MODES.INTEGRATED
  });
  assert.equal(environment.DRI_PRIME, 'pci-0000_09_00_0');
  assert.deepEqual(app.calls, [
    ['appendSwitch', 'render-node-override', '/dev/dri/renderD129'],
    ['appendSwitch', 'force_low_power_gpu']
  ]);
});

test('unsupported integrated selection falls back to Automatic when persisted', (t) => {
  const filePath = temporaryPreferencesPath(t);
  writeStoredGraphicsMode(filePath, GRAPHICS_MODES.INTEGRATED);
  const app = fakeApp();
  const controller = createGraphicsModeController({
    app,
    filePath,
    platform: 'freebsd'
  });

  assert.deepEqual(controller.state(), {
    appliedMode: GRAPHICS_MODES.AUTOMATIC,
    integratedGpuDevice: '',
    integratedGpuSupported: false,
    platform: 'freebsd',
    restartRequired: true,
    selectedMode: GRAPHICS_MODES.INTEGRATED
  });
  assert.equal(controller.setMode(GRAPHICS_MODES.INTEGRATED).selectedMode, GRAPHICS_MODES.AUTOMATIC);
  assert.equal(readStoredGraphicsMode(filePath), GRAPHICS_MODES.AUTOMATIC);
});

test('graphics controller tracks restart state and uses Electron relaunch', (t) => {
  const filePath = temporaryPreferencesPath(t);
  const app = fakeApp();
  const controller = createGraphicsModeController({
    app,
    filePath,
    platform: 'win32'
  });

  assert.equal(controller.state().restartRequired, false);
  assert.equal(controller.setMode(GRAPHICS_MODES.INTEGRATED).restartRequired, true);
  controller.restart();
  assert.deepEqual(app.calls, [['relaunch'], ['quit']]);
});

test('Linux graphics controller restores the inherited GPU environment before restarting into Automatic', (t) => {
  const filePath = temporaryPreferencesPath(t);
  writeStoredGraphicsMode(filePath, GRAPHICS_MODES.INTEGRATED);
  const environment = {
    DRI_PRIME: '1',
    KEEP_ME: 'yes',
    __GLX_VENDOR_LIBRARY_NAME: 'nvidia',
    __NV_PRIME_RENDER_OFFLOAD: '1',
    __NV_PRIME_RENDER_OFFLOAD_PROVIDER: 'NVIDIA-G0',
    __VK_LAYER_NV_optimus: 'NVIDIA_only'
  };
  const inheritedEnvironment = { ...environment };
  const app = fakeApp();
  const controller = createGraphicsModeController({
    app,
    environment,
    filePath,
    linuxGraphicsDevices: [{
      bootVga: false,
      deviceId: '0x1638',
      pciAddress: '0000:09:00.0',
      renderNodePath: '/dev/dri/renderD129',
      subsystemVendorId: '0x1458',
      topology: ['0000:00:08.1', '0000:09:00.0'],
      vendorId: '0x1002'
    }],
    platform: 'linux'
  });

  assert.equal(environment.DRI_PRIME, 'pci-0000_09_00_0');
  assert.equal(environment.__NV_PRIME_RENDER_OFFLOAD, undefined);

  controller.setMode(GRAPHICS_MODES.AUTOMATIC);
  controller.restart();

  assert.deepEqual(environment, inheritedEnvironment);
  assert.deepEqual(app.calls, [
    ['appendSwitch', 'render-node-override', '/dev/dri/renderD129'],
    ['appendSwitch', 'force_low_power_gpu'],
    ['relaunch'],
    ['quit']
  ]);
});
