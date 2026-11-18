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
// About 2.4 KB of stack per level in the worst construct; real players stay under 40.
#define MAX_DEPTH 256

void enter(P *p) {
  if (++p->depth > MAX_DEPTH) yte_fail(p, PARSE_ERROR);
}

static bool in_source(P *p, Name n) { return n.p >= p->src && n.p + n.n <= p->src + p->len; }

static bool is_member(uint8_t kind) {
  return kind == K_STATIC || kind == K_COMPUTED || kind == K_PRIVATE_MEMBER;
}

static bool str_is(P *p, const Expr *e, const char *s) {
  size_t n = strlen(s);
  return e->kind == K_STR && e->end - e->start == n + 2 && memcmp(p->src + e->start + 1, s, n) == 0;
}

static Expr member_static(P *p, const Expr *obj, Name prop, bool optional) {
  Expr e = {0};
  e.start = obj->start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_STATIC;
  e.ref = -1;
  e.prop = prop;
  e.proto = name_is(prop, "prototype");
  e.safe = e.proto;
  e.obj_safe = obj->safe;
  if (obj->has_mname) {
    e.has_mname = e.has_obj_mname = true;
    e.obj_mname = obj->mname;
    e.ref = obj->ref;
    Name m = obj->mname;
    if (!optional && in_source(p, m) && m.p + m.n + 1 == prop.p && m.p[m.n] == '.')
      e.mname = (Name){m.p, m.n + 1 + prop.n};
    else
      e.mname = name_cat(p, &p->scratch, m, ".", prop, "");
    entry_add(p, e.mname, e.ref);
  }
  return e;
}

static Expr member_computed(P *p, const Expr *obj, const Expr *key, uint32_t lb, uint32_t rb) {
  Expr e = {0};
  e.start = obj->start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_COMPUTED;
  e.ref = -1;
  if (obj->has_mname) {
    e.has_mname = true;
    e.ref = obj->ref;
    Name m = obj->mname, text = {p->src + key->start, key->end - key->start};
    if (in_source(p, m) && m.p + m.n == p->src + lb && key->start == lb + 1 && key->end == rb)
      e.mname = (Name){m.p, rb + 1 - (uint32_t)(m.p - p->src)};
    else
      e.mname = name_cat(p, &p->scratch, m, "[", text, "]");
    entry_add(p, e.mname, e.ref);
  }
  return e;
}

// Parses `(args)`; candidates of each argument are kept for async arrow conversion.
static void parse_args(P *p, bool *safe, uint16_t *argc, Expr first[2]) {
  expect(p, P_LPAREN);
  *safe = true;
  *argc = 0;
  while (!is_punct(p, P_RPAREN)) {
    Expr a;
    if (eat(p, P_ELLIPSIS)) {
      a = parse_assign(p, false);
      *safe = false;
    } else {
      a = parse_assign(p, false);
      *safe = *safe && a.safe;
    }
    if (*argc < 2) first[*argc] = a;
    if (*argc < 0xFFFF) (*argc)++;
    if (!eat(p, P_COMMA)) break;
  }
  expect(p, P_RPAREN);
}

static Expr make_call(P *p, const Expr *callee, bool args_safe, uint16_t argc, const Expr first[2]) {
  Expr e = {0};
  e.start = callee->start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_CALL;
  e.ref = -1;
  bool ok = (callee->kind == K_IDENT && builtin(callee->name)) ||
            (callee->kind == K_STATIC && callee->obj_safe && builtin(callee->prop));
  e.safe = ok && args_safe;
  e.callee_member = is_member(callee->kind);
  e.alr = e.callee_member && argc == 2 && str_is(p, &first[0], "alr") && str_is(p, &first[1], "yes");
  e.callee_fn = callee->ikind == K_FUNC;
  e.fn_start = callee->fn_start;
  return e;
}

static Expr async_arrow(P *p, const Expr *callee, uint32_t pending, uint32_t cands) {
  if (p->track) {
    p->refs.v[callee->ref].state = 2;
    scope_open_at(p, pending, true);
    for (uint32_t i = cands; i < p->cands.n; i++) ref_bind(p, p->cands.v[i]);
  } else {
    scope_open_at(p, pending, true);
  }
  cand_cut(p, cands);
  return parse_arrow_body(p, true, callee->start);
}

