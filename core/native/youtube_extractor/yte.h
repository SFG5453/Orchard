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

// Internal interfaces of the tree-free player parser. The parser returns small
// per-node summaries (Expr) instead of an AST, so memory stays near the source size.

#ifndef ORCHARD_YTE_H
#define ORCHARD_YTE_H

#include <setjmp.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef struct { const char *p; uint32_t n; } Name;

static inline bool name_eq(Name a, Name b) {
  return a.n == b.n && (a.n == 0 || memcmp(a.p, b.p, a.n) == 0);
}
bool name_is(Name a, const char *s);

enum { T_EOF, T_IDENT, T_PRIVATE, T_NUM, T_BIGINT, T_STR, T_TEMPLATE, T_REGEX, T_PUNCT };

enum {
  KW_NONE, KW_BREAK, KW_CASE, KW_CATCH, KW_CLASS, KW_CONST, KW_CONTINUE, KW_DEBUGGER,
  KW_DEFAULT, KW_DELETE, KW_DO, KW_ELSE, KW_ENUM, KW_EXPORT, KW_EXTENDS, KW_FALSE,
  KW_FINALLY, KW_FOR, KW_FUNCTION, KW_IF, KW_IMPORT, KW_IN, KW_INSTANCEOF, KW_NEW,
  KW_NULL, KW_RETURN, KW_SUPER, KW_SWITCH, KW_THIS, KW_THROW, KW_TRUE, KW_TRY,
  KW_TYPEOF, KW_VAR, KW_VOID, KW_WHILE, KW_WITH,
  // Contextual words, valid identifiers.
  KW_ASYNC, KW_AWAIT, KW_YIELD, KW_LET, KW_STATIC, KW_GET, KW_SET, KW_OF, KW_ACCESSOR,
};
#define KW_RESERVED_END KW_ASYNC

enum {
  P_NONE, P_LBRACE, P_RBRACE, P_LPAREN, P_RPAREN, P_LBRACK, P_RBRACK, P_SEMI, P_COMMA,
  P_DOT, P_ELLIPSIS, P_QDOT, P_QUESTION, P_COLON, P_ARROW, P_AT,
  P_LT, P_GT, P_LE, P_GE, P_EQ, P_NE, P_SEQ, P_SNE, P_PLUS, P_MINUS, P_STAR, P_SLASH,
  P_PERCENT, P_POW, P_INC, P_DEC, P_SHL, P_SHR, P_USHR, P_AMP, P_PIPE, P_CARET, P_BANG,
  P_TILDE, P_AND, P_OR, P_NULLISH,
  // Assignment operators; P_ASSIGN must stay first.
  P_ASSIGN, P_ADD_A, P_SUB_A, P_MUL_A, P_DIV_A, P_MOD_A, P_POW_A, P_SHL_A, P_SHR_A,
  P_USHR_A, P_AND_A, P_OR_A, P_XOR_A, P_LAND_A, P_LOR_A, P_NULLISH_A,
};

// Template token states.
enum { TPL_FULL, TPL_HEAD, TPL_MIDDLE, TPL_TAIL };

typedef struct {
  uint8_t type, punct, kw, nl, esc, tpl;
  uint32_t start, end;
} Tok;

enum {
  K_NONE, K_IDENT, K_THIS, K_NULL, K_BOOL, K_NUM, K_BIGINT, K_STR, K_REGEX, K_TEMPLATE,
  K_TAGGED, K_ARRAY, K_OBJECT, K_FUNC, K_ARROW, K_CLASS, K_UNARY, K_UPDATE, K_BINARY,
  K_LOGICAL, K_COND, K_ASSIGN, K_SEQ, K_STATIC, K_COMPUTED, K_PRIVATE_MEMBER, K_CALL,
  K_NEW, K_CHAIN, K_PAREN, K_SUPER, K_YIELD, K_AWAIT, K_META, K_IMPORT, K_PRIVATE_IN,
};

typedef struct {
  uint32_t start, end;
  uint8_t kind, ikind;    // raw kind and kind with parentheses stripped
  bool safe;              // extraction policy result for the inner node
  bool sigfn;             // inner function matches the signature function shape
  bool alr;               // call(..., "alr", "yes") with a member callee
  bool callee_member;     // call with a raw member callee
  bool new_member;        // new with a raw member callee
  bool proto;             // raw static member whose property is "prototype"
  bool obj_safe;          // raw static member whose object is safe
  bool has_mname, has_obj_mname, callee_fn;
  int32_t ref;            // root identifier reference of the inner member chain
  Name name;              // identifier name when ikind is K_IDENT
  Name mname;             // member_name of the inner node
  Name obj_mname;         // member_name of a raw static member's object
  Name prop;              // raw static member property
  uint32_t fn_start;      // inner function start (K_FUNC), or callee's for calls
  uint32_t body_start, body_end;  // raw K_FUNC body braces
} Expr;

typedef struct { Name name; uint8_t state; } Ref;  // state: 0 open, 1 local, 2 binding
typedef struct { Name name; int32_t ref; } Entry;
typedef struct { uint32_t pending; Name *decls; uint32_t n, cap; bool fn; } Scope;

typedef struct {
  Name p0;
  uint16_t nparams;
  bool p0_simple, p0_plain, p1_init, p2_init, ctor, alr, gen, async;
} FnCtx;

typedef struct {
  uint32_t start, end, body_start, body_end;
  uint8_t kind, ikind;
  bool safe, sigfn;
  Name ident;
} Init;

