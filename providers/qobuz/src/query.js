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

// QuickJS has neither URL nor URLSearchParams; these cover what the provider needs.

function encode(value) {
  return encodeURIComponent(String(value)).replace(/%20/g, '+');
}

function decode(value) {
  try {
    return decodeURIComponent(String(value).replace(/\+/g, ' '));
  } catch {
    return String(value);
  }
}

/** Form-encodes an object or [key, value] pairs, as URLSearchParams#toString does. */
export function formEncode(params = {}) {
  const entries = Array.isArray(params) ? params : Object.entries(params);
  return entries.map(([key, value]) => `${encode(key)}=${encode(value)}`).join('&');
}

/** Query parameters of an absolute URL; the last value of a repeated key wins. */
export function queryParams(url) {
  const query = String(url || '').split('#')[0].split('?').slice(1).join('?');
  const params = {};
  for (const part of query.split('&')) {
    if (!part) continue;
    const separator = part.indexOf('=');
    const key = decode(separator < 0 ? part : part.slice(0, separator));
    params[key] = separator < 0 ? '' : decode(part.slice(separator + 1));
  }
  return params;
}