Expr parse_postfix(P *p, Expr e, bool calls) {
  bool chain = false;
  for (;;) {
    if (is_punct(p, P_DOT) || is_punct(p, P_QDOT)) {
      bool optional = is_punct(p, P_QDOT);
      if (optional && !calls) break;
      lex_next(p);
      if (optional) {
        chain = true;
        if (is_punct(p, P_LPAREN) || is_punct(p, P_LBRACK)) continue;
      }
      if (p->t.type == T_PRIVATE) {
        lex_next(p);
        Expr m = {0};
        m.start = e.start;
        m.end = p->prev_end;
        m.kind = m.ikind = K_PRIVATE_MEMBER;
        m.ref = -1;
        e = m;
        continue;
      }
      if (p->t.type != T_IDENT) yte_fail(p, PARSE_ERROR);
      Name prop = tok_name(p);
      lex_next(p);
      e = member_static(p, &e, prop, optional);
    } else if (is_punct(p, P_LBRACK)) {
      uint32_t lb = p->t.start;
      lex_next(p);
      uint32_t m = cand_mark(p);
      Expr key = parse_expression(p, false);
      cand_cut(p, m);
      uint32_t rb = p->t.start;
      expect(p, P_RBRACK);
      e = member_computed(p, &e, &key, lb, rb);
    } else if (is_punct(p, P_LPAREN) && calls) {
      bool maybe_async = e.kind == K_IDENT && name_is(e.name, "async") && !p->t.nl;
      uint32_t pending = p->pending.n, cands = cand_mark(p);
      bool safe;
      uint16_t argc;
      Expr first[2] = {{0}, {0}};
      parse_args(p, &safe, &argc, first);
      if (maybe_async && is_punct(p, P_ARROW) && !p->t.nl) return async_arrow(p, &e, pending, cands);
      e = make_call(p, &e, safe, argc, first);
    } else if (p->t.type == T_TEMPLATE && (p->t.tpl == TPL_FULL || p->t.tpl == TPL_HEAD)) {
      parse_template(p);
      Expr t = {0};
      t.start = e.start;
      t.end = p->prev_end;
      t.kind = t.ikind = K_TAGGED;
      t.ref = -1;
      e = t;
    } else {
      break;
    }
  }
  if (chain) {
    Expr c = {0};
    c.start = e.start;
    c.end = e.end;
    c.kind = c.ikind = K_CHAIN;
    c.ref = -1;
    e = c;
  }
  return e;
}

static Expr parse_new(P *p) {
  uint32_t start = p->t.start;
  lex_next(p);
  if (is_punct(p, P_DOT)) {
    lex_next(p);
    if (p->t.type != T_IDENT) yte_fail(p, PARSE_ERROR);
    lex_next(p);
    Expr m = {0};
    m.start = start;
    m.end = p->prev_end;
    m.kind = m.ikind = K_META;
    m.ref = -1;
    return m;
  }
  enter(p);
  Expr callee = is_kw(p, KW_NEW) ? parse_new(p) : parse_primary(p);
  callee = parse_postfix(p, callee, false);
  leave(p);
  bool safe = true;
  uint16_t argc = 0;
  Expr first[2] = {{0}, {0}};
  if (is_punct(p, P_LPAREN)) parse_args(p, &safe, &argc, first);
  Expr e = {0};
  e.start = start;
  e.end = p->prev_end;
  e.kind = e.ikind = K_NEW;
  e.ref = -1;
  e.safe = callee.kind == K_IDENT && builtin(callee.name) && safe;
  e.new_member = is_member(callee.kind);
  return e;
}

Expr parse_lhs_new(P *p) {
  Expr e = is_kw(p, KW_NEW) ? parse_new(p) : parse_primary(p);
  return parse_postfix(p, e, true);
}

static bool in_async(P *p) { return p->fns.n && p->fns.v[p->fns.n - 1].async; }
static bool in_gen(P *p) { return p->fns.n && p->fns.v[p->fns.n - 1].gen; }

static Expr parse_unary(P *p) {
  uint32_t start = p->t.start;
  bool unary = false, update = false, await = false;
  if (p->t.type == T_PUNCT) {
    int c = p->t.punct;
    unary = c == P_BANG || c == P_TILDE || c == P_PLUS || c == P_MINUS;
    update = c == P_INC || c == P_DEC;
  } else if (p->t.type == T_IDENT && !p->t.esc) {
    unary = p->t.kw == KW_DELETE || p->t.kw == KW_VOID || p->t.kw == KW_TYPEOF;
    await = p->t.kw == KW_AWAIT && in_async(p);
  }
  if (unary || update || await) {
    lex_next(p);
    enter(p);
    Expr arg = parse_unary(p);
    leave(p);
    Expr e = {0};
    e.start = start;
    e.end = arg.end;
    e.kind = e.ikind = update ? K_UPDATE : await ? K_AWAIT : K_UNARY;
    e.safe = unary && arg.safe;
    e.ref = -1;
    return e;
  }
  Expr e = parse_lhs_new(p);
  if ((is_punct(p, P_INC) || is_punct(p, P_DEC)) && !p->t.nl) {
    lex_next(p);
    Expr u = {0};
    u.start = e.start;
    u.end = p->prev_end;
    u.kind = u.ikind = K_UPDATE;
    u.ref = -1;
    return u;
  }
  return e;
}

