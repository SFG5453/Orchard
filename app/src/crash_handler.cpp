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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "crash_handler.h"

#if defined(_WIN32)

#include <windows.h>
#include <dbghelp.h>

#include <cstdarg>
#include <csignal>
#include <cstdio>
#include <cwchar>
#include <exception>

namespace {

// Everything here runs on a broken process: no Qt, no heap-happy containers.
// If the crash handler crashes, we have achieved crash-ception. Please don't.

// Stack buffer plus WriteFile: CRT streams take locks and may allocate on a corrupt heap.
void report(const char *format, ...) {
  char line[512];
  va_list arguments;
  va_start(arguments, format);
  const int length = std::vsnprintf(line, sizeof(line), format, arguments);
  va_end(arguments);
  if (length <= 0)
    return;
  DWORD written = 0;
  WriteFile(GetStdHandle(STD_ERROR_HANDLE), line,
            static_cast<DWORD>(length < int(sizeof(line)) ? length : sizeof(line) - 1),
            &written, nullptr);
}

void printAddress(const char *label, DWORD64 address) {
  HMODULE module = nullptr;
  wchar_t path[MAX_PATH] = L"?";
  if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                             GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                         reinterpret_cast<LPCWSTR>(address), &module)) {
    GetModuleFileNameW(module, path, MAX_PATH);
  }
  const wchar_t *name = wcsrchr(path, L'\\');
  name = name ? name + 1 : path;
  const DWORD64 offset = module ? address - reinterpret_cast<DWORD64>(module) : address;
  report("  %s %ls+0x%llx\n", label, name,
               static_cast<unsigned long long>(offset));
}

void printStack(const CONTEXT *faultContext) {
  CONTEXT context = *faultContext;
  for (int frame = 0; frame < 48 && context.Rip; ++frame) {
    char label[16];
    std::snprintf(label, sizeof(label), "#%02d", frame);
    printAddress(label, context.Rip);

    DWORD64 imageBase = 0;
    PRUNTIME_FUNCTION function = RtlLookupFunctionEntry(context.Rip, &imageBase, nullptr);
    if (!function) {
      // Leaf function: return address sits at the stack pointer.
      context.Rip = *reinterpret_cast<DWORD64 *>(context.Rsp);
      context.Rsp += 8;
      continue;
    }
    void *handlerData = nullptr;
    DWORD64 establisherFrame = 0;
    RtlVirtualUnwind(UNW_FLAG_NHANDLER, imageBase, context.Rip, function, &context,
                     &handlerData, &establisherFrame, nullptr);
  }
}

void writeMiniDump(EXCEPTION_POINTERS *pointers) {
  wchar_t path[MAX_PATH];
  const DWORD length = GetModuleFileNameW(nullptr, path, MAX_PATH);
  wchar_t *slash = length ? wcsrchr(path, L'\\') : nullptr;
  if (!slash || (slash - path) + 20 >= MAX_PATH)
    return;
  wcscpy(slash + 1, L"orchard-crash.dmp");

  HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                            FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    // Install dir may be read-only (Program Files); fall back to %TEMP%.
    const DWORD tempLength = GetTempPathW(MAX_PATH - 20, path);
    if (!tempLength)
      return;
    wcscpy(path + tempLength, L"orchard-crash.dmp");
    file = CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                       FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE)
      return;
  }

  MINIDUMP_EXCEPTION_INFORMATION info{GetCurrentThreadId(), pointers, FALSE};
  const auto type = static_cast<MINIDUMP_TYPE>(MiniDumpWithIndirectlyReferencedMemory |
                                               MiniDumpWithThreadInfo |
                                               MiniDumpWithUnloadedModules);
  const BOOL written = MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(), file,
                                         type, pointers ? &info : nullptr, nullptr, nullptr);
  CloseHandle(file);
  if (written)
    report("Crash dump written to %ls\n", path);
}

LONG WINAPI onUnhandledException(EXCEPTION_POINTERS *pointers) {
  static volatile LONG entered = 0;
  if (InterlockedExchange(&entered, 1))
    return EXCEPTION_CONTINUE_SEARCH;

  const EXCEPTION_RECORD *record = pointers->ExceptionRecord;
  report("\n=== Orchard crashed: exception 0x%08lx on thread %lu ===\n",
               record->ExceptionCode, GetCurrentThreadId());
  if (record->ExceptionCode == EXCEPTION_ACCESS_VIOLATION && record->NumberParameters >= 2) {
    const ULONG_PTR mode = record->ExceptionInformation[0];
    report("  %s address 0x%llx\n",
                 mode == 0 ? "read" : mode == 1 ? "write" : "execute",
                 static_cast<unsigned long long>(record->ExceptionInformation[1]));
  }
  printAddress("at", reinterpret_cast<DWORD64>(record->ExceptionAddress));
  report("Stack:\n");
  printStack(pointers->ContextRecord);

  writeMiniDump(pointers);
  // Let Windows Error Reporting run too: Event Viewer and LocalDumps still see the crash.
  return EXCEPTION_CONTINUE_SEARCH;
}

// abort(), std::terminate and pure virtual calls skip the SEH filter; raise a
// synthetic exception so they get the same report.
[[noreturn]] void raiseFatal(DWORD code) {
  RaiseException(code, EXCEPTION_NONCONTINUABLE, 0, nullptr);
  TerminateProcess(GetCurrentProcess(), code);
  for (;;) {
  }
}

constexpr DWORD kAbortCode = 0xE0A0B001;
constexpr DWORD kTerminateCode = 0xE0A0B002;
constexpr DWORD kPureCallCode = 0xE0A0B003;

} // namespace

void installCrashHandler() {
  SetUnhandledExceptionFilter(onUnhandledException);
  std::signal(SIGABRT, [](int) { raiseFatal(kAbortCode); });
  std::set_terminate([] { raiseFatal(kTerminateCode); });
  _set_purecall_handler([] { raiseFatal(kPureCallCode); });
  // Keep abort() from showing its own dialog before SIGABRT reaches us.
  _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
}

#else

void installCrashHandler() {}

#endif
