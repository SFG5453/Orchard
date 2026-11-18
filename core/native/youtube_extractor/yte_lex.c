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

#define PARSE_ERROR "Could not parse YouTube player JavaScript"

static bool id_start(unsigned char c) {
  return ((c | 32) >= 'a' && (c | 32) <= 'z') || c == '_' || c == '$' || c >= 0x80 || c == '\\';
}
static bool id_part(unsigned char c) { return id_start(c) || (c >= '0' && c <= '9'); }
static bool digit(unsigned char c) { return c >= '0' && c <= '9'; }

// Width of a non-ASCII whitespace or line terminator at i, 0 if none.
static int uspace(const unsigned char *s, uint32_t i, uint32_t n, bool *nl) {
  if (s[i] == 0xC2 && i + 1 < n && s[i + 1] == 0xA0) return 2;
  if (i + 2 >= n) return 0;
  unsigned a = s[i], b = s[i + 1], c = s[i + 2];
  if (a == 0xEF && b == 0xBB && c == 0xBF) return 3;
  if (a == 0xE2 && b == 0x80 && (c == 0xA8 || c == 0xA9)) { *nl = true; return 3; }
  if (a == 0xE2 && b == 0x80 && (c <= 0x8A || c == 0xAF)) return 3;
  if (a == 0xE2 && b == 0x81 && c == 0x9F) return 3;
  if (a == 0xE3 && b == 0x80 && c == 0x80) return 3;
  if (a == 0xE1 && b == 0x9A && c == 0x80) return 3;
  return 0;
}

static void skip(P *p) {
  const unsigned char *s = (const unsigned char *)p->src;
  uint32_t i = p->pos, n = p->len;
  bool nl = false;
  while (i < n) {
    unsigned char c = s[i];
    if (c == '\n' || c == '\r') { nl = true; i++; continue; }
    if (c == ' ' || c == '\t' || c == '\v' || c == '\f') { i++; continue; }
    if (c == '/' && i + 1 < n && s[i + 1] == '/') {
      while (i < n && s[i] != '\n' && s[i] != '\r') i++;
      continue;
    }
    if (c == '/' && i + 1 < n && s[i + 1] == '*') {
      i += 2;
      for (;; i++) {
        if (i + 1 >= n) yte_fail(p, PARSE_ERROR);
        if (s[i] == '*' && s[i + 1] == '/') break;
        if (s[i] == '\n' || s[i] == '\r') nl = true;
      }
      i += 2;
      continue;
    }
    if (c >= 0x80) {
      int w = uspace(s, i, n, &nl);
      if (w) { i += w; continue; }
    }
    break;
  }
  p->pos = i;
  p->t.nl = nl;
}

static int kw_of(const char *s, uint32_t n) {
#define K(word, id) if (n == sizeof(word) - 1 && memcmp(s, word, n) == 0) return id
  switch (s[0]) {
  case 'a': K("async", KW_ASYNC); K("await", KW_AWAIT); K("accessor", KW_ACCESSOR); break;
  case 'b': K("break", KW_BREAK); break;
  case 'c': K("case", KW_CASE); K("catch", KW_CATCH); K("class", KW_CLASS); K("const", KW_CONST);
    K("continue", KW_CONTINUE); break;
  case 'd': K("debugger", KW_DEBUGGER); K("default", KW_DEFAULT); K("delete", KW_DELETE);
    K("do", KW_DO); break;
  case 'e': K("else", KW_ELSE); K("enum", KW_ENUM); K("export", KW_EXPORT); K("extends", KW_EXTENDS);
    break;
  case 'f': K("false", KW_FALSE); K("finally", KW_FINALLY); K("for", KW_FOR);
    K("function", KW_FUNCTION); break;
  case 'g': K("get", KW_GET); break;
  case 'i': K("if", KW_IF); K("import", KW_IMPORT); K("in", KW_IN); K("instanceof", KW_INSTANCEOF);
    break;
  case 'l': K("let", KW_LET); break;
  case 'n': K("new", KW_NEW); K("null", KW_NULL); break;
  case 'o': K("of", KW_OF); break;
  case 'r': K("return", KW_RETURN); break;
  case 's': K("super", KW_SUPER); K("switch", KW_SWITCH); K("static", KW_STATIC); K("set", KW_SET);
    break;
  case 't': K("this", KW_THIS); K("throw", KW_THROW); K("true", KW_TRUE); K("try", KW_TRY);
    K("typeof", KW_TYPEOF); break;
  case 'v': K("var", KW_VAR); K("void", KW_VOID); break;
  case 'w': K("while", KW_WHILE); K("with", KW_WITH); break;
  case 'y': K("yield", KW_YIELD); break;
  }
#undef K
  return KW_NONE;
}

static uint32_t scan_ident(P *p, uint32_t i) {
  const unsigned char *s = (const unsigned char *)p->src;
  while (i < p->len && id_part(s[i])) {
    if (s[i] == '\\') {
      p->t.esc = 1;
      i++;
      if (i < p->len && s[i] == 'u') i++;
      if (i < p->len && s[i] == '{') { while (i < p->len && s[i] != '}') i++; i++; }
      else i += 4;
      continue;
    }
    if (s[i] >= 0x80) {
      bool nl = false;
      if (uspace(s, i, p->len, &nl)) break;
    }
    i++;
  }
  return i;
}

