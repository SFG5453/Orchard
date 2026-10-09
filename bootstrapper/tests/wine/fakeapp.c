/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include <stdio.h>
#include <stdlib.h>
#include <windows.h>
// Stand-in for orchard.exe: records its arguments and launch environment.
int wmain(int argc, wchar_t **argv) {
  const wchar_t *out = _wgetenv(L"ORCHARD_TEST_OUT");
  FILE *f = _wfopen(out, L"w");
  for (int i = 1; i < argc; ++i) fwprintf(f, L"arg=%ls\n", argv[i]);
  const wchar_t *names[] = {L"ORCHARD_MODELS_DIR", L"ORCHARD_BOOTSTRAPPER", L"ORCHARD_INSTALLED_VERSION", L"PATH"};
  for (int i = 0; i < 4; ++i) { const wchar_t *v = _wgetenv(names[i]); fwprintf(f, L"%ls=%ls\n", names[i], v ? v : L""); }
  fclose(f);
  return 0;
}
