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
// takes the parser down this path. Under electron-builder the stock handler
// throws here, because it formats its warning out of a package.json `bugs`
// field the asar packer strips, so the whole search fails instead of the
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