static uint32_t scan_number(P *p, uint32_t i) {
  const unsigned char *s = (const unsigned char *)p->src;
  uint32_t n = p->len;
  p->t.type = T_NUM;
  if (s[i] == '0' && i + 1 < n && strchr("xXoObB", s[i + 1]) && s[i + 1]) {
    i += 2;
    while (i < n && (id_part(s[i]) && s[i] != 'n' && s[i] != '\\')) i++;
  } else {
    while (i < n && (digit(s[i]) || s[i] == '_')) i++;
    if (i < n && s[i] == '.') { i++; while (i < n && (digit(s[i]) || s[i] == '_')) i++; }
    if (i < n && (s[i] == 'e' || s[i] == 'E')) {
      i++;
      if (i < n && (s[i] == '+' || s[i] == '-')) i++;
      while (i < n && (digit(s[i]) || s[i] == '_')) i++;
    }
  }
  if (i < n && s[i] == 'n') { p->t.type = T_BIGINT; i++; }
  if (i < n && id_start(s[i]) && s[i] < 0x80) yte_fail(p, PARSE_ERROR);
  return i;
}

static uint32_t scan_string(P *p, uint32_t i) {
  const char *s = p->src;
  char q = s[i++];
  for (;;) {
    if (i >= p->len || s[i] == '\n' || s[i] == '\r') yte_fail(p, PARSE_ERROR);
    if (s[i] == q) return i + 1;
    if (s[i] == '\\') { i++; if (i < p->len && s[i] == '\r' && i + 1 < p->len && s[i + 1] == '\n') i++; }
    i++;
  }
}

// Scans template characters from i (after ` or }) and sets the template state.
static uint32_t scan_template(P *p, uint32_t i, bool first) {
  const char *s = p->src;
  for (;;) {
    if (i >= p->len) yte_fail(p, PARSE_ERROR);
    if (s[i] == '`') { p->t.tpl = first ? TPL_FULL : TPL_TAIL; return i + 1; }
    if (s[i] == '$' && i + 1 < p->len && s[i + 1] == '{') {
      p->t.tpl = first ? TPL_HEAD : TPL_MIDDLE;
      return i + 2;
    }
    if (s[i] == '\\') i++;
    i++;
  }
}

static uint32_t scan_punct(P *p, uint32_t i) {
  const char *s = p->src + i;
  uint32_t left = p->len - i;
#define AT(k, ch) (left > (k) && s[k] == (ch))
#define R(code, width) do { p->t.punct = (code); return i + (width); } while (0)
  switch (s[0]) {
  case '{': R(P_LBRACE, 1);
  case '}': R(P_RBRACE, 1);
  case '(': R(P_LPAREN, 1);
  case ')': R(P_RPAREN, 1);
  case '[': R(P_LBRACK, 1);
  case ']': R(P_RBRACK, 1);
  case ';': R(P_SEMI, 1);
  case ',': R(P_COMMA, 1);
  case ':': R(P_COLON, 1);
  case '~': R(P_TILDE, 1);
  case '@': R(P_AT, 1);
  case '.': if (AT(1, '.') && AT(2, '.')) R(P_ELLIPSIS, 3); R(P_DOT, 1);
  case '?':
    if (AT(1, '?')) { if (AT(2, '=')) R(P_NULLISH_A, 3); R(P_NULLISH, 2); }
    if (AT(1, '.') && !(left > 2 && digit((unsigned char)s[2]))) R(P_QDOT, 2);
    R(P_QUESTION, 1);
  case '=':
    if (AT(1, '=')) { if (AT(2, '=')) R(P_SEQ, 3); R(P_EQ, 2); }
    if (AT(1, '>')) R(P_ARROW, 2);
    R(P_ASSIGN, 1);
  case '!': if (AT(1, '=')) { if (AT(2, '=')) R(P_SNE, 3); R(P_NE, 2); } R(P_BANG, 1);
  case '<':
    if (AT(1, '<')) { if (AT(2, '=')) R(P_SHL_A, 3); R(P_SHL, 2); }
    if (AT(1, '=')) R(P_LE, 2);
    R(P_LT, 1);
  case '>':
    if (AT(1, '>')) {
      if (AT(2, '>')) { if (AT(3, '=')) R(P_USHR_A, 4); R(P_USHR, 3); }
      if (AT(2, '=')) R(P_SHR_A, 3);
      R(P_SHR, 2);
    }
    if (AT(1, '=')) R(P_GE, 2);
    R(P_GT, 1);
  case '+': if (AT(1, '+')) R(P_INC, 2); if (AT(1, '=')) R(P_ADD_A, 2); R(P_PLUS, 1);
  case '-': if (AT(1, '-')) R(P_DEC, 2); if (AT(1, '=')) R(P_SUB_A, 2); R(P_MINUS, 1);
  case '*':
    if (AT(1, '*')) { if (AT(2, '=')) R(P_POW_A, 3); R(P_POW, 2); }
    if (AT(1, '=')) R(P_MUL_A, 2);
    R(P_STAR, 1);
  case '/': if (AT(1, '=')) R(P_DIV_A, 2); R(P_SLASH, 1);
  case '%': if (AT(1, '=')) R(P_MOD_A, 2); R(P_PERCENT, 1);
  case '&':
    if (AT(1, '&')) { if (AT(2, '=')) R(P_LAND_A, 3); R(P_AND, 2); }
    if (AT(1, '=')) R(P_AND_A, 2);
    R(P_AMP, 1);
  case '|':
    if (AT(1, '|')) { if (AT(2, '=')) R(P_LOR_A, 3); R(P_OR, 2); }
    if (AT(1, '=')) R(P_OR_A, 2);
    R(P_PIPE, 1);
  case '^': if (AT(1, '=')) R(P_XOR_A, 2); R(P_CARET, 1);
  }
#undef AT
#undef R
  yte_fail(p, PARSE_ERROR);
}

