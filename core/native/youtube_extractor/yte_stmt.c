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

#include "yte.h"

#define PARSE_ERROR "Could not parse YouTube player JavaScript"

static bool let_decl(P *p) {
  Tok n = lex_peek(p, NULL);
  return n.type == T_IDENT || (n.type == T_PUNCT && (n.punct == P_LBRACK || n.punct == P_LBRACE));
}

// `top` marks a direct statement of a collected function body.
static void parse_var(P *p, int kind, bool no_in, bool top) {
  lex_next(p);
  if (top) coll_reset_alias(p);
  for (;;) {
    bool simple = p->t.type == T_IDENT;
    Name name = simple ? tok_name(p) : (Name){0};
    parse_binding(p, kind);
    Expr init;
    bool has = eat(p, P_ASSIGN);
    if (has) {
      uint32_t m = cand_mark(p);
      init = parse_assign(p, no_in);
      cand_cut(p, m);
    }
    if (top) coll_var(p, name, has ? &init : NULL);
    if (!eat(p, P_COMMA)) break;
  }
}

static void paren_expression(P *p) {
  expect(p, P_LPAREN);
  parse_expression(p, false);
  expect(p, P_RPAREN);
}

static void parse_block(P *p) {
  expect(p, P_LBRACE);
  scope_open(p, false);
  while (!is_punct(p, P_RBRACE)) {
    if (p->t.type == T_EOF) yte_fail(p, PARSE_ERROR);
    parse_statement(p, false, NULL);
  }
  lex_next(p);
  scope_close(p);
}

static void parse_for(P *p) {
  lex_next(p);
  if (is_kw(p, KW_AWAIT)) lex_next(p);
  expect(p, P_LPAREN);
  scope_open(p, false);
  if (is_kw(p, KW_VAR) || is_kw(p, KW_CONST) || (is_kw(p, KW_LET) && let_decl(p)))
    parse_var(p, is_kw(p, KW_VAR) ? 1 : 0, true, false);
  else if (!is_punct(p, P_SEMI))
    parse_expression(p, true);
  if (is_kw(p, KW_IN) || is_kw(p, KW_OF)) {
    bool of = is_kw(p, KW_OF);
    lex_next(p);
    if (of) parse_assign(p, false);
    else parse_expression(p, false);
    expect(p, P_RPAREN);
  } else {
    expect(p, P_SEMI);
    if (!is_punct(p, P_SEMI)) parse_expression(p, false);
    expect(p, P_SEMI);
    if (!is_punct(p, P_RPAREN)) parse_expression(p, false);
    expect(p, P_RPAREN);
  }
  parse_statement(p, false, NULL);
  scope_close(p);
}

static void parse_try(P *p) {
  lex_next(p);
  parse_block(p);
  if (is_kw(p, KW_CATCH)) {
    lex_next(p);
    scope_open(p, false);
    if (eat(p, P_LPAREN)) {
      parse_binding(p, 0);
      expect(p, P_RPAREN);
    }
    parse_block(p);
    scope_close(p);
  }
  if (is_kw(p, KW_FINALLY)) {
    lex_next(p);
    parse_block(p);
  }
}

static void parse_switch(P *p) {
  lex_next(p);
  paren_expression(p);
  expect(p, P_LBRACE);
  scope_open(p, false);
  while (!is_punct(p, P_RBRACE)) {
    if (is_kw(p, KW_CASE)) {
      lex_next(p);
      parse_expression(p, false);
      expect(p, P_COLON);
    } else if (is_kw(p, KW_DEFAULT)) {
      lex_next(p);
      expect(p, P_COLON);
    } else {
      if (p->t.type == T_EOF) yte_fail(p, PARSE_ERROR);
      parse_statement(p, false, NULL);
    }
  }
  lex_next(p);
  scope_close(p);
}

static bool ends_statement(P *p) {
  return is_punct(p, P_SEMI) || is_punct(p, P_RBRACE) || p->t.type == T_EOF || p->t.nl;
}

