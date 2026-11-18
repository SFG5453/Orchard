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

#include <locale.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

#include "youtube_extractor.h"
#include "yte.h"

#define PARSE_ERROR "Could not parse YouTube player JavaScript"
#define MAX_SOURCE_BYTES (16u * 1024 * 1024)
// Fits MAX_DEPTH nesting regardless of the caller's thread stack.
#define EXTRACT_STACK (1024u * 1024)

typedef struct { char *v; size_t n, cap; } Buf;

typedef struct {
  P *p;
  Coll *c;
  uint8_t *seen;
  Buf out;
} Emitter;

static void buf_add(P *p, Buf *b, const char *s, size_t n) {
  if (b->n + n + 1 > b->cap) {
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < b->n + n + 1) cap *= 2;
    char *grown = realloc(b->v, cap);
    if (!grown) yte_fail(p, "Native player extraction ran out of memory");
    b->v = grown;
    b->cap = cap;
  }
  memcpy(b->v + b->n, s, n);
  b->n += n;
  b->v[b->n] = 0;
}
static void buf_str(P *p, Buf *b, const char *s) { buf_add(p, b, s, strlen(s)); }
static void buf_name(P *p, Buf *b, Name n) { buf_add(p, b, n.p, n.n); }

static uint32_t hash(Name n) {
  uint32_t h = 2166136261u;
  for (uint32_t i = 0; i < n.n; i++) h = (h ^ (uint8_t)n.p[i]) * 16777619u;
  return h;
}

typedef struct { Name *slots; uint32_t mask; Name *list; uint32_t n; } NameSet;

static void set_add(NameSet *s, Name n) {
  for (uint32_t i = hash(n) & s->mask;; i = (i + 1) & s->mask) {
    if (!s->slots[i].p) {
      s->slots[i] = n;
      s->list[s->n++] = n;
      return;
    }
    if (name_eq(s->slots[i], n)) return;
  }
}

// External names referenced by a definition, in first-use order.
static Name *dependencies(Emitter *em, const Def *d, uint32_t *count) {
  P *p = em->p;
  p->refs.n = p->pending.n = p->entries.n = p->cands.n = p->scopes.n = p->fns.n = 0;
  FnCtx ctx = {0};
  VPUSH(p, p->fns, ctx);
  p->depth = 0;
  p->fn_depth = 1;
  lex_seek(p, d->init.start);
  Expr e = parse_assign(p, false);
  if (e.start != d->init.start || e.end != d->init.end) yte_fail(p, PARSE_ERROR);
  uint32_t cap = 16;
  while (cap < (p->entries.n + 1) * 2) cap *= 2;
  NameSet set = {arena_alloc(p, &p->scratch, cap * sizeof(Name)), cap - 1,
                 arena_alloc(p, &p->scratch, (p->entries.n + 1) * sizeof(Name)), 0};
  memset(set.slots, 0, cap * sizeof(Name));
  for (uint32_t i = 0; i < p->entries.n; i++) {
    Entry *en = &p->entries.v[i];
    if (p->refs.v[en->ref].state == 0) set_add(&set, en->name);
  }
  if (!d->variable) {
    Name parent = d->name;
    while (parent.n && parent.p[parent.n - 1] != '.') parent.n--;
    if (parent.n) {
      parent.n--;
      if (parent.n >= 10 && memcmp(parent.p + parent.n - 10, ".prototype", 10) == 0) parent.n -= 10;
      set_add(&set, parent);
    }
  }
  *count = set.n;
  return set.list;
}

static const char *fallback(uint8_t ikind) {
  switch (ikind) {
  case K_OBJECT: case K_NEW: case K_STATIC: case K_COMPUTED: case K_LOGICAL: return "{}";
  case K_ARRAY: return "[]";
  default: return "undefined";
  }
}

static void emit(Emitter *em, int32_t id, int depth) {
  P *p = em->p;
  Coll *c = em->c;
  if (em->seen[id]) return;
  em->seen[id] = 1;
  if (depth > 512) yte_fail(p, "Player dependency graph is too deep");
  const Def *d = &c->defs[id];
  if (d->has_init) {
    size_t mark = arena_mark(&p->scratch);
    uint32_t n;
    Name *deps = dependencies(em, d, &n);
    for (uint32_t i = 0; i < n; i++) {
      if (builtin(deps[i])) continue;
      // Alias members bind their own prototype immediately before use.
      if (d->has_alias && name_eq(deps[i], d->alias_key)) continue;
      int32_t dep = index_get(&c->index, deps[i]);
      if (dep >= 0) emit(em, dep, depth + 1);
    }
    arena_release(&p->scratch, mark);
  }
  if (d->has_alias) {
    buf_name(p, &em->out, d->alias_key);
    buf_str(p, &em->out, " = ");
    buf_name(p, &em->out, d->alias_owner);
    buf_str(p, &em->out, ".prototype;\n");
  }
  if (d->variable) buf_str(p, &em->out, "var ");
  buf_name(p, &em->out, d->name);
  buf_str(p, &em->out, " = ");
  if (!d->has_init) {
    buf_str(p, &em->out, "undefined");
  } else if (!d->init.safe) {
    buf_str(p, &em->out, fallback(d->init.ikind));
  } else if (d->init.kind == K_IDENT && index_get(&c->index, d->init.ident) < 0) {
    buf_str(p, &em->out, "undefined");
  } else {
    buf_add(p, &em->out, p->src + d->init.start, d->init.end - d->init.start);
  }
  buf_str(p, &em->out, ";\n");
  for (uint32_t i = 0; i < d->nmembers; i++) emit(em, d->members[i], depth + 1);
}