void lex_next(P *p) {
  p->prev_end = p->t.end;
  skip(p);
  Tok *t = &p->t;
  uint32_t i = p->pos;
  t->start = i;
  t->esc = 0;
  t->kw = KW_NONE;
  t->punct = P_NONE;
  t->tpl = 0;
  if (i >= p->len) { t->type = T_EOF; t->end = i; return; }
  unsigned char c = (unsigned char)p->src[i];
  if (id_start(c)) {
    t->type = T_IDENT;
    i = scan_ident(p, i);
    if (!t->esc) t->kw = (uint8_t)kw_of(p->src + t->start, i - t->start);
  } else if (digit(c) || (c == '.' && i + 1 < p->len && digit((unsigned char)p->src[i + 1]))) {
    i = scan_number(p, i);
  } else if (c == '"' || c == '\'') {
    t->type = T_STR;
    i = scan_string(p, i);
  } else if (c == '`') {
    t->type = T_TEMPLATE;
    i = scan_template(p, i + 1, true);
  } else if (c == '#') {
    t->type = T_PRIVATE;
    i = scan_ident(p, i + 1);
  } else {
    t->type = T_PUNCT;
    i = scan_punct(p, i);
  }
  t->end = p->pos = i;
}

void lex_regex(P *p) {
  const char *s = p->src;
  uint32_t i = p->t.start + 1;
  bool cls = false;
  for (;;) {
    if (i >= p->len || s[i] == '\n' || s[i] == '\r') yte_fail(p, PARSE_ERROR);
    char c = s[i];
    if (c == '\\') { i += 2; continue; }
    if (c == '[') cls = true;
    else if (c == ']') cls = false;
    else if (c == '/' && !cls) break;
    i++;
  }
  i++;
  while (i < p->len && id_part((unsigned char)s[i])) i++;
  p->t.type = T_REGEX;
  p->t.punct = P_NONE;
  p->t.end = p->pos = i;
}

void lex_template_cont(P *p) {
  if (!is_punct(p, P_RBRACE)) yte_fail(p, PARSE_ERROR);
  p->t.type = T_TEMPLATE;
  p->t.punct = P_NONE;
  p->t.end = p->pos = scan_template(p, p->t.start + 1, false);
}

void lex_seek(P *p, uint32_t pos) {
  p->pos = pos;
  p->t.end = pos;
  lex_next(p);
}

Tok lex_peek(P *p, uint32_t *after) {
  Tok saved = p->t;
  uint32_t pos = p->pos, prev = p->prev_end;
  lex_next(p);
  Tok next = p->t;
  if (after) *after = p->pos;
  p->t = saved;
  p->pos = pos;
  p->prev_end = prev;
  return next;
}

void expect(P *p, int punct) {
  if (!is_punct(p, punct)) yte_fail(p, PARSE_ERROR);
  lex_next(p);
}

bool eat(P *p, int punct) {
  if (!is_punct(p, punct)) return false;
  lex_next(p);
  return true;
}

void semicolon(P *p) {
  if (eat(p, P_SEMI)) return;
  if (is_punct(p, P_RBRACE) || p->t.type == T_EOF || p->t.nl) return;
  yte_fail(p, PARSE_ERROR);
}

double num_value(const char *s, uint32_t n) {
  char buf[128];
  uint32_t k = 0;
  for (uint32_t i = 0; i < n && k + 1 < sizeof buf; i++) if (s[i] != '_') buf[k++] = s[i];
  buf[k] = 0;
  if (k > 1 && buf[0] == '0') {
    int base = 0;
    char b = (char)(buf[1] | 32);
    if (b == 'x') base = 16; else if (b == 'o') base = 8; else if (b == 'b') base = 2;
    if (base) return (double)strtoull(buf + 2, NULL, base);
    if (strspn(buf, "01234567") == k) return (double)strtoull(buf, NULL, 8);
  }
  return strtod(buf, NULL);
}