static int precedence(P *p, bool no_in, bool *logical) {
  *logical = false;
  if (p->t.type == T_IDENT) {
    if (p->t.esc) return -1;
    if (p->t.kw == KW_INSTANCEOF || (p->t.kw == KW_IN && !no_in)) return 8;
    return -1;
  }
  if (p->t.type != T_PUNCT) return -1;
  switch (p->t.punct) {
  case P_NULLISH: *logical = true; return 1;
  case P_OR: *logical = true; return 2;
  case P_AND: *logical = true; return 3;
  case P_PIPE: return 4;
  case P_CARET: return 5;
  case P_AMP: return 6;
  case P_EQ: case P_NE: case P_SEQ: case P_SNE: return 7;
  case P_LT: case P_GT: case P_LE: case P_GE: return 8;
  case P_SHL: case P_SHR: case P_USHR: return 9;
  case P_PLUS: case P_MINUS: return 10;
  case P_STAR: case P_SLASH: case P_PERCENT: return 11;
  case P_POW: return 12;
  default: return -1;
  }
}

static Expr parse_binary(P *p, int min, bool no_in) {
  Expr left = parse_unary(p);
  for (;;) {
    bool logical;
    int prec = precedence(p, no_in, &logical);
    if (prec < min) break;
    lex_next(p);
    enter(p);
    Expr right = parse_binary(p, prec == 12 ? prec : prec + 1, no_in);
    leave(p);
    Expr e = {0};
    e.start = left.start;
    e.end = right.end;
    e.kind = e.ikind = logical ? K_LOGICAL : K_BINARY;
    e.safe = left.safe && right.safe;
    e.ref = -1;
    left = e;
  }
  return left;
}

static Expr parse_conditional(P *p, bool no_in) {
  Expr test = parse_binary(p, 1, no_in);
  if (!is_punct(p, P_QUESTION)) return test;
  lex_next(p);
  parse_assign(p, false);
  expect(p, P_COLON);
  Expr alt = parse_assign(p, no_in);
  Expr e = {0};
  e.start = test.start;
  e.end = alt.end;
  e.kind = e.ikind = K_COND;
  e.ref = -1;
  return e;
}

static bool starts_expression(P *p) {
  if (p->t.type == T_EOF || p->t.nl) return false;
  if (p->t.type != T_PUNCT) return !(p->t.type == T_IDENT && p->t.kw == KW_IN);
  int c = p->t.punct;
  return !(c == P_RPAREN || c == P_RBRACK || c == P_RBRACE || c == P_COMMA || c == P_SEMI ||
           c == P_COLON || c == P_QUESTION || c >= P_ASSIGN || c == P_ARROW);
}

// Keeps binding candidates only for expressions that can become patterns.
static void keep_candidates(P *p, const Expr *e, uint32_t mark) {
  if (e->kind == K_IDENT) {
    cand_cut(p, mark);
    cand_push(p, e->ref);
  } else if (e->kind != K_ARRAY && e->kind != K_OBJECT) {
    cand_cut(p, mark);
  }
}

Expr parse_assign(P *p, bool no_in) {
  enter(p);
  uint32_t mark = cand_mark(p);
  if (is_kw(p, KW_YIELD) && in_gen(p)) {
    uint32_t start = p->t.start;
    lex_next(p);
    if (starts_expression(p) || is_punct(p, P_STAR)) {
      eat(p, P_STAR);
      parse_assign(p, no_in);
    }
    cand_cut(p, mark);
    Expr e = {0};
    e.start = start;
    e.end = p->prev_end;
    e.kind = e.ikind = K_YIELD;
    e.ref = -1;
    leave(p);
    return e;
  }
  Expr left = parse_conditional(p, no_in);
  keep_candidates(p, &left, mark);
  if (p->t.type == T_PUNCT && p->t.punct >= P_ASSIGN) {
    bool plain = p->t.punct == P_ASSIGN;
    lex_next(p);
    uint32_t right_mark = cand_mark(p);
    Expr right = parse_assign(p, no_in);
    cand_cut(p, right_mark);
    AssignInfo *a = &p->last_assign;
    a->op_plain = plain;
    a->left_ident = left.kind == K_IDENT;
    a->has_target = left.kind == K_IDENT || (is_member(left.kind) && left.has_mname);
    a->target = left.kind == K_IDENT ? left.name : left.mname;
    a->right = right;
    Expr e = {0};
    e.start = left.start;
    e.end = right.end;
    e.kind = e.ikind = K_ASSIGN;
    e.ref = -1;
    leave(p);
    return e;
  }
  leave(p);
  return left;
}

Expr parse_expression(P *p, bool no_in) {
  Expr e = parse_assign(p, no_in);
  if (!is_punct(p, P_COMMA)) return e;
  while (eat(p, P_COMMA)) parse_assign(p, no_in);
  Expr s = {0};
  s.start = e.start;
  s.end = p->prev_end;
  s.kind = s.ikind = K_SEQ;
  s.ref = -1;
  return s;
}
