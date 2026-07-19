function positiveSeconds(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

export function durationLabelSeconds(value) {
  const parts = String(value || '').trim().split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => (total * 60) + part, 0);
}

function seekableEndSeconds(media) {
  const seekable = media?.seekable;
  if (!seekable?.length) return 0;
  try {
    return positiveSeconds(seekable.end(seekable.length - 1));
  } catch {
    return 0;
  }
}

export function reliablePlaybackDuration(ctx, media, track = ctx.activeTrack.value) {
  return Math.max(
    positiveSeconds(media?.duration),
    positiveSeconds(track?.durationSeconds),
    durationLabelSeconds(track?.duration),
    seekableEndSeconds(media)
  );
}

export async function resumeMediaAt(media, seconds) {
  const target = positiveSeconds(seconds);
  if (!media || !target) return;
  if (media.readyState === 0) {
    await new Promise((resolve) => {
      media.addEventListener('loadedmetadata', resolve, { once: true });
      media.addEventListener('error', resolve, { once: true });
    });
  }
  if (typeof media.fastSeek === 'function') media.fastSeek(target);
  else media.currentTime = target;
}
