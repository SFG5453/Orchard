export const GRAPHICS_MODES = Object.freeze({
  AUTOMATIC: 'automatic',
  INTEGRATED: 'integrated'
});

export const GRAPHICS_MODE_OPTIONS = Object.freeze([
  Object.freeze({ label: 'Automatic', value: GRAPHICS_MODES.AUTOMATIC }),
  Object.freeze({ label: 'Integrated GPU', value: GRAPHICS_MODES.INTEGRATED })
]);

export function normalizeGraphicsMode(value) {
  return GRAPHICS_MODE_OPTIONS.some((option) => option.value === value)
    ? value
    : GRAPHICS_MODES.AUTOMATIC;
}

export function integratedGpuSelectionSupported(platform = process.platform) {
  return ['darwin', 'win32'].includes(platform);
}
