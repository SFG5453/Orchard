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

export function normalizeYouTubeAuthCookie(cookie = '') {
  const normalized = String(cookie || '').trim();
  const cookies = parseCookieString(normalized);
  if (!normalized || cookies.SAPISID || !cookies['__Secure-3PAPISID']) return normalized;
  return `${normalized}; SAPISID=${cookies['__Secure-3PAPISID']}`;
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
