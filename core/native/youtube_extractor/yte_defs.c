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

// Top-level definition table of the player wrapper, mirroring the alias and
// prototype retention rules of the pinned YouTube.js extractor.

#include <stdlib.h>
#include <string.h>

#include "yte.h"

#define OOM "Native player extraction ran out of memory"

static uint32_t hash(Name n) {
  uint32_t h = 2166136261u;
  for (uint32_t i = 0; i < n.n; i++) h = (h ^ (uint8_t)n.p[i]) * 16777619u;
  return h;
}

int32_t index_get(const Index *ix, Name n) {
  if (!ix->cap) return -1;
  for (uint32_t i = hash(n) & (ix->cap - 1);; i = (i + 1) & (ix->cap - 1)) {
    if (!ix->keys[i].p) return -1;
    if (name_eq(ix->keys[i], n)) return ix->vals[i];
  }
}

static void index_slot(Index *ix, Name n, int32_t v) {
  for (uint32_t i = hash(n) & (ix->cap - 1);; i = (i + 1) & (ix->cap - 1)) {
    if (!ix->keys[i].p) {
      ix->keys[i] = n;
      ix->vals[i] = v;
      ix->n++;
      return;
    }
    if (name_eq(ix->keys[i], n)) {
      ix->vals[i] = v;
      return;
    }
  }
}

void index_put(P *p, Index *ix, Name n, int32_t v) {
  if ((ix->n + 1) * 2 > ix->cap) {
    Index grown = {0};
    grown.cap = ix->cap ? ix->cap * 2 : 1024;
    grown.keys = calloc(grown.cap, sizeof(Name));
    grown.vals = malloc(grown.cap * sizeof(int32_t));
    if (!grown.keys || !grown.vals) {
      free(grown.keys);
      free(grown.vals);
      yte_fail(p, OOM);
    }
    for (uint32_t i = 0; i < ix->cap; i++)
      if (ix->keys[i].p) index_slot(&grown, ix->keys[i], ix->vals[i]);
    index_free(ix);
    *ix = grown;
  }
  index_slot(ix, n, v);
}

void index_free(Index *ix) {
  free(ix->keys);
  free(ix->vals);
  *ix = (Index){0};
}

void coll_free(Coll *c) {
  if (!c) return;
  for (uint32_t i = 0; i < c->n; i++) free(c->defs[i].members);
  free(c->defs);
  index_free(&c->index);
  free(c);
}

void coll_begin(P *p, FnCtx *ctx, uint32_t fn_start) {
  Coll *c = calloc(1, sizeof(Coll));
  if (!c) yte_fail(p, OOM);
  c->fn_start = fn_start;
  c->has_param0 = ctx->nparams > 0 && ctx->p0_simple;
  c->param0 = ctx->p0;
  c->next = p->coll;
  p->coll = c;
}

void coll_end(P *p) {
  Coll *c = p->coll;
  p->coll = c->next;
  c->next = p->done;
  p->done = c;
}

static Init to_init(const Expr *e) {
  Init i = {0};
  i.start = e->start;
  i.end = e->end;
  i.body_start = e->body_start;
  i.body_end = e->body_end;
  i.kind = e->kind;
  i.ikind = e->ikind;
  i.safe = e->safe;
  i.sigfn = e->sigfn;
  if (e->ikind == K_IDENT) i.ident = e->name;
  return i;
}

static int32_t push_def(P *p, Coll *c, Name name, bool variable, const Expr *init) {
  if (c->n == c->cap) {
    uint32_t cap = c->cap ? c->cap * 2 : 1024;
    Def *grown = realloc(c->defs, cap * sizeof(Def));
    if (!grown) yte_fail(p, OOM);
    c->defs = grown;
    c->cap = cap;
  }
  Def *d = &c->defs[c->n];
  memset(d, 0, sizeof *d);
  d->name = name_keep(p, name);
  d->variable = variable;
  d->has_init = init != NULL;
  if (init) d->init = to_init(init);
  return (int32_t)c->n++;
}

static void add_member(P *p, Def *owner, int32_t id) {
  if (owner->nmembers == owner->cmembers)
    yte_grow(p, (void **)&owner->members, &owner->cmembers, sizeof(int32_t));
  owner->members[owner->nmembers++] = id;
}

void coll_var(P *p, Name name, const Expr *init) {
  Coll *c = p->coll;
  if (!c || !name.p) return;
  int32_t id = push_def(p, c, name, true, init);
  index_put(p, &c->index, c->defs[id].name, id);
}

void coll_reset_alias(P *p) {
  if (p->coll) p->coll->alias = false;
}

static const char *find(Name hay, const char *needle) {
  size_t n = strlen(needle);
  for (uint32_t i = 0; i + n <= hay.n; i++)
    if (memcmp(hay.p + i, needle, n) == 0) return hay.p + i;
  return NULL;
}

void coll_expr_stmt(P *p, const Expr *e) {
  Coll *c = p->coll;
  if (!c) return;
  AssignInfo *a = &p->last_assign;
  if (e->ikind != K_ASSIGN || !a->op_plain || !a->has_target) {
    c->alias = false;
    return;
  }
  Name name = a->target;
  const Expr *right = &a->right;
  if (right->has_mname && name_eq(right->mname, name)) return;
  if (right->kind == K_STATIC && right->proto && right->has_obj_mname) {
    int32_t owner = index_get(&c->index, right->obj_mname);
    if (owner >= 0) {
      c->alias = true;
      c->alias_key = name_keep(p, name);
      c->alias_owner = name_keep(p, right->obj_mname);
      c->alias_id = owner;
    }
  }
  Name key = c->alias_key;
  if (c->alias && name.n > key.n && memcmp(name.p, key.p, key.n) == 0 &&
      (name.p[key.n] == '.' || name.p[key.n] == '[')) {
    int32_t id = push_def(p, c, name, false, right);
    Def *d = &c->defs[id];
    d->has_alias = true;
    d->alias_key = c->alias_key;
    d->alias_owner = c->alias_owner;
    add_member(p, &c->defs[c->alias_id], id);
    return;
  }
  if (c->alias && !name_eq(key, name)) c->alias = false;
  if (a->left_ident) {
    int32_t id = index_get(&c->index, name);
    if (id >= 0) {
      c->defs[id].init = to_init(right);
      c->defs[id].has_init = true;
    }
    return;
  }
  if (index_get(&c->index, name) >= 0) return;
  int32_t id = (int32_t)c->n;
  const char *proto = find(name, ".prototype");
  if (proto) {
    Name owner = {name.p, (uint32_t)(proto - name.p)};
    uint32_t rest = name.n - owner.n - 10;
    char next = rest ? proto[10] : 0;
    int32_t owner_id = index_get(&c->index, owner);
    if ((next == '.' || next == '[') && owner_id >= 0) add_member(p, &c->defs[owner_id], id);
  }
  push_def(p, c, name, false, right);
  index_put(p, &c->index, c->defs[id].name, id);
}

void stamp_add(P *p, uint32_t at, uint32_t value) {
  Stamp s = {at, value};
  VPUSH(p, p->stamps, s);
}