typedef struct {
  Name name;
  bool variable, has_init, has_alias;
  Init init;
  int32_t *members;
  uint32_t nmembers, cmembers;
  Name alias_key, alias_owner;
} Def;

typedef struct { Name *keys; int32_t *vals; uint32_t cap, n; } Index;

typedef struct Coll {
  Def *defs;
  uint32_t n, cap;
  Index index;
  bool alias;
  Name alias_key, alias_owner;
  int32_t alias_id;
  uint32_t fn_start;
  Name param0;
  bool has_param0;
  struct Coll *next;
} Coll;

typedef struct Chunk { struct Chunk *next; size_t base, used, cap; char data[]; } Chunk;
typedef struct { Chunk *head; } Arena;

#define VEC(T) struct { T *v; uint32_t n, cap; }

typedef struct { uint32_t at; uint32_t value; } Stamp;

typedef struct {
  bool op_plain, left_ident, has_target;
  Name target;
  Expr right;
} AssignInfo;

typedef struct P {
  const char *src;
  uint32_t len, pos, prev_end;
  Tok t;
  jmp_buf jb;
  const char *err;
  int depth;
  bool track;    // record references and scopes (dependency pass)
  bool collect;  // record definitions and timestamps (first pass)
  int fn_depth;
  Arena scratch, keep;
  VEC(FnCtx) fns;
  VEC(Ref) refs;
  VEC(int32_t) pending;
  VEC(Entry) entries;
  VEC(int32_t) cands;
  VEC(Scope) scopes;
  VEC(Stamp) stamps;
  Coll *coll, *done, *wrapper;
  AssignInfo last_assign;
} P;

#define VPUSH(p, vec, val) do { \
    if ((vec).n == (vec).cap) yte_grow((p), (void **)&(vec).v, &(vec).cap, sizeof(*(vec).v)); \
    (vec).v[(vec).n++] = (val); \
  } while (0)

_Noreturn void yte_fail(P *p, const char *msg);
void yte_grow(P *p, void **v, uint32_t *cap, size_t size);
void *arena_alloc(P *p, Arena *a, size_t n);
size_t arena_mark(Arena *a);
void arena_release(Arena *a, size_t mark);
void arena_free(Arena *a);
Name name_cat(P *p, Arena *a, Name a1, const char *mid, Name a2, const char *tail);
Name name_keep(P *p, Name n);

// Lexer.
void lex_next(P *p);
void lex_regex(P *p);
void lex_template_cont(P *p);
void lex_seek(P *p, uint32_t pos);
Tok lex_peek(P *p, uint32_t *after);
static inline Name tok_name(P *p) { return (Name){p->src + p->t.start, p->t.end - p->t.start}; }
static inline bool is_punct(P *p, int c) { return p->t.type == T_PUNCT && p->t.punct == c; }
static inline bool is_kw(P *p, int k) { return p->t.type == T_IDENT && p->t.kw == k && !p->t.esc; }
void expect(P *p, int punct);
bool eat(P *p, int punct);
void semicolon(P *p);
double num_value(const char *s, uint32_t n);

// Expressions.
Expr parse_expression(P *p, bool no_in);
Expr parse_assign(P *p, bool no_in);
Expr parse_lhs_new(P *p);
Expr parse_postfix(P *p, Expr e, bool calls);
Expr parse_primary(P *p);
Expr parse_function(P *p, uint32_t start, bool is_async, bool declaration);
Expr parse_method(P *p, uint32_t start, bool is_async, bool gen);
Expr parse_class(P *p, uint32_t start, bool declaration);
Expr parse_object(P *p);
Expr parse_template(P *p);
Expr parse_arrow_body(P *p, bool is_async, uint32_t start);
void parse_params(P *p, FnCtx *ctx);
void parse_binding(P *p, int kind);  // 0 param/let/const/catch, 1 var
Name property_key(P *p, bool *computed, bool *is_string);
bool key_follows(Tok n, bool star_ok);
void enter(P *p);
static inline void leave(P *p) { p->depth--; }
Expr expr_leaf(P *p, uint8_t kind);
bool builtin(Name n);

// Statements.
enum { S_OTHER, S_EXPR, S_VAR };
int parse_statement(P *p, bool top, Expr *out);
void parse_function_body(P *p, FnCtx *ctx, uint32_t fn_start, uint32_t *open, uint32_t *close);
void parse_program(P *p);

// Scopes and references; no-ops unless p->track.
void scope_open(P *p, bool fn);
void scope_open_at(P *p, uint32_t pending, bool fn);
void scope_close(P *p);
void scope_declare(P *p, Name n, bool var);
int32_t ref_add(P *p, Name n);
void entry_add(P *p, Name n, int32_t ref);
void ref_bind(P *p, int32_t ref);
static inline uint32_t cand_mark(P *p) { return p->cands.n; }
static inline void cand_cut(P *p, uint32_t mark) { if (p->cands.n > mark) p->cands.n = mark; }
void cand_push(P *p, int32_t ref);

// Definitions.
void coll_begin(P *p, FnCtx *ctx, uint32_t fn_start);
void coll_end(P *p);
void coll_var(P *p, Name name, const Expr *init);
void coll_reset_alias(P *p);
void coll_expr_stmt(P *p, const Expr *e);
void stamp_add(P *p, uint32_t at, uint32_t value);
int32_t index_get(const Index *ix, Name n);
void index_put(P *p, Index *ix, Name n, int32_t v);
void index_free(Index *ix);
void coll_free(Coll *c);

#endif
