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

#include <string.h>

#include "yte.h"

#define PARSE_ERROR "Could not parse YouTube player JavaScript"

static Expr fn_rest(P *p, uint32_t start, bool is_async, bool gen, const Name *own) {
  scope_open(p, true);
  if (own) scope_declare(p, *own, false);
  FnCtx ctx = {0};
  ctx.async = is_async;
  ctx.gen = gen;
  VPUSH(p, p->fns, ctx);
  p->fn_depth++;
  parse_params(p, &ctx);
  uint32_t open, close;
  parse_function_body(p, &ctx, start, &open, &close);
  p->fn_depth--;
  p->fns.n--;
  scope_close(p);
  Expr e = {0};
  e.start = start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_FUNC;
  e.safe = true;
  e.ref = -1;
  e.sigfn = ctx.nparams >= 3 && ctx.p0_plain && ctx.p1_init && ctx.p2_init && ctx.ctor && ctx.alr;
  e.fn_start = start;
  e.body_start = open;
  e.body_end = close;
  return e;
}

Expr parse_function(P *p, uint32_t start, bool is_async, bool declaration) {
  lex_next(p);
  bool gen = eat(p, P_STAR);
  Name name = {0};
  bool named = p->t.type == T_IDENT;
  if (named) {
    name = tok_name(p);
    lex_next(p);
  }
  if (declaration && named) {
    // Sloppy-mode block functions also bind in the enclosing function.
    scope_declare(p, name, false);
    scope_declare(p, name, true);
  }
  return fn_rest(p, start, is_async, gen, !declaration && named ? &name : NULL);
}

Expr parse_method(P *p, uint32_t start, bool is_async, bool gen) {
  return fn_rest(p, start, is_async, gen, NULL);
}

static void parse_default(P *p) {
  uint32_t m = cand_mark(p);
  parse_assign(p, false);
  cand_cut(p, m);
}

void parse_params(P *p, FnCtx *ctx) {
  expect(p, P_LPAREN);
  while (!is_punct(p, P_RPAREN)) {
    if (eat(p, P_ELLIPSIS)) {
      parse_binding(p, 0);
      eat(p, P_COMMA);
      continue;
    }
    uint16_t i = ctx->nparams++;
    bool simple = p->t.type == T_IDENT;
    Name n = tok_name(p);
    parse_binding(p, 0);
    bool init = eat(p, P_ASSIGN);
    if (init) parse_default(p);
    if (i == 0) {
      ctx->p0_simple = simple;
      ctx->p0 = n;
      ctx->p0_plain = simple && !init;
    }
    if (i == 1) ctx->p1_init = init;
    if (i == 2) ctx->p2_init = init;
    if (!eat(p, P_COMMA)) break;
  }
  expect(p, P_RPAREN);
}

void parse_binding(P *p, int kind) {
  enter(p);
  if (p->t.type == T_IDENT) {
    scope_declare(p, tok_name(p), kind == 1);
    lex_next(p);
  } else if (eat(p, P_LBRACK)) {
    while (!is_punct(p, P_RBRACK)) {
      if (eat(p, P_COMMA)) continue;
      if (eat(p, P_ELLIPSIS)) {
        parse_binding(p, kind);
      } else {
        parse_binding(p, kind);
        if (eat(p, P_ASSIGN)) parse_default(p);
      }
      if (!eat(p, P_COMMA)) break;
    }
    expect(p, P_RBRACK);
  } else if (eat(p, P_LBRACE)) {
    while (!is_punct(p, P_RBRACE)) {
      if (eat(p, P_ELLIPSIS)) {
        parse_binding(p, kind);
      } else {
        bool computed, is_string;
        Tok key_tok = p->t;
        Name key = property_key(p, &computed, &is_string);
        if (eat(p, P_COLON)) {
          parse_binding(p, kind);
        } else {
          if (key_tok.type != T_IDENT || computed) yte_fail(p, PARSE_ERROR);
          scope_declare(p, key, kind == 1);
        }
        if (eat(p, P_ASSIGN)) parse_default(p);
      }
      if (!eat(p, P_COMMA)) break;
    }
    expect(p, P_RBRACE);
  } else {
    yte_fail(p, PARSE_ERROR);
  }
  leave(p);
}

Name property_key(P *p, bool *computed, bool *is_string) {
  *computed = *is_string = false;
  Name n = tok_name(p);
  switch (p->t.type) {
  case T_IDENT: case T_PRIVATE: case T_NUM: case T_BIGINT:
    lex_next(p);
    return n;
  case T_STR:
    *is_string = true;
    lex_next(p);
    return (Name){n.p + 1, n.n - 2};
  }
  if (!eat(p, P_LBRACK)) yte_fail(p, PARSE_ERROR);
  parse_default(p);
  expect(p, P_RBRACK);
  *computed = true;
  return (Name){0};
}

static void static_block(P *p, uint32_t start) {
  scope_open(p, true);
  FnCtx ctx = {0};
  VPUSH(p, p->fns, ctx);
  p->fn_depth++;
  uint32_t open, close;
  parse_function_body(p, &ctx, start, &open, &close);
  p->fn_depth--;
  p->fns.n--;
  scope_close(p);
}

