export const APPEARANCE_DEFAULTS = {
  accentColorSource: 'artwork',
  customAccentColor: '#2fdf93',
  immersiveBackgroundIntensity: 'balanced',
  immersiveBackgroundMotion: 'animated',
  themePreference: 'dark'
};

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
  { label: 'Animated', value: 'animated' },
  { label: 'Static', value: 'static' }
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
