/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include <windows.h>
#include <wincrypt.h>
#include <stdio.h>
// Trusts the test CA inside the throwaway prefix so WinHTTP verifies it like a real one.
int main(int argc, char **argv) {
  FILE *f = fopen(argv[1], "rb"); unsigned char buf[8192]; DWORD n = fread(buf, 1, sizeof buf, f); fclose(f);
  BOOL ok = CertAddEncodedCertificateToSystemStoreA("Root", buf, n);
  printf("added=%d err=%lu\n", ok, GetLastError());
  return ok ? 0 : 1;
}