static int stamp_cmp(const void *a, const void *b) {
  uint32_t x = ((const Stamp *)a)->at, y = ((const Stamp *)b)->at;
  return x < y ? -1 : x > y;
}

static bool find_timestamp(P *p, const Coll *c, uint32_t *value) {
  qsort(p->stamps.v, p->stamps.n, sizeof(Stamp), stamp_cmp);
  for (uint32_t i = 0; i < c->n; i++) {
    const Def *d = &c->defs[i];
    if (!d->variable || !d->has_init || d->init.kind != K_FUNC) continue;
    uint32_t lo = 0, hi = p->stamps.n;
    while (lo < hi) {
      uint32_t mid = (lo + hi) / 2;
      if (p->stamps.v[mid].at <= d->init.body_start) lo = mid + 1;
      else hi = mid;
    }
    if (lo < p->stamps.n && p->stamps.v[lo].at < d->init.body_end) {
      *value = p->stamps.v[lo].value;
      return true;
    }
  }
  return false;
}

static double now_ms(void) {
#ifdef _WIN32
  LARGE_INTEGER count, freq;
  QueryPerformanceCounter(&count);
  QueryPerformanceFrequency(&freq);
  return count.QuadPart * 1000.0 / freq.QuadPart;
#else
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
#endif
}

static void json_string(P *p, Buf *b, const char *s, size_t n) {
  static const char hex[] = "0123456789abcdef";
  buf_str(p, b, "\"");
  size_t run = 0;
  for (size_t i = 0; i < n; i++) {
    unsigned char c = (unsigned char)s[i];
    if (c >= 0x20 && c != '"' && c != '\\') continue;
    buf_add(p, b, s + run, i - run);
    run = i + 1;
    char esc[7] = {'\\', 0};
    switch (c) {
    case '"': esc[1] = '"'; break;
    case '\\': esc[1] = '\\'; break;
    case '\n': esc[1] = 'n'; break;
    case '\r': esc[1] = 'r'; break;
    case '\t': esc[1] = 't'; break;
    case '\b': esc[1] = 'b'; break;
    case '\f': esc[1] = 'f'; break;
    default: memcpy(esc + 1, "u00", 3); esc[4] = hex[c >> 4]; esc[5] = hex[c & 15]; break;
    }
    buf_str(p, b, esc);
  }
  buf_add(p, b, s + run, n - run);
  buf_str(p, b, "\"");
}

static char *error_json(const char *msg) {
  size_t n = strlen(msg) + 16;
  char *out = malloc(n);
  if (out) snprintf(out, n, "{\"error\":\"%s\"}", msg);
  return out;
}

static void cleanup(P *p, Emitter *em) {
  free(em->seen);
  free(em->out.v);
  coll_free(p->wrapper);
  for (Coll *c = p->coll, *next; c; c = next) { next = c->next; coll_free(c); }
  for (Coll *c = p->done, *next; c; c = next) { next = c->next; coll_free(c); }
  free(p->fns.v);
  free(p->refs.v);
  free(p->pending.v);
  free(p->entries.v);
  free(p->cands.v);
  free(p->scopes.v);
  free(p->stamps.v);
  arena_free(&p->scratch);
  arena_free(&p->keep);
}

