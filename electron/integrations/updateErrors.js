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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Turns an update failure into something the listener can act on.
 *
 * The update check runs on Electron's `net`, so its failures arrive as bare
 * Chromium `net::ERR_*` codes. The proxy ones matter most: the same stack serves
 * album art while playback runs on Node's, which ignores the proxy entirely, so
 * an unreachable proxy presents as "updates and artwork are broken but music
 * plays" with nothing on screen pointing at the machine's own settings.
 */
const PROXY_ERROR_PATTERN = /ERR_(?:TUNNEL_CONNECTION_FAILED|PROXY_CONNECTION_FAILED|PROXY_AUTH_UNSUPPORTED|MANDATORY_PROXY_CONFIGURATION_FAILED|PROXY_CERTIFICATE_INVALID)/i;

export function errorText(error) {
  if (!error) return '';
  if (typeof error === 'string') return error.trim();
  return String(error.message || error).trim();
}

export function isProxyUpdateError(error) {
  return PROXY_ERROR_PATTERN.test(errorText(error));
}

export function updateErrorMessage(error) {
  const text = errorText(error);
  if (!text) return 'Update check failed.';
  if (!isProxyUpdateError(text)) return text;

  return `Orchard could not reach the update server through your system proxy (${text}). ` +
    'Album art is served the same way and will also be missing. Check your proxy settings, or turn ' +
    'on Settings > Network > "Ignore system proxy".';
}
