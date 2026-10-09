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

export function bytes(input) {
  if (input instanceof Uint8Array) return input;
  if (input instanceof ArrayBuffer) return new Uint8Array(input);
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  if (Array.isArray(input)) return Uint8Array.from(input);
  throw new TypeError('Expected bytes, an ArrayBuffer, or an ArrayBuffer view');
}

export function concatBytes(parts) {
  const arrays = parts.map(bytes);
  const output = new Uint8Array(arrays.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of arrays) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

export function equalBytes(left, right) {
  const a = bytes(left);
  const b = bytes(right);
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

export function ascii(input, start = 0, end = bytes(input).length) {
  return String.fromCharCode(...bytes(input).subarray(start, end));
}

export function indexOfBytes(input, needle) {
  const data = bytes(input);
  const wanted = bytes(needle);
  outer: for (let offset = 0; offset + wanted.length <= data.length; offset += 1) {
    for (let index = 0; index < wanted.length; index += 1) {
      if (data[offset + index] !== wanted[index]) continue outer;
    }
    return offset;
  }
  return -1;
}

export function readU16(data, offset) {
  return new DataView(bytes(data).buffer, bytes(data).byteOffset).getUint16(offset, false);
}

export function readU24(data, offset) {
  const value = bytes(data);
  return value[offset] * 0x10000 + value[offset + 1] * 0x100 + value[offset + 2];
}

export function readU32(data, offset) {
  return new DataView(bytes(data).buffer, bytes(data).byteOffset).getUint32(offset, false);
}

export function readU64(data, offset) {
  return new DataView(bytes(data).buffer, bytes(data).byteOffset).getBigUint64(offset, false);
}

export function writeU16(data, offset, value) {
  new DataView(bytes(data).buffer, bytes(data).byteOffset).setUint16(offset, value, false);
}

export function writeU24(data, offset, value) {
  const output = bytes(data);
  output[offset] = (value >>> 16) & 0xff;
  output[offset + 1] = (value >>> 8) & 0xff;
  output[offset + 2] = value & 0xff;
}

export function writeU64(data, offset, value) {
  new DataView(bytes(data).buffer, bytes(data).byteOffset).setBigUint64(offset, BigInt(value), false);
}

export function hexToBytes(value) {
  const hex = String(value || '');
  if (hex.length % 2 || !/^[a-f\d]*$/i.test(hex)) throw new TypeError('Invalid hexadecimal value');
  return Uint8Array.from({ length: hex.length / 2 }, (_, index) =>
    Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16));
}

export function bytesToHex(input) {
  return [...bytes(input)].map((value) => value.toString(16).padStart(2, '0')).join('');
}

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function decodeBase64(input) {
  const value = String(input || '').replace(/-/g, '+').replace(/_/g, '/').replace(/\s|=/g, '');
  if (!/^[A-Za-z0-9+/]*$/.test(value) || value.length % 4 === 1) {
    throw new TypeError('Invalid base64 value');
  }
  const output = [];
  let bits = 0;
  let bitCount = 0;
  for (const character of value) {
    bits = bits * 64 + BASE64.indexOf(character);
    bitCount += 6;
    if (bitCount >= 8) {
      bitCount -= 8;
      output.push((bits >>> bitCount) & 0xff);
      bits &= (1 << bitCount) - 1;
    }
  }
  return Uint8Array.from(output);
}

export function decodeBase64Ascii(input) {
  return ascii(decodeBase64(input));
}
