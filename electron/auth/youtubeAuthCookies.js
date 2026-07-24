// Parses and collects the minimum YouTube cookie state needed by browser-backed authentication.
export function parseCookieString(cookie = '') {
  return String(cookie || '')
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .reduce((cookies, part) => {
      const separatorIndex = part.indexOf('=');
      if (separatorIndex <= 0) return cookies;
      cookies[part.slice(0, separatorIndex)] = part.slice(separatorIndex + 1);
      return cookies;
    }, {});
}

export function hasYouTubeLoginCookie(cookie = '') {
  const cookies = parseCookieString(cookie);
  return Boolean(cookies.SAPISID || cookies['__Secure-3PAPISID']);
}

export function youtubeAccountIdentity(cookie = '', dataSyncId = '') {
  const cookies = parseCookieString(cookie);
  const signingCookie = cookies.SAPISID || cookies['__Secure-3PAPISID'] || '';
  return `${String(dataSyncId || '').trim()}\n${signingCookie}`;
}

export function normalizeYouTubeAuthCookie(cookie = '') {
  const normalized = String(cookie || '').trim();
  const cookies = parseCookieString(normalized);
  if (!normalized || cookies.SAPISID || !cookies['__Secure-3PAPISID']) return normalized;
  return `${normalized}; SAPISID=${cookies['__Secure-3PAPISID']}`;
}

export function delegatedSessionIdFromPageAuth({ dataSyncId = '', delegatedSessionId = '' } = {}) {
  const explicit = String(delegatedSessionId || '').trim();
  if (explicit) return explicit;

  const normalized = String(dataSyncId || '').trim();
  const separatorIndex = normalized.indexOf('||');
  if (separatorIndex <= 0 || !normalized.slice(separatorIndex + 2)) return '';
  return normalized.slice(0, separatorIndex);
}

export function accountIndexFromPageAuth(value) {
  const normalized = String(value ?? '').trim();
  if (!/^\d+$/.test(normalized)) return 0;
  return Number.parseInt(normalized, 10);
}

export async function collectYouTubeAuthCookie(authSession) {
  const cookieParts = new Map();
  const urls = ['https://music.youtube.com', 'https://www.youtube.com', 'https://youtube.com'];

  for (const url of urls) {
    const cookies = await authSession.cookies.get({ url });
    for (const cookie of cookies) cookieParts.set(cookie.name, cookie.value);
  }

  const cookie = normalizeYouTubeAuthCookie(
    [...cookieParts.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
  );
  if (hasYouTubeLoginCookie(cookie)) await authSession.cookies.flushStore();
  return cookie;
}
