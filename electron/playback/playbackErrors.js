function errorText(error) {
  let info = '';
  if (typeof error?.info === 'string') info = error.info;
  else if (error?.info) {
    try {
      info = JSON.stringify(error.info);
    } catch {
      info = '';
    }
  }
  return `${error?.message || ''} ${info}`;
}

function responseStatus(error) {
  const direct = Number(error?.status);
  if (Number.isFinite(direct) && direct > 0) return direct;
  return Number(/\bstatus(?: code)?\s+(\d{3})\b/i.exec(errorText(error))?.[1]) || 0;
}

export function isAgeGatePlaybackError(error) {
  return /sign in to confirm your age|confirm your age|age[- ]restricted/i.test(errorText(error));
}

export function isBotCheckPlaybackError(error) {
  return /sign in to confirm (?:you(?:'|’)?re|you are) not a bot|not a bot|unusual traffic/i.test(errorText(error));
}

export function isPrivatePlaybackError(error) {
  return /(?:this )?video (?:is|has been set to) private|private video/i.test(errorText(error));
}

export function canFallbackToGuest(error) {
  const status = responseStatus(error);
  return isAgeGatePlaybackError(error) ||
    isBotCheckPlaybackError(error) ||
    status === 401 ||
    status === 403 ||
    /missing required authentication credential|no playable|playability|stream failed|validated stream/i.test(errorText(error));
}