// Returns true when a keyword statement was handled.
static bool keyword_statement(P *p, bool top, int *kind) {
  uint32_t start = p->t.start;
  switch (p->t.kw) {
  case KW_VAR: case KW_CONST:
    parse_var(p, p->t.kw == KW_VAR ? 1 : 0, false, top);
    semicolon(p);
    *kind = S_VAR;
    return true;
  case KW_LET:
    if (!let_decl(p)) return false;
    parse_var(p, 0, false, top);
    semicolon(p);
    *kind = S_VAR;
    return true;
  case KW_FUNCTION: parse_function(p, start, false, true); return true;
  case KW_ASYNC: {
    Tok n = lex_peek(p, NULL);
    if (n.nl || n.type != T_IDENT || n.kw != KW_FUNCTION) return false;
    lex_next(p);
    parse_function(p, start, true, true);
    return true;
  }
  case KW_CLASS: parse_class(p, start, true); return true;
  case KW_IF:
    lex_next(p);
    paren_expression(p);
    parse_statement(p, false, NULL);
    if (is_kw(p, KW_ELSE)) {
      lex_next(p);
      parse_statement(p, false, NULL);
    }
    return true;
  case KW_FOR: parse_for(p); return true;
  case KW_WHILE: case KW_WITH:
    lex_next(p);
    paren_expression(p);
    parse_statement(p, false, NULL);
    return true;
  case KW_DO:
    lex_next(p);
    parse_statement(p, false, NULL);
    if (!is_kw(p, KW_WHILE)) yte_fail(p, PARSE_ERROR);
    lex_next(p);
    paren_expression(p);
    eat(p, P_SEMI);
    return true;
  case KW_CONTINUE: case KW_BREAK:
    lex_next(p);
    if (p->t.type == T_IDENT && !p->t.nl) lex_next(p);
    semicolon(p);
    return true;
  case KW_RETURN: case KW_THROW:
    lex_next(p);
    if (!ends_statement(p)) parse_expression(p, false);
    semicolon(p);
    return true;
  case KW_TRY: parse_try(p); return true;
  case KW_SWITCH: parse_switch(p); return true;
  case KW_DEBUGGER:
    lex_next(p);
    semicolon(p);
    return true;
  }
  return false;
}

int parse_statement(P *p, bool top, Expr *out) {
  enter(p);
  uint32_t m = cand_mark(p);
  int kind = S_OTHER;
  bool done = false;
  if (is_punct(p, P_LBRACE)) {
    parse_block(p);
    done = true;
  } else if (eat(p, P_SEMI)) {
    done = true;
  } else if (p->t.type == T_IDENT) {
    if (!p->t.esc) done = keyword_statement(p, top, &kind);
    if (!done && (p->t.esc || p->t.kw == KW_NONE || p->t.kw >= KW_RESERVED_END)) {
      Tok n = lex_peek(p, NULL);
      if (n.type == T_PUNCT && n.punct == P_COLON) {
        lex_next(p);
        lex_next(p);
        parse_statement(p, false, NULL);
        done = true;
      }
    }
  }
  if (!done) {
    Expr e = parse_expression(p, false);
    semicolon(p);
    if (out) *out = e;
    kind = S_EXPR;
  }
  cand_cut(p, m);
  leave(p);
  return kind;
}

void parse_function_body(P *p, FnCtx *ctx, uint32_t fn_start, uint32_t *open, uint32_t *close) {
  *open = p->t.start;
  expect(p, P_LBRACE);
  bool collecting = p->collect && p->fn_depth == 1;
  if (collecting) coll_begin(p, ctx, fn_start);
  while (!is_punct(p, P_RBRACE)) {
    if (p->t.type == T_EOF) yte_fail(p, PARSE_ERROR);
    size_t mark = arena_mark(&p->scratch);
    Expr e;
    int kind = parse_statement(p, collecting, &e);
    if (kind == S_EXPR && e.ikind == K_ASSIGN) {
      AssignInfo *a = &p->last_assign;
      if (a->op_plain && a->left_ident && ctx->p0_simple && name_eq(a->target, ctx->p0) &&
          a->right.kind == K_NEW && a->right.new_member)
        ctx->ctor = true;
    } else if (kind == S_EXPR && e.ikind == K_CALL && e.alr) {
      ctx->alr = true;
    }
    if (collecting) {
      if (kind == S_EXPR) coll_expr_stmt(p, &e);
      else if (kind == S_OTHER) coll_reset_alias(p);
      arena_release(&p->scratch, mark);
    }
  }
  *close = p->t.start;
  lex_next(p);
  if (collecting) coll_end(p);
}

void parse_program(P *p) {
  lex_seek(p, 0);
  while (p->t.type != T_EOF) {
    size_t mark = arena_mark(&p->scratch);
    Expr e;
    int kind = parse_statement(p, false, &e);
    if (kind == S_EXPR && !p->wrapper && e.ikind == K_CALL && e.callee_fn) {
      for (Coll **c = &p->done; *c; c = &(*c)->next) {
        if ((*c)->fn_start != e.fn_start) continue;
        p->wrapper = *c;
        *c = (*c)->next;
        p->wrapper->next = NULL;
        break;
      }
    }
    while (p->done) {
      Coll *next = p->done->next;
      coll_free(p->done);
      p->done = next;
    }
    arena_release(&p->scratch, mark);
  }
}
