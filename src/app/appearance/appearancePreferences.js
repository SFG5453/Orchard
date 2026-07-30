import {
  GRAPHICS_MODES,
  GRAPHICS_MODE_OPTIONS,
  normalizeGraphicsMode
} from '../../../shared/graphicsMode.js';

export const APPEARANCE_DEFAULTS = {
  accentColorSource: 'artwork',
  customAccentColor: '#2fdf93',
  graphicsMode: GRAPHICS_MODES.AUTOMATIC,
  immersiveBackgroundIntensity: 'balanced',
  immersiveBackgroundMotion: 'animated',
  layoutPreset: 'grove',
  themePreference: 'dark'
};

export { GRAPHICS_MODE_OPTIONS, normalizeGraphicsMode };

export const ACCENT_COLOR_SOURCE_OPTIONS = [
  { label: 'Artwork', value: 'artwork' },
  { label: 'Orchard', value: 'orchard' },
  { label: 'Custom', value: 'custom' }
];

export const IMMERSIVE_BACKGROUND_INTENSITY_OPTIONS = [
  { label: 'Subtle', value: 'subtle', opacity: 0.48 },
  { label: 'Balanced', value: 'balanced', opacity: 0.82 },
  { label: 'Vivid', value: 'vivid', opacity: 1 }
];

export const IMMERSIVE_BACKGROUND_MOTION_OPTIONS = [
  { label: 'Animated artwork', value: 'animated' },
  { label: 'Artwork warp', value: 'static' }
];

/*
 * Layout presets carry codenames so a preset can be talked about (and reported in
 * bugs) without pinning it to "old" and "new" as more shapes land.
 *   Grove  - the original sidebar + floating player island shell.
 *   Canopy - the 4.0.0 redesign: docked player, sticky compact header, centered content.
 */
export const LAYOUT_PRESET_OPTIONS = [
  { label: 'Grove', value: 'grove', description: 'The classic Orchard shell: roomy headers and a floating player island.' },
  { label: 'Canopy', value: 'canopy', description: 'The 4.0.0 redesign: denser rails, a sticky compact header, and a docked player.' }
];

export const THEME_PREFERENCE_OPTIONS = [
  { label: 'Dark', value: 'dark' },
  { label: 'OLED', value: 'oled' },
  { label: 'System', value: 'system' }
];

function optionValue(options, value, fallback) {
  return options.some((option) => option.value === value) ? value : fallback;
}

export function normalizeAccentColorSource(value) {
  return optionValue(ACCENT_COLOR_SOURCE_OPTIONS, value, APPEARANCE_DEFAULTS.accentColorSource);
}

export function normalizeCustomAccentColor(value) {
  const color = String(value || '').trim();
  return /^#[0-9a-f]{6}$/i.test(color) ? color.toLowerCase() : APPEARANCE_DEFAULTS.customAccentColor;
}

export function normalizeImmersiveBackgroundIntensity(value) {
  return optionValue(
    IMMERSIVE_BACKGROUND_INTENSITY_OPTIONS,
    value,
    APPEARANCE_DEFAULTS.immersiveBackgroundIntensity
  );
}

export function normalizeImmersiveBackgroundMotion(value) {
  return optionValue(
    IMMERSIVE_BACKGROUND_MOTION_OPTIONS,
    value,
    APPEARANCE_DEFAULTS.immersiveBackgroundMotion
  );
}

export function normalizeLayoutPreset(value) {
  return optionValue(LAYOUT_PRESET_OPTIONS, value, APPEARANCE_DEFAULTS.layoutPreset);
}

export function normalizeThemePreference(value) {
  return optionValue(THEME_PREFERENCE_OPTIONS, value, APPEARANCE_DEFAULTS.themePreference);
}

export function immersiveBackgroundOpacity(value) {
  return IMMERSIVE_BACKGROUND_INTENSITY_OPTIONS.find((option) => option.value === value)?.opacity ?? 0.82;
}

export function hexColorToRgb(value) {
  const color = normalizeCustomAccentColor(value).slice(1);
  return [0, 2, 4].map((offset) => Number.parseInt(color.slice(offset, offset + 2), 16));
}

export function resolvedTheme(preference, systemPrefersDark = true) {
  if (preference !== 'system') return preference;
  return systemPrefersDark ? 'dark' : 'light';
}
