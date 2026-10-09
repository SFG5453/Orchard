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

import encoding from 'fast-text-encoding';
import { AbortController, AbortSignal } from 'abort-controller/dist/abort-controller.mjs';

// JSDOM is JavaScript data structures, not a renderer. Its bundled dependencies
// need these web globals before they initialize in the embedded QuickJS realm.
globalThis.global = globalThis;
globalThis.self = globalThis;
globalThis.navigator ||= { userAgent: 'QuickJS' };
globalThis.TextEncoder ||= encoding.TextEncoder;
globalThis.TextDecoder ||= encoding.TextDecoder;
globalThis.AbortController ||= AbortController;
globalThis.AbortSignal ||= AbortSignal;
globalThis.queueMicrotask ||= callback => Promise.resolve().then(callback);
globalThis.console ||= { log() {}, info() {}, warn() {}, error() {}, debug() {} };
