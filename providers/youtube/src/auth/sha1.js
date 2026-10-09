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

/** ECMAScript-only SHA-1 for YouTube SAPISID request authentication. */
function utf8(value) {
  const output = [];
  for (const character of String(value)) {
    const code = character.codePointAt(0);
    if (code < 0x80) output.push(code);
    else if (code < 0x800) output.push(0xc0 | (code >>> 6), 0x80 | (code & 0x3f));
    else if (code < 0x10000) output.push(0xe0 | (code >>> 12), 0x80 | ((code >>> 6) & 0x3f), 0x80 | (code & 0x3f));
    else output.push(0xf0 | (code >>> 18), 0x80 | ((code >>> 12) & 0x3f), 0x80 | ((code >>> 6) & 0x3f), 0x80 | (code & 0x3f));
  }
  return output;
}

function rotate(value, count) {
  return ((value << count) | (value >>> (32 - count))) >>> 0;
}

export function sha1Hex(input) {
  const message = utf8(input);
  const bitLength = BigInt(message.length) * 8n;
  message.push(0x80);
  while (message.length % 64 !== 56) message.push(0);
  for (let index = 7; index >= 0; index -= 1) {
    message.push(Number((bitLength >> BigInt(index * 8)) & 0xffn));
  }

  let h0 = 0x67452301;
  let h1 = 0xefcdab89;
  let h2 = 0x98badcfe;
  let h3 = 0x10325476;
  let h4 = 0xc3d2e1f0;

  for (let offset = 0; offset < message.length; offset += 64) {
    const words = new Uint32Array(80);
    for (let index = 0; index < 16; index += 1) {
      const start = offset + index * 4;
      words[index] = ((message[start] << 24) | (message[start + 1] << 16) |
        (message[start + 2] << 8) | message[start + 3]) >>> 0;
    }
    for (let index = 16; index < 80; index += 1) {
      words[index] = rotate(words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16], 1);
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let index = 0; index < 80; index += 1) {
      let f;
      let k;
      if (index < 20) {
        f = (b & c) | (~b & d);
        k = 0x5a827999;
      } else if (index < 40) {
        f = b ^ c ^ d;
        k = 0x6ed9eba1;
      } else if (index < 60) {
        f = (b & c) | (b & d) | (c & d);
        k = 0x8f1bbcdc;
      } else {
        f = b ^ c ^ d;
        k = 0xca62c1d6;
      }
      const next = (rotate(a, 5) + f + e + k + words[index]) >>> 0;
      e = d;
      d = c;
      c = rotate(b, 30);
      b = a;
      a = next;
    }
    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
  }

  return [h0, h1, h2, h3, h4].map((value) => value.toString(16).padStart(8, '0')).join('');
}

