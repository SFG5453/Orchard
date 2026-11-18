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

#include "youtube_extractor.h"

static int failures;

#define CHECK(cond) do { \
    if (!(cond)) { fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); failures++; } \
  } while (0)

static char *run(const char *src, size_t n) { return orchard_youtube_extract((const uint8_t *)src, n); }

static void expect_error(const char *src, size_t n, const char *message) {
  char *json = run(src, n);
  CHECK(json != NULL);
  if (!json) return;
  CHECK(strncmp(json, "{\"error\":", 9) == 0);
  if (message) CHECK(strstr(json, message) != NULL);
  orchard_youtube_extract_free(json);
}

// Golden output for the fixture player.
static const char kPlayer[] =
  "var _yt_player={};(function(g){var window=this;\n"
  "var w=\"a;b\".split(\";\"),unused=1;\n"
  "var Q=function(a){this.a=a};\n"
  "Q.prototype.get=function(a){return this.a[a]||null};\n"
  "q=Q.prototype;q.set=function(a,b){this.a[a]=b};q.clone=function(){return new Q(H(this.a))};\n"
  "var H=function(a){var b={};for(let c in a)b[c]=a[c];return b},\n"
  "svc=document.createElement(\"div\"),\n"
  "V=function(){return{signatureTimestamp:2.0767e4}},\n"
  "T=function(a,b){var c=w[0],H=1;return b?a+H+svc+cfg.x:c},cfg={x:1,y:[2]},lst=[1,init],init=new Map;\n"
  "g.url=function(a){this.u=a};\n"
  "g.url.prototype=Q.prototype;\n"
  "var AI=function(a,b=\"\",c=\"\"){a=new g.url(a);a.set(\"alr\",\"yes\");c&&(c=T(c,b),a.set(b,c));var d=new Q(b);d.clone(),(e=>e)(Z);return a};var Z=async x=>{for(const [k,v] of x)await v;return`${k}`};\n"
  "})(_yt_player);\n";

static const char kOutput[] =
  "const exportedVars = (function(g) {\n"
  "const window = Object.create(null);\n"
  "const document = {};\n"
  "const self = window;\n"
  "g.url = function(a){this.u=a};\n"
  "var w = \"a;b\".split(\";\");\n"
  "var svc = undefined;\n"
  "var cfg = {};\n"
  "var T = function(a,b){var c=w[0],H=1;return b?a+H+svc+cfg.x:c};\n"
  "var Q = function(a){this.a=a};\n"
  "Q.prototype.get = function(a){return this.a[a]||null};\n"
  "q = Q.prototype;\n"
  "q.set = function(a,b){this.a[a]=b};\n"
  "var H = function(a){var b={};for(let c in a)b[c]=a[c];return b};\n"
  "q = Q.prototype;\n"
  "q.clone = function(){return new Q(H(this.a))};\n"
  "var Z = async x=>{for(const [k,v] of x)await v;return`${k}`};\n"
  "var AI = function(a,b=\"\",c=\"\"){a=new g.url(a);a.set(\"alr\",\"yes\");c&&(c=T(c,b),a.set(b,c));var d=new Q(b);d.clone(),(e=>e)(Z);return a};\n"
  "\n"
  "return { nsigFunction: AI };\n"
  "})({});\n";

static char *json_escape(const char *s) {
  char *out = malloc(strlen(s) * 2 + 1), *o = out;
  for (; *s; s++) {
    if (*s == '\n') { *o++ = '\\'; *o++ = 'n'; continue; }
    if (*s == '"' || *s == '\\') *o++ = '\\';
    *o++ = *s;
  }
  *o = 0;
  return out;
}

static void test_rejects_invalid_or_unrecognized_players(void) {
  expect_error("", 0, "wrapper");
  expect_error("(function(", 10, "Could not parse");
  const char *plain = "(function(g) { var x = 1; })(this);";
  expect_error(plain, strlen(plain), "signature function");
  expect_error(NULL, 1, "Invalid player source length");
  size_t big = 16u * 1024 * 1024 + 1;
  char *spaces = malloc(big);
  memset(spaces, ' ', big);
  expect_error(spaces, big, "Invalid player source length");
  free(spaces);
  expect_error("\xff", 1, "UTF-8");
  expect_error("\xed\xa0\x80", 3, "UTF-8");
}

static void test_does_not_match_markers_in_strings_or_comments(void) {
  const char *src =
    "(function(g) {\n"
    "  // signatureTimestamp: 20702\n"
    "  var x = function() { return \"url=new g.URL(url);url.set('alr','yes')\"; };\n"
    "})(this);";
  expect_error(src, strlen(src), NULL);
}

static void test_matches_reference_output(void) {
  char *json = run(kPlayer, sizeof kPlayer - 1);
  CHECK(json != NULL);
  if (!json) return;
  char *escaped = json_escape(kOutput);
  size_t n = strlen(escaped);
  CHECK(strncmp(json, "{\"output\":\"", 11) == 0);
  CHECK(strncmp(json + 11, escaped, n) == 0 && json[11 + n] == '"');
  CHECK(strstr(json, "\"exportedRawValues\":{\"signatureTimestampVar\":\"20767\"}") != NULL);
  CHECK(strstr(json, "\"exported\":[\"nsigFunction\"]") != NULL);
  const char *timings = strstr(json, "\"timings\":");
  int end = 0;
  if (timings) {
    sscanf(timings, "\"timings\":{\"parseMs\":%*[0-9].%*3[0-9],"
                    "\"analyzeMs\":%*[0-9].%*3[0-9],\"emitMs\":%*[0-9].%*3[0-9]}}%n", &end);
  }
  CHECK(end > 0 && timings[end] == '\0');
  free(escaped);
  orchard_youtube_extract_free(json);
}

static void test_deep_nesting_fails_cleanly(void) {
  size_t depth = 100000;
  char *src = malloc(depth * 2 + 64), *o = src;
  o += sprintf(o, "(function(g){var x=");
  memset(o, '[', depth);
  o += depth;
  *o++ = '1';
  memset(o, ']', depth);
  o += depth;
  o += sprintf(o, ";})(this);");
  expect_error(src, (size_t)(o - src), "Could not parse");
  free(src);
}

int main(void) {
  setlocale(LC_NUMERIC, "");
  test_rejects_invalid_or_unrecognized_players();
  test_does_not_match_markers_in_strings_or_comments();
  test_matches_reference_output();
  test_deep_nesting_fails_cleanly();
  if (failures) {
    fprintf(stderr, "%d check(s) failed\n", failures);
    return 1;
  }
  puts("youtube extractor tests passed");
  return 0;
}