static char *extract(const char *src, uint32_t len) {
  P *p = calloc(1, sizeof(P));
  Emitter *em = calloc(1, sizeof(Emitter));
  Buf *json = calloc(1, sizeof(Buf));
  if (!p || !em || !json) {
    free(p);
    free(em);
    free(json);
    return NULL;
  }
  p->src = src;
  p->len = len;
  p->collect = true;
  char *result;
  if (setjmp(p->jb)) {
    free(json->v);
    result = error_json(p->err ? p->err : PARSE_ERROR);
  } else {
    double t0 = now_ms();
    parse_program(p);
    double t1 = now_ms();
    Coll *c = p->wrapper;
    if (!c) yte_fail(p, "Could not find the YouTube player wrapper");
    if (!c->has_param0) yte_fail(p, "Could not find the YouTube player namespace");
    int32_t target = -1;
    for (uint32_t i = 0; i < c->n && target < 0; i++) {
      const Def *d = &c->defs[i];
      if (d->variable && d->has_init && d->init.ikind == K_FUNC && d->init.sigfn) target = (int32_t)i;
    }
    if (target < 0) yte_fail(p, "Could not find the YouTube signature function");
    uint32_t stamp;
    if (!find_timestamp(p, c, &stamp)) yte_fail(p, "Could not find the YouTube signature timestamp");
    double t2 = now_ms();
    p->collect = false;
    p->track = true;
    em->p = p;
    em->c = c;
    em->seen = calloc(c->n ? c->n : 1, 1);
    if (!em->seen) yte_fail(p, "Native player extraction ran out of memory");
    emit(em, target, 0);
    double t3 = now_ms();

    Buf program = {0};
    buf_str(p, &program, "const exportedVars = (function(");
    buf_name(p, &program, c->param0);
    buf_str(p, &program, ") {\nconst window = Object.create(null);\nconst document = {};\n"
                         "const self = window;\n");
    if (em->out.n) buf_add(p, &program, em->out.v, em->out.n);
    buf_str(p, &program, "\nreturn { nsigFunction: ");
    buf_name(p, &program, c->defs[target].name);
    buf_str(p, &program, " };\n})({});\n");
    free(em->out.v);
    em->out = program;

    char tail[256];
    snprintf(tail, sizeof tail,
             ",\"exported\":[\"nsigFunction\"],\"exportedRawValues\":{\"signatureTimestampVar\":"
             "\"%u\"},\"timings\":{\"parseMs\":%.3f,\"analyzeMs\":%.3f,\"emitMs\":%.3f}}",
             stamp, t1 - t0, t2 - t1, t3 - t2);
    buf_str(p, json, "{\"output\":");
    json_string(p, json, program.v, program.n);
    buf_str(p, json, tail);
    result = json->v;
  }
  cleanup(p, em);
  free(p);
  free(em);
  free(json);
  return result;
}

static bool utf8_valid(const uint8_t *s, size_t n) {
  for (size_t i = 0; i < n;) {
    uint8_t c = s[i];
    if (c < 0x80) { i++; continue; }
    size_t w;
    uint32_t cp;
    if (c >= 0xC2 && c <= 0xDF) { w = 2; cp = c & 0x1F; }
    else if (c >= 0xE0 && c <= 0xEF) { w = 3; cp = c & 0x0F; }
    else if (c >= 0xF0 && c <= 0xF4) { w = 4; cp = c & 0x07; }
    else return false;
    if (i + w > n) return false;
    for (size_t k = 1; k < w; k++) {
      if ((s[i + k] & 0xC0) != 0x80) return false;
      cp = (cp << 6) | (s[i + k] & 0x3F);
    }
    if ((w == 3 && cp < 0x800) || (w == 4 && cp < 0x10000) || cp > 0x10FFFF ||
        (cp >= 0xD800 && cp <= 0xDFFF))
      return false;
    i += w;
  }
  return true;
}

typedef struct { const char *src; uint32_t len; char *result; } Job;

static void run_job(Job *job) {
  // JavaScript and JSON numbers require a decimal point.
#ifdef _WIN32
  bool locale_ready = _configthreadlocale(_ENABLE_PER_THREAD_LOCALE) != -1 &&
                      setlocale(LC_NUMERIC, "C") != NULL;
#else
  locale_t numeric = newlocale(LC_NUMERIC_MASK, "C", (locale_t)0);
  locale_t previous = numeric ? uselocale(numeric) : (locale_t)0;
  bool locale_ready = previous != (locale_t)0;
#endif
  job->result = locale_ready ? extract(job->src, job->len) : error_json("Could not set player numeric locale");
#ifndef _WIN32
  if (previous) uselocale(previous);
  if (numeric) freelocale(numeric);
#endif
}

#ifdef _WIN32
static DWORD WINAPI job_entry(LPVOID arg) {
  run_job(arg);
  return 0;
}

static bool run_on_stack(Job *job) {
  HANDLE thread = CreateThread(NULL, EXTRACT_STACK, job_entry, job,
                               STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
  if (!thread) return false;
  WaitForSingleObject(thread, INFINITE);
  CloseHandle(thread);
  return true;
}
#else
static void *job_entry(void *arg) {
  run_job(arg);
  return NULL;
}

static bool run_on_stack(Job *job) {
  pthread_attr_t attr;
  pthread_t thread;
  if (pthread_attr_init(&attr) != 0) return false;
  bool started = pthread_attr_setstacksize(&attr, EXTRACT_STACK) == 0 &&
                 pthread_create(&thread, &attr, job_entry, job) == 0;
  pthread_attr_destroy(&attr);
  if (started) pthread_join(thread, NULL);
  return started;
}
#endif

char *orchard_youtube_extract(const uint8_t *source, size_t length) {
  if (!source || length > MAX_SOURCE_BYTES) return error_json("Invalid player source length");
  if (!utf8_valid(source, length)) return error_json("Player source must be UTF-8");
  Job job = {(const char *)source, (uint32_t)length, NULL};
  if (!run_on_stack(&job)) return error_json("Could not start native player extraction");
  return job.result;
}

void orchard_youtube_extract_free(char *result) { free(result); }