static bool member_modifier(P *p, bool *accessor, bool *is_async, bool *field_accessor) {
  if (p->t.type != T_IDENT || p->t.esc) return false;
  int kw = p->t.kw;
  if (kw != KW_GET && kw != KW_SET && kw != KW_ASYNC && kw != KW_ACCESSOR) return false;
  Tok n = lex_peek(p, NULL);
  if (!key_follows(n, kw == KW_ASYNC) || (n.nl && kw != KW_GET && kw != KW_SET)) return false;
  *accessor = kw == KW_GET || kw == KW_SET;
  *is_async = kw == KW_ASYNC;
  *field_accessor = kw == KW_ACCESSOR;
  lex_next(p);
  return true;
}

Expr parse_class(P *p, uint32_t start, bool declaration) {
  lex_next(p);
  Name name = {0};
  bool named = p->t.type == T_IDENT && !is_kw(p, KW_EXTENDS);
  if (named) {
    name = tok_name(p);
    lex_next(p);
  }
  if (declaration && named) scope_declare(p, name, false);
  scope_open(p, false);
  if (named) scope_declare(p, name, false);
  bool safe = true;
  if (is_kw(p, KW_EXTENDS)) {
    lex_next(p);
    Expr h = parse_lhs_new(p);
    if (h.kind != K_IDENT && h.kind != K_STATIC) safe = false;
  }
  expect(p, P_LBRACE);
  while (!is_punct(p, P_RBRACE)) {
    if (eat(p, P_SEMI)) continue;
    uint32_t member_start = p->t.start;
    bool is_static = false;
    if (is_kw(p, KW_STATIC)) {
      Tok n = lex_peek(p, NULL);
      if (!(n.type == T_PUNCT && (n.punct == P_LPAREN || n.punct == P_ASSIGN ||
                                  n.punct == P_SEMI || n.punct == P_RBRACE))) {
        is_static = true;
        lex_next(p);
      }
    }
    if (is_static && is_punct(p, P_LBRACE)) {
      safe = false;
      static_block(p, member_start);
      continue;
    }
    bool accessor = false, is_async = false, field_accessor = false;
    member_modifier(p, &accessor, &is_async, &field_accessor);
    bool gen = eat(p, P_STAR);
    bool computed, is_string;
    property_key(p, &computed, &is_string);
    if (computed || field_accessor) safe = false;
    if (is_punct(p, P_LPAREN)) {
      parse_method(p, member_start, is_async, gen);
      continue;
    }
    if (eat(p, P_ASSIGN)) {
      Expr v = parse_assign(p, false);
      if (is_static && !v.safe) safe = false;
    }
    semicolon(p);
  }
  expect(p, P_RBRACE);
  scope_close(p);
  Expr e = {0};
  e.start = start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_CLASS;
  e.safe = safe;
  e.ref = -1;
  return e;
}

// Built-in names from the pinned YouTube.js strict initializer policy, sorted bytewise.
static const char *const kBuiltins[] = {
  "AbortController", "AbortSignal", "Array", "ArrayBuffer", "AsyncContext", "Atomics",
  "AudioContext", "BigInt", "BigInt64Array", "BigUint64Array", "Blob", "Boolean",
  "BroadcastChannel", "Buffer", "CanvasRenderingContext2D", "Crypto", "CustomEvent", "DataView",
  "Date", "Element", "Error", "Event", "EventTarget", "File", "FileReader", "Float32Array",
  "Float64Array", "FormData", "HTMLCollection", "HTMLElement", "Headers", "History",
  "IDBKeyRange", "Infinity", "Int16Array", "Int32Array", "Int8Array", "IntersectionObserver",
  "Intl", "JSON", "Map", "Math", "MediaRecorder", "MediaSource", "MediaStream",
  "MemberExpression", "MutationObserver", "NaN", "Node", "NodeList", "Number", "Object",
  "OfflineAudioContext", "Performance", "Promise", "Proxy", "ReadableStream", "Reflect",
  "RegExp", "Request", "ResizeObserver", "Response", "Screen", "SharedArrayBuffer",
  "SharedWorker", "SourceBuffer", "String", "SubtleCrypto", "Symbol", "TextDecoder",
  "TextEncoder", "TransformStream", "URL", "URLSearchParams", "Uint16Array", "Uint32Array",
  "Uint8Array", "Uint8ClampedArray", "WeakMap", "WeakSet", "WebAssembly",
  "WebGLRenderingContext", "Worker", "WritableStream", "XMLHttpRequest", "alert", "arguments",
  "atob", "btoa", "cancelAnimationFrame", "cancelIdleCallback", "clearImmediate",
  "clearInterval", "clearTimeout", "confirm", "console", "decodeURI", "decodeURIComponent",
  "document", "encodeURI", "encodeURIComponent", "escape", "eval", "fetch", "function", "global",
  "globalThis", "hasOwnProperty", "isFinite", "isNaN", "isPrototypeOf", "location", "log",
  "navigator", "parse", "parseFloat", "parseInt", "process", "prompt", "prototype",
  "queueMicrotask", "requestAnimationFrame", "requestIdleCallback", "setImmediate",
  "setInterval", "setTimeout", "split", "stringify", "structuredClone", "this", "toString",
  "undefined", "unescape", "valueOf", "window",
};

bool builtin(Name n) {
  size_t lo = 0, hi = sizeof kBuiltins / sizeof *kBuiltins;
  while (lo < hi) {
    size_t mid = (lo + hi) / 2;
    const char *s = kBuiltins[mid];
    size_t len = strlen(s);
    int c = memcmp(n.p, s, n.n < len ? n.n : len);
    if (c == 0) c = n.n < len ? -1 : n.n > len ? 1 : 0;
    if (c == 0) return true;
    if (c < 0) hi = mid;
    else lo = mid + 1;
  }
  return false;
}
