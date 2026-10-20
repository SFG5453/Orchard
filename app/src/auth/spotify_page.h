/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>

// Shared by the WebKitGTK and WebView2 Spotify helpers.
namespace spotify_page {

// Spotify serves its web player to current desktop browsers only.
inline constexpr auto kBrowserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0";
inline constexpr int kTokenTimeoutMs = 25000;

// The web player trades its cookie for a signed token; read that answer as it
// arrives instead of forging Spotify's request signature. Runs before page code.
inline constexpr auto kTokenHook = R"JS(
(() => {
  const keep = (data) => {
    window.__orchardSpotifyTokenSeen = data && data.isAnonymous === true ? 'anonymous' : 'signed-in';
    if (data && data.accessToken && data.isAnonymous !== true)
      window.__orchardSpotifyToken = {
        accessToken: data.accessToken,
        expiresAt: data.accessTokenExpirationTimestampMs || 0
      };
  };
  const isToken = (url) => /\/api\/token(?:\?|$)/.test(String(url || ''));
  // The player passes a URL object, which has no .url; String() gives its href.
  const urlOf = (input) => typeof input === 'string' ? input : (input && input.url) || String(input);
  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input) {
      const pending = originalFetch.apply(this, arguments);
      try {
        if (isToken(urlOf(input)))
          pending.then((response) => response.clone().json()).then(keep, () => {});
      } catch (_) {}
      return pending;
    };
  }
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (isToken(url))
      this.addEventListener('load', () => { try { keep(JSON.parse(this.responseText)); } catch (_) {} });
    return open.apply(this, arguments);
  };
})();
)JS";

// Older players embedded the first token in the page instead of fetching it.
inline constexpr auto kTokenRead = R"JS(
JSON.stringify(window.__orchardSpotifyToken || (() => {
  try {
    const node = document.getElementById('session');
    const data = node && JSON.parse(node.textContent);
    return data && data.accessToken && data.isAnonymous !== true
      ? { accessToken: data.accessToken, expiresAt: data.accessTokenExpirationTimestampMs || 0 }
      : null;
  } catch (_) {
    return null;
  }
})())
)JS";

// The parent writes {spdc} as one line on stdin.
inline QString readCookie() {
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return {};
  return QJsonDocument::fromJson(input.readLine(16 * 1024)).object().value(QStringLiteral("spdc")).toString();
}

} // namespace spotify_page
