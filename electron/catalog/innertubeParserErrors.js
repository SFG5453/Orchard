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

// Replaces YouTube.js's default parser error handler.
//
// The stock handler builds its "please report this" warnings from
// `packageInfo.bugs.url`, read out of youtubei.js's own package.json. When
// electron-builder packs dependencies into the asar it strips non-essential
// package.json fields, `bugs` among them, so that read throws
// "Cannot read properties of undefined (reading 'url')" — inside the handler
// that only exists to log a warning. The parse then fails outright, which is
// why searches broke in packaged builds but never in a dev run, and only for
// queries whose results happened to contain a renderer YouTube.js does not
// recognize yet.
import { Parser } from 'youtubei.js';

const TAG = '[youtubei.js/parser]';

export function installInnertubeParserErrorHandler(log = console.warn) {
  Parser.setParserErrorHandler(({ classname, ...context }) => {
    switch (context.error_type) {
      case 'parse':
        log(`${TAG} Something went wrong at ${classname}.`, context.error);
        break;
      case 'typecheck':
        log(`${TAG} Type mismatch, got ${classname} expected ${
          Array.isArray(context.expected) ? context.expected.join(' | ') : context.expected
        }.`);
        break;
      case 'mutation_data_missing':
        log(`${TAG} Mutation data required for processing ${classname}, but none found.`);
        break;
      case 'mutation_data_invalid':
        log(`${TAG} Mutation data missing or invalid for ${context.failed} out of ${context.total} MusicMultiSelectMenuItems.`);
        break;
      case 'class_not_found':
        log(`${TAG} ${classname} not found; a runtime class was generated in its place.`);
        break;
      case 'class_changed':
        log(`${TAG} ${classname} changed; altered keys: ${
          (context.changed_keys || []).map(([key]) => key).join(', ')
        }.`);
        break;
      default:
        log(`${TAG} ${classname} raised ${context.error_type}.`);
        break;
    }
  });
}
