import {
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import path from 'node:path';
import {
  GRAPHICS_MODES,
  integratedGpuSelectionSupported,
  normalizeGraphicsMode
} from '../../shared/graphicsMode.js';

export const GRAPHICS_MODE_FILENAME = 'graphics-mode.json';

const LINUX_GPU_OFFLOAD_ENVIRONMENT = Object.freeze([
  '__GLX_VENDOR_LIBRARY_NAME',
  '__NV_PRIME_RENDER_OFFLOAD',
  '__NV_PRIME_RENDER_OFFLOAD_PROVIDER',
  '__VK_LAYER_NV_optimus'
]);
const LINUX_GPU_ENVIRONMENT = Object.freeze([
  'DRI_PRIME',
  ...LINUX_GPU_OFFLOAD_ENVIRONMENT
]);

function readText(filePath) {
  try {
    return readFileSync(filePath, 'utf8').trim().toLowerCase();
  } catch {
    return '';
  }
}

function pciAddresses(value) {
  return [...String(value).matchAll(/\b[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\.[0-7]\b/gi)]
    .map((match) => match[0].toLowerCase());
}

export function readLinuxGraphicsDevices(drmPath = '/sys/class/drm') {
  try {
    return readdirSync(drmPath, { withFileTypes: true })
      .filter((entry) => /^card\d+$/.test(entry.name))
      .flatMap((entry) => {
        const sysfsPath = path.join(drmPath, entry.name, 'device');
        try {
          const devicePath = realpathSync(sysfsPath);
          const addresses = pciAddresses(devicePath);
          const pciAddress = addresses.at(-1) || '';
          if (!pciAddress) return [];
          const renderNode = readdirSync(path.join(sysfsPath, 'drm'))
            .find((name) => /^renderD\d+$/.test(name));

          return [{
            bootVga: readText(path.join(sysfsPath, 'boot_vga')) === '1',
            card: entry.name,
            deviceId: readText(path.join(sysfsPath, 'device')),
            devicePath,
            pciAddress,
            renderNodePath: renderNode ? path.join('/dev/dri', renderNode) : '',
            subsystemVendorId: readText(path.join(sysfsPath, 'subsystem_vendor')),
            topology: addresses,
            vendorId: readText(path.join(sysfsPath, 'vendor'))
          }];
        } catch {
          return [];
        }
      });
  } catch {
    return [];
  }
}

export function selectLinuxIntegratedGpu(devices = []) {
  const candidates = devices
    .map((device) => {
      const address = String(device.pciAddress || '').toLowerCase();
      const topology = Array.isArray(device.topology) ? device.topology : [];
      const intelIntegrated = device.vendorId === '0x8086' && /:00:02\.[0-7]$/.test(address);
      const amdApu = device.vendorId === '0x1002' &&
        topology.slice(0, -1).some((ancestor) => /:00:08\.[0-7]$/.test(ancestor));
      if (!intelIntegrated && !amdApu) return null;

      return {
        ...device,
        score: 100 +
          (device.subsystemVendorId && device.subsystemVendorId !== device.vendorId ? 4 : 0) +
          (device.bootVga ? 0 : 2) -
          topology.length
      };
    })
    .filter(Boolean)
    .sort((left, right) => right.score - left.score);

  return candidates[0] || null;
}

export function linuxDriPrimeSelector(device) {
  const address = String(device?.pciAddress || '').toLowerCase();
  return /^[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\.[0-7]$/.test(address)
    ? `pci-${address.replace(/[.:]/g, '_')}`
    : '';
}

export function linuxRenderNodePath(device) {
  const renderNodePath = String(device?.renderNodePath || '');
  return /^\/dev\/dri\/renderD\d+$/.test(renderNodePath) ? renderNodePath : '';
}

export function readStoredGraphicsMode(filePath) {
  try {
    const stored = JSON.parse(readFileSync(filePath, 'utf8'));
    return normalizeGraphicsMode(stored?.graphicsMode);
  } catch {
    return GRAPHICS_MODES.AUTOMATIC;
  }
}

export function writeStoredGraphicsMode(filePath, graphicsMode) {
  const normalized = normalizeGraphicsMode(graphicsMode);
  const temporaryPath = `${filePath}.${process.pid}.tmp`;

  mkdirSync(path.dirname(filePath), { recursive: true });
  try {
    writeFileSync(temporaryPath, `${JSON.stringify({ graphicsMode: normalized }, null, 2)}\n`, {
      encoding: 'utf8',
      mode: 0o600
    });
    renameSync(temporaryPath, filePath);
  } catch (error) {
    try {
      unlinkSync(temporaryPath);
    } catch {
      // The temporary file may not have been created.
    }
    throw error;
  }

  return normalized;
}

export function configureLinuxIntegratedGpuEnvironment(device, environment = process.env) {
  for (const name of LINUX_GPU_OFFLOAD_ENVIRONMENT) {
    delete environment[name];
  }
  const selector = linuxDriPrimeSelector(device);
  if (selector) environment.DRI_PRIME = selector;
  else delete environment.DRI_PRIME;
  return selector;
}

function captureLinuxGpuEnvironment(environment) {
  return new Map(LINUX_GPU_ENVIRONMENT.map((name) => [
    name,
    {
      present: Object.hasOwn(environment, name),
      value: environment[name]
    }
  ]));
}

function restoreLinuxGpuEnvironment(environment, snapshot) {
  for (const [name, original] of snapshot) {
    if (original.present) environment[name] = original.value;
    else delete environment[name];
  }
}

export function applyStartupGraphicsMode({
  app,
  environment = process.env,
  graphicsMode,
  linuxIntegratedGpu = null,
  platform = process.platform
}) {
  const normalized = normalizeGraphicsMode(graphicsMode);

  if (normalized === GRAPHICS_MODES.INTEGRATED) {
    const supported = platform === 'linux'
      ? Boolean(
          linuxDriPrimeSelector(linuxIntegratedGpu) &&
          linuxRenderNodePath(linuxIntegratedGpu)
        )
      : integratedGpuSelectionSupported(platform);
    if (!supported) return GRAPHICS_MODES.AUTOMATIC;

    if (platform === 'linux') {
      configureLinuxIntegratedGpuEnvironment(linuxIntegratedGpu, environment);
      app.commandLine.appendSwitch(
        'render-node-override',
        linuxRenderNodePath(linuxIntegratedGpu)
      );
    }
    app.commandLine.appendSwitch('force_low_power_gpu');
    return GRAPHICS_MODES.INTEGRATED;
  }

  return GRAPHICS_MODES.AUTOMATIC;
}

export function createGraphicsModeController({
  app,
  filePath,
  environment = process.env,
  linuxGraphicsDevices,
  platform = process.platform
}) {
  const originalLinuxGpuEnvironment = platform === 'linux'
    ? captureLinuxGpuEnvironment(environment)
    : null;
  const linuxIntegratedGpu = platform === 'linux'
    ? selectLinuxIntegratedGpu(linuxGraphicsDevices || readLinuxGraphicsDevices())
    : null;
  const integratedGpuSupported = platform === 'linux'
    ? Boolean(
        linuxDriPrimeSelector(linuxIntegratedGpu) &&
        linuxRenderNodePath(linuxIntegratedGpu)
      )
    : integratedGpuSelectionSupported(platform);
  let selectedMode = readStoredGraphicsMode(filePath);
  const appliedMode = applyStartupGraphicsMode({
    app,
    environment,
    graphicsMode: selectedMode,
    linuxIntegratedGpu,
    platform
  });

  function state() {
    return {
      appliedMode,
      integratedGpuDevice: linuxIntegratedGpu
        ? `${linuxIntegratedGpu.vendorId.replace(/^0x/, '')}:${linuxIntegratedGpu.deviceId.replace(/^0x/, '')}@${linuxIntegratedGpu.pciAddress}`
        : '',
      integratedGpuSupported,
      platform,
      restartRequired: selectedMode !== appliedMode,
      selectedMode
    };
  }

  return {
    state,
    setMode(value) {
      const normalized = normalizeGraphicsMode(value);
      const nextMode = integratedGpuSupported ||
        normalized !== GRAPHICS_MODES.INTEGRATED
        ? normalized
        : GRAPHICS_MODES.AUTOMATIC;
      writeStoredGraphicsMode(filePath, nextMode);
      selectedMode = nextMode;
      return state();
    },
    restart() {
      if (platform === 'linux' &&
          selectedMode === GRAPHICS_MODES.AUTOMATIC &&
          originalLinuxGpuEnvironment) {
        restoreLinuxGpuEnvironment(environment, originalLinuxGpuEnvironment);
      }
      app.relaunch();
      app.quit();
    }
  };
}
