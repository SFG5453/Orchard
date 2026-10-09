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

// Parsing and dependency analysis run in the Rust host. The provider receives
// only the small extracted program and compiles it in QuickJS.
export function extract(source) {
  if (typeof globalThis.__orchardExtractPlayer !== 'function') {
    throw new Error('Native YouTube player extraction is unavailable.');
  }
  const result = globalThis.__orchardExtractPlayer(source);
  if (result?.error) throw new Error(result.error);
  return result;
}
