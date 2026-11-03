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


export function continuationTokenFromEndpoint(endpoint) {
  return endpoint?.continuationCommand?.token ||
    endpoint?.command?.continuationCommand?.token ||
    endpoint?.payload?.continuation ||
    null;
}

export function continuationTokenFromContinuations(continuations = []) {
  return continuations
    .map((entry) =>
      entry?.nextContinuationData?.continuation ||
      entry?.reloadContinuationData?.continuation ||
      entry?.nextRadioContinuationData?.continuation
    )
    .find(Boolean) || null;
}

export function playlistContinuationTokenFromItems(items = []) {
  for (const item of [...items].reverse()) {
    const renderer = item?.continuationItemRenderer;
    const token = renderer?.continuationEndpoint?.continuationCommand?.token ||
      continuationTokenFromEndpoint(renderer?.continuationEndpoint);
    if (token) return token;
  }

  return null;
}
