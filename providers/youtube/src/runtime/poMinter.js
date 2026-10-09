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

import { JSDOM } from 'jsdom';
import { createWebPoMinter } from '../playback/webPoMinter.js';
import { createYouTubePoTokenService } from '../playback/youtubePoToken.js';

// Loaded only for playback. No renderer, sockets, filesystem, or Node runtime:
// Qt owns fetch/timers, and page scripts/resource loading remain disabled.
const dom = new JSDOM('<!doctype html><html><head><title></title></head><body></body></html>', {
  url: 'https://www.youtube.com/', referrer: 'https://www.youtube.com/'
});
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  location: dom.window.location, origin: dom.window.origin
});

// One minter serves many songs. No Chromium clown car at every track change.
globalThis.OrchardYouTubePoToken = createYouTubePoTokenService({ createMinter: createWebPoMinter });
