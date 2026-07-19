// Converts release dates into stable UTC-relative timing buckets and labels.
export function startOfUtcDay(time = Date.now()) {
  const date = new Date(time);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
}

export function releaseDaysFromToday(value = '') {
  const releaseTime = Date.parse(value || '');
  if (!Number.isFinite(releaseTime)) return 0;
  return Math.round((startOfUtcDay(releaseTime) - startOfUtcDay()) / 86400000);
}

export function releaseTimingForDate(value = '') {
  const days = releaseDaysFromToday(value);
  if (days === 0) return 'out_today';
  if (days > 0 && days <= 7) return 'this_week';
  if (days > 7) return 'coming_soon';
  return 'recently_released';
}

export function releaseTimingLabel(timing, days) {
  if (timing === 'out_today') return 'Out today';
  if (timing === 'this_week') return days === 1 ? 'Tomorrow' : `${days} days`;
  if (timing === 'coming_soon') return `${days} days`;
  const elapsed = Math.abs(days);
  return elapsed <= 1 ? 'Yesterday' : `${elapsed} days ago`;
}
