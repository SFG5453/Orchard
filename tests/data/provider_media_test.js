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

// Test-only bundle: drives the host's binary results and media crypto directly.
const hex = (value) => Uint8Array.from(String(value).match(/../g) || [], (pair) => parseInt(pair, 16));

globalThis.OrchardMediaTest = Object.freeze({
  async invoke(method, payload = {}) {
    switch (method) {
    case 'hkdf':
      return __orchardHkdfSha256(hex(payload.key), hex(payload.salt), hex(payload.info), payload.length);
    case 'aes':
      return __orchardAes128(payload.mode, hex(payload.key), hex(payload.iv), hex(payload.data));
    case 'fetchBytes': {
      const response = await fetch(payload.url);
      const bytes = new Uint8Array(await response.arrayBuffer());
      // A view into a larger buffer must still arrive as just its own bytes.
      return payload.offset ? bytes.subarray(payload.offset) : bytes;
    }
    case 'json':
      return { bytes: [1, 2] };
    default:
      throw new Error(`Unknown test method: ${method}`);
    }
  }
});
