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

#include <stdlib.h>
#include <string.h>

#include "yte.h"

#define OOM "Native player extraction ran out of memory"

_Noreturn void yte_fail(P *p, const char *msg) {
  if (!p->err) p->err = msg;
  longjmp(p->jb, 1);
}

void yte_grow(P *p, void **v, uint32_t *cap, size_t size) {
  uint32_t next = *cap ? *cap * 2 : 16;
  void *grown = realloc(*v, (size_t)next * size);
  if (!grown) yte_fail(p, OOM);
  *v = grown;
  *cap = next;
}

void *arena_alloc(P *p, Arena *a, size_t n) {
  n = (n + 7) & ~(size_t)7;
  Chunk *c = a->head;
  if (!c || c->cap - c->used < n) {
    size_t cap = n > 65536 ? n : 65536;
    Chunk *fresh = malloc(sizeof(Chunk) + cap);
    if (!fresh) yte_fail(p, OOM);
    fresh->next = c;
    fresh->base = c ? c->base + c->cap : 0;
    fresh->used = 0;
    fresh->cap = cap;
    a->head = c = fresh;
  }
  void *out = c->data + c->used;
  c->used += n;
  return out;
}

size_t arena_mark(Arena *a) { return a->head ? a->head->base + a->head->used : 0; }

void arena_release(Arena *a, size_t mark) {
  while (a->head && a->head->base >= mark && a->head->base > 0) {
    Chunk *next = a->head->next;
    free(a->head);
    a->head = next;
  }
  if (a->head) a->head->used = mark - a->head->base;
}

void arena_free(Arena *a) {
  while (a->head) {
    Chunk *next = a->head->next;
    free(a->head);
    a->head = next;
  }
}

bool name_is(Name a, const char *s) {
  size_t n = strlen(s);
  return a.n == n && memcmp(a.p, s, n) == 0;
}

Name name_cat(P *p, Arena *a, Name a1, const char *mid, Name a2, const char *tail) {
  size_t m = strlen(mid), t = strlen(tail), n = a1.n + m + a2.n + t;
  char *out = arena_alloc(p, a, n ? n : 1);
  memcpy(out, a1.p, a1.n);
  memcpy(out + a1.n, mid, m);
  if (a2.n) memcpy(out + a1.n + m, a2.p, a2.n);
  memcpy(out + a1.n + m + a2.n, tail, t);
  return (Name){out, (uint32_t)n};
}

Name name_keep(P *p, Name n) {
  if (n.p >= p->src && n.p + n.n <= p->src + p->len) return n;
  char *out = arena_alloc(p, &p->keep, n.n ? n.n : 1);
  memcpy(out, n.p, n.n);
  return (Name){out, n.n};
}

void scope_open(P *p, bool fn) { scope_open_at(p, p->pending.n, fn); }

void scope_open_at(P *p, uint32_t pending, bool fn) {
  if (!p->track) return;
  Scope s = {pending, NULL, 0, 0, fn};
  VPUSH(p, p->scopes, s);
}

void scope_declare(P *p, Name n, bool var) {
  if (!p->track || !p->scopes.n) return;
  Scope *s = &p->scopes.v[p->scopes.n - 1];
  if (var) {
    for (uint32_t i = p->scopes.n; i-- > 0;) {
      if (p->scopes.v[i].fn) { s = &p->scopes.v[i]; break; }
    }
  }
  if (s->n == s->cap) {
    uint32_t cap = s->cap ? s->cap * 2 : 8;
    Name *grown = arena_alloc(p, &p->scratch, cap * sizeof(Name));
    if (s->n) memcpy(grown, s->decls, s->n * sizeof(Name));
    s->decls = grown;
    s->cap = cap;
  }
  s->decls[s->n++] = n;
}

void scope_close(P *p) {
  if (!p->track) return;
  Scope *s = &p->scopes.v[p->scopes.n - 1];
  uint32_t out = s->pending;
  for (uint32_t i = s->pending; i < p->pending.n; i++) {
    Ref *r = &p->refs.v[p->pending.v[i]];
    if (r->state) continue;
    bool local = false;
    for (uint32_t d = 0; d < s->n && !local; d++) local = name_eq(r->name, s->decls[d]);
    if (local) r->state = 1;
    else p->pending.v[out++] = p->pending.v[i];
  }
  p->pending.n = out;
  p->scopes.n--;
}

int32_t ref_add(P *p, Name n) {
  if (!p->track) return -1;
  int32_t id = (int32_t)p->refs.n;
  Ref r = {n, 0};
  VPUSH(p, p->refs, r);
  VPUSH(p, p->pending, id);
  entry_add(p, n, id);
  return id;
}

void entry_add(P *p, Name n, int32_t ref) {
  if (!p->track || ref < 0) return;
  Entry e = {n, ref};
  VPUSH(p, p->entries, e);
}

void ref_bind(P *p, int32_t ref) {
  if (!p->track || ref < 0) return;
  p->refs.v[ref].state = 2;
  scope_declare(p, p->refs.v[ref].name, false);
}

void cand_push(P *p, int32_t ref) {
  if (!p->track || ref < 0) return;
  VPUSH(p, p->cands, ref);
}
