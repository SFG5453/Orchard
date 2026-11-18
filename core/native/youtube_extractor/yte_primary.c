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

#include <math.h>

#include "yte.h"

#define PARSE_ERROR "Could not parse YouTube player JavaScript"

Expr expr_leaf(P *p, uint8_t kind) {
  Expr e = {0};
  e.start = p->t.start;
  e.end = p->t.end;
  e.kind = e.ikind = kind;
  e.ref = -1;
  e.safe = kind == K_IDENT || kind == K_NULL || kind == K_BOOL || kind == K_NUM ||
           kind == K_STR || kind == K_REGEX;
  lex_next(p);
  return e;
}

static Expr node(uint32_t start, uint32_t end, uint8_t kind, bool safe) {
  Expr e = {0};
  e.start = start;
  e.end = end;
  e.kind = e.ikind = kind;
  e.safe = safe;
  e.ref = -1;
  return e;
}

Expr parse_arrow_body(P *p, bool is_async, uint32_t start) {
  expect(p, P_ARROW);
  FnCtx ctx = {0};
  ctx.async = is_async;
  VPUSH(p, p->fns, ctx);
  p->fn_depth++;
  if (is_punct(p, P_LBRACE)) {
    uint32_t open, close;
    parse_function_body(p, &ctx, start, &open, &close);
  } else {
    parse_assign(p, false);
  }
  p->fn_depth--;
  p->fns.n--;
  scope_close(p);
  return node(start, p->prev_end, K_ARROW, true);
}

// Minified players never put comments between a parameter and `=>`.
static bool arrow_follows(P *p) {
  const char *s = p->src;
  uint32_t i = p->t.end;
  while (i < p->len && (s[i] == ' ' || s[i] == '\t')) i++;
  return i + 1 < p->len && s[i] == '=' && s[i + 1] == '>';
}

static Expr ident_arrow(P *p, uint32_t start, bool is_async) {
  Name name = tok_name(p);
  lex_next(p);
  scope_open(p, true);
  scope_declare(p, name, false);
  return parse_arrow_body(p, is_async, start);
}

static Expr primary_ident(P *p) {
  uint32_t start = p->t.start;
  if (!p->t.esc) {
    switch (p->t.kw) {
    case KW_THIS: return expr_leaf(p, K_THIS);
    case KW_NULL: return expr_leaf(p, K_NULL);
    case KW_TRUE: case KW_FALSE: return expr_leaf(p, K_BOOL);
    case KW_FUNCTION: return parse_function(p, start, false, false);
    case KW_CLASS: return parse_class(p, start, false);
    case KW_SUPER: return expr_leaf(p, K_SUPER);
    case KW_IMPORT: return expr_leaf(p, K_IMPORT);
    case KW_ASYNC: {
      Tok n = lex_peek(p, NULL);
      if (!n.nl && n.type == T_IDENT && n.kw == KW_FUNCTION && !n.esc) {
        lex_next(p);
        return parse_function(p, start, true, false);
      }
      if (!n.nl && n.type == T_IDENT) {
        Tok saved = p->t;
        uint32_t pos = p->pos, prev = p->prev_end;
        lex_next(p);
        lex_next(p);
        bool arrow = is_punct(p, P_ARROW) && !p->t.nl;
        p->t = saved;
        p->pos = pos;
        p->prev_end = prev;
        if (arrow) {
          lex_next(p);
          return ident_arrow(p, start, true);
        }
      }
      break;
    }
    default:
      if (p->t.kw != KW_NONE && p->t.kw < KW_RESERVED_END) yte_fail(p, PARSE_ERROR);
    }
  }
  if (arrow_follows(p)) return ident_arrow(p, start, false);
  Name name = tok_name(p);
  int32_t ref = ref_add(p, name);
  Expr e = expr_leaf(p, K_IDENT);
  e.name = name;
  e.ref = ref;
  e.has_mname = true;
  e.mname = name;
  return e;
}

static Expr parse_paren(P *p) {
  uint32_t start = p->t.start, pending = p->pending.n, cands = cand_mark(p);
  lex_next(p);
  Expr inner = {0};
  int count = 0;
  bool rest = false, trailing = false;
  while (!is_punct(p, P_RPAREN)) {
    if (eat(p, P_ELLIPSIS)) rest = true;
    inner = parse_assign(p, false);
    count++;
    if (!eat(p, P_COMMA)) break;
    if (is_punct(p, P_RPAREN)) trailing = true;
  }
  expect(p, P_RPAREN);
  if (is_punct(p, P_ARROW) && !p->t.nl) {
    scope_open_at(p, pending, true);
    for (uint32_t i = cands; i < p->cands.n; i++) ref_bind(p, p->cands.v[i]);
    cand_cut(p, cands);
    return parse_arrow_body(p, false, start);
  }
  if (count == 0 || rest || trailing) yte_fail(p, PARSE_ERROR);
  Expr e = count == 1 ? inner : node(inner.start, inner.end, K_SEQ, false);
  e.kind = K_PAREN;
  e.start = start;
  e.end = p->prev_end;
  e.proto = e.has_obj_mname = e.new_member = e.obj_safe = false;
  e.body_start = e.body_end = 0;
  return e;
}

