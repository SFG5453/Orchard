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

import assert from 'node:assert/strict';
import test from 'node:test';
import { Parser, YTNodes } from 'youtubei.js';
import { installInnertubeParserErrorHandler } from '../electron/catalog/innertubeParserErrors.js';

// YouTube.js caches generated classes by name, so each case needs its own.
let rendererCounter = 0;

function unknownRenderer() {
  rendererCounter += 1;
  return {
    [`orchardUnknown${rendererCounter}Renderer`]: {
      title: { runs: [{ text: 'Some song' }] }
    }
  };
}

function captureWarnings() {
  const warnings = [];
  installInnertubeParserErrorHandler((...args) => warnings.push(args.join(' ')));
  return warnings;
}

// A search response carrying a renderer YouTube.js does not recognize is what
// takes the parser down this path. In a minimal production package the stock
// handler can throw here when it formats its warning from a package.json `bugs`
// field that is absent, so the whole search fails instead of the
// unknown item being skipped.
test('an unrecognized renderer warns instead of failing the parse', () => {
  const warnings = captureWarnings();

  assert.doesNotThrow(() => Parser.parseItem(unknownRenderer()));
  assert.ok(
    warnings.some((warning) => /OrchardUnknown/i.test(warning)),
    `expected a class_not_found warning, got ${JSON.stringify(warnings)}`
  );
});

test('a renderer of an unexpected type warns instead of failing the parse', () => {
  const warnings = captureWarnings();

  assert.doesNotThrow(() => Parser.parseItem(unknownRenderer(), YTNodes.MusicResponsiveListItem));
  assert.ok(
    warnings.some((warning) => /expected MusicResponsiveListItem/.test(warning)),
    `expected a typecheck warning, got ${JSON.stringify(warnings)}`
  );
});

test('a renderer that throws while parsing warns instead of failing the parse', () => {
  const warnings = captureWarnings();

  // Text nodes are constructed eagerly; a non-object where runs are expected
  // makes the generated class throw.
  assert.doesNotThrow(() => Parser.parseItem({ musicResponsiveListItemRenderer: { flexColumns: 7 } }));
  assert.ok(
    warnings.some((warning) => /MusicResponsiveListItem/.test(warning)),
    `expected a parse warning, got ${JSON.stringify(warnings)}`
  );
});