static Expr parse_array(P *p) {
  uint32_t start = p->t.start;
  bool safe = true;
  lex_next(p);
  while (!is_punct(p, P_RBRACK)) {
    if (eat(p, P_COMMA)) continue;
    if (eat(p, P_ELLIPSIS)) {
      parse_assign(p, false);
      safe = false;
    } else {
      Expr e = parse_assign(p, false);
      safe = safe && e.safe;
    }
    if (!eat(p, P_COMMA)) break;
  }
  expect(p, P_RBRACK);
  return node(start, p->prev_end, K_ARRAY, safe);
}

Expr parse_template(P *p) {
  uint32_t start = p->t.start;
  bool safe = true;
  if (p->t.type != T_TEMPLATE) yte_fail(p, PARSE_ERROR);
  if (p->t.tpl == TPL_FULL) {
    lex_next(p);
    return node(start, p->prev_end, K_TEMPLATE, true);
  }
  for (;;) {
    lex_next(p);
    Expr e = parse_expression(p, false);
    safe = safe && e.safe;
    lex_template_cont(p);
    if (p->t.tpl == TPL_TAIL) break;
  }
  lex_next(p);
  return node(start, p->prev_end, K_TEMPLATE, safe);
}

bool key_follows(Tok n, bool star_ok) {
  if (n.type == T_IDENT || n.type == T_STR || n.type == T_NUM || n.type == T_BIGINT ||
      n.type == T_PRIVATE)
    return true;
  return n.type == T_PUNCT && (n.punct == P_LBRACK || (star_ok && n.punct == P_STAR));
}

static bool value_safe(const Expr *v) {
  uint8_t k = v->kind;
  return k == K_FUNC || k == K_ARROW || k == K_BOOL || k == K_NULL || k == K_NUM ||
         k == K_STR || k == K_REGEX;
}

static void check_stamp(P *p, Name key, uint32_t at, const Expr *v) {
  if (!p->collect || v->kind != K_NUM || !name_is(key, "signatureTimestamp")) return;
  double x = num_value(p->src + v->start, v->end - v->start);
  if (x > 0 && floor(x) == x && x <= 4294967295.0) stamp_add(p, at, (uint32_t)x);
}

Expr parse_object(P *p) {
  uint32_t start = p->t.start;
  bool safe = true;
  lex_next(p);
  while (!is_punct(p, P_RBRACE)) {
    if (eat(p, P_ELLIPSIS)) {
      parse_assign(p, false);
      safe = false;
      if (!eat(p, P_COMMA)) break;
      continue;
    }
    uint32_t member_start = p->t.start;
    bool accessor = false, is_async = false, gen = false;
    if (p->t.type == T_IDENT && !p->t.esc &&
        (p->t.kw == KW_GET || p->t.kw == KW_SET || p->t.kw == KW_ASYNC)) {
      Tok n = lex_peek(p, NULL);
      bool is_a = p->t.kw == KW_ASYNC;
      if (key_follows(n, is_a) && !(is_a && n.nl)) {
        accessor = !is_a;
        is_async = is_a;
        lex_next(p);
      }
    }
    if (eat(p, P_STAR)) gen = true;
    bool computed = false, is_string = false;
    Tok key_tok = p->t;
    Name key = property_key(p, &computed, &is_string);
    if (is_punct(p, P_LPAREN)) {
      parse_method(p, member_start, is_async, gen);
      if (computed || accessor) safe = false;
    } else if (!accessor && !is_async && !gen && eat(p, P_COLON)) {
      Expr v = parse_assign(p, false);
      if (computed || !value_safe(&v)) safe = false;
      if (!computed) check_stamp(p, key, key_tok.start, &v);
    } else {
      if (key_tok.type != T_IDENT || computed) yte_fail(p, PARSE_ERROR);
      cand_push(p, ref_add(p, key));
      safe = false;
      if (eat(p, P_ASSIGN)) {
        uint32_t m = cand_mark(p);
        parse_assign(p, false);
        cand_cut(p, m);
      }
    }
    if (!eat(p, P_COMMA)) break;
  }
  expect(p, P_RBRACE);
  return node(start, p->prev_end, K_OBJECT, safe);
}

Expr parse_primary(P *p) {
  switch (p->t.type) {
  case T_IDENT: return primary_ident(p);
  case T_NUM: return expr_leaf(p, K_NUM);
  case T_BIGINT: return expr_leaf(p, K_BIGINT);
  case T_STR: return expr_leaf(p, K_STR);
  case T_TEMPLATE: return parse_template(p);
  case T_PRIVATE: return expr_leaf(p, K_PRIVATE_IN);
  case T_PUNCT:
    switch (p->t.punct) {
    case P_LPAREN: return parse_paren(p);
    case P_LBRACK: return parse_array(p);
    case P_LBRACE: return parse_object(p);
    case P_SLASH: case P_DIV_A: lex_regex(p); return expr_leaf(p, K_REGEX);
    }
  }
  yte_fail(p, PARSE_ERROR);
}
