/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"

#include "platform/windows/Wide.hpp"
#include "util/Error.hpp"

#include <shellapi.h>
#include <shlobj.h>

#include <cstdio>
#include <io.h>
#include <thread>

namespace orchard::boot::platform {

namespace {

bool consoleAttached = false;

std::wstring quoteArgument(const std::wstring &arg) {
  if (!arg.empty() && arg.find_first_of(L" \t\n\v\"") == std::wstring::npos)
    return arg;
  // CommandLineToArgvW rules: backslashes only escape when a quote follows.
  std::wstring out = L"\"";
  for (auto it = arg.begin();; ++it) {
    std::size_t slashes = 0;
    while (it != arg.end() && *it == L'\\') {
      ++it;
      ++slashes;
    }
    if (it == arg.end()) {
      out.append(slashes * 2, L'\\');
      break;
    }
    out.append(*it == L'"' ? slashes * 2 + 1 : slashes, L'\\');
    out.push_back(*it);
  }
  return out + L"\"";
}

} // namespace

void initProcess() {
  // GUI subsystem: borrow the parent's console when started from a terminal.
  // Pipes from QProcess are already wired up and stay untouched.
  if (GetFileType(GetStdHandle(STD_ERROR_HANDLE)) == FILE_TYPE_UNKNOWN && AttachConsole(ATTACH_PARENT_PROCESS)) {
    std::freopen("CONOUT$", "w", stdout);
    std::freopen("CONOUT$", "w", stderr);
    std::freopen("CONIN$", "r", stdin);
    consoleAttached = true;
  }
}

std::vector<std::string> arguments(int, char **) {
  int count = 0;
  wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &count);
  std::vector<std::string> out;
  for (int i = 1; i < count; ++i)
    out.push_back(narrow(argv[i]));
  LocalFree(argv);
  return out;
}

std::string platformId() {
#if defined(_M_X64) || defined(__x86_64__)
  return "win-x86_64";
#elif defined(_M_ARM64) || defined(__aarch64__)
  return "win-arm64";
#else
  return {};
#endif
}

fs::path selfExecutable() {
  std::wstring buffer(MAX_PATH, L'\0');
  while (true) {
    const DWORD size = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (size < buffer.size()) {
      buffer.resize(size);
      return buffer;
    }
    buffer.resize(buffer.size() * 2);
  }
}

std::string bootstrapperFileName() { return "Orchard.exe"; }

// %LOCALAPPDATA%\Programs\Orchard: per-user, no admin, where Windows expects user installs.
fs::path defaultInstallRoot() {
  wchar_t *folder = nullptr;
  fs::path root;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_UserProgramFiles, KF_FLAG_CREATE, nullptr, &folder)))
    root = folder;
  CoTaskMemFree(folder);
  if (root.empty()) {
    const wchar_t *local = _wgetenv(L"LOCALAPPDATA");
    if (!local)
      throw Error("cannot find the user's program folder");
    root = fs::path(local) / L"Programs";
  }
  return root / L"Orchard";
}

std::FILE *openFile(const fs::path &path, const char *mode) { return _wfopen(path.c_str(), widen(mode).c_str()); }

bool seekFile(std::FILE *file, std::uint64_t offset) {
  return _fseeki64(file, static_cast<__int64>(offset), SEEK_SET) == 0;
}

void syncFile(std::FILE *file) { FlushFileBuffers(reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file)))); }

void makeExecutable(const fs::path &) {}

void replaceExecutable(const fs::path &target, const fs::path &replacement, const fs::path &backup) {
  // Windows refuses to overwrite or delete a running .exe but happily renames it.
  fs::path spare = backup;
  DeleteFileW(spare.c_str());
  if (GetFileAttributesW(spare.c_str()) != INVALID_FILE_ATTRIBUTES)
    spare += L"." + std::to_wstring(GetCurrentProcessId());
  const bool hadTarget = GetFileAttributesW(target.c_str()) != INVALID_FILE_ATTRIBUTES;
  if (hadTarget && !MoveFileExW(target.c_str(), spare.c_str(), MOVEFILE_REPLACE_EXISTING))
    throw Error("cannot move the old bootstrapper aside: " + lastErrorText());
  if (!MoveFileExW(replacement.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
    const std::string reason = lastErrorText();
    if (hadTarget)
      MoveFileExW(spare.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING);
    throw Error("cannot install the new bootstrapper: " + reason);
  }
}

std::unique_ptr<FileLock> FileLock::tryAcquire(const fs::path &path) {
  fs::create_directories(path.parent_path());
  HANDLE file = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                            FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE)
    throw Error("cannot open lock file: " + lastErrorText());
  OVERLAPPED overlapped{};
  if (!LockFileEx(file, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &overlapped)) {
    CloseHandle(file);
    return nullptr;
  }
  return std::unique_ptr<FileLock>(new FileLock(reinterpret_cast<std::intptr_t>(file)));
}

std::unique_ptr<FileLock> FileLock::acquire(const fs::path &path, std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (true) {
    if (auto lock = tryAcquire(path))
      return lock;
    if (std::chrono::steady_clock::now() > deadline)
      return nullptr;
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
  }
}

FileLock::~FileLock() {
  HANDLE file = reinterpret_cast<HANDLE>(handle_);
  OVERLAPPED overlapped{};
  UnlockFileEx(file, 0, 1, 0, &overlapped);
  CloseHandle(file);
}

Environment currentEnvironment() {
  Environment env;
  wchar_t *block = GetEnvironmentStringsW();
  for (const wchar_t *entry = block; entry && *entry; entry += wcslen(entry) + 1) {
    const std::wstring_view text(entry);
    // "=C:=C:\dir" entries are per-drive working directories, not variables.
    const std::size_t eq = text.find(L'=', 1);
    if (text.front() != L'=' && eq != std::wstring_view::npos)
      env[narrow(text.substr(0, eq))] = narrow(text.substr(eq + 1));
  }
  FreeEnvironmentStringsW(block);
  return env;
}

std::string environmentKey(const Environment &env, const std::string &name) {
  for (const auto &[key, value] : env) {
    if (CompareStringOrdinal(widen(key).c_str(), -1, widen(name).c_str(), -1, TRUE) == CSTR_EQUAL)
      return key;
  }
  return name;
}

char pathListSeparator() { return ';'; }

Process spawn(const fs::path &program, const SpawnOptions &options) {
  std::wstring commandLine = quoteArgument(program.wstring());
  for (const std::string &arg : options.args)
    commandLine += L" " + quoteArgument(widen(arg));
  std::wstring block;
  if (options.env) {
    for (const auto &[name, value] : *options.env)
      block += widen(name) + L"=" + widen(value) + L'\0';
    block += L'\0';
  }
  STARTUPINFOW startup{};
  startup.cb = sizeof startup;
  DWORD flags = CREATE_UNICODE_ENVIRONMENT;
  BOOL inherit = FALSE;
  if (options.background) {
    flags |= DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP;
  } else if (GetStdHandle(STD_OUTPUT_HANDLE)) {
    // Delegated bootstrapper commands print JSON on our stdout.
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    inherit = TRUE;
  }
  PROCESS_INFORMATION info{};
  if (!CreateProcessW(program.c_str(), commandLine.data(), nullptr, nullptr, inherit, flags,
                      options.env ? block.data() : nullptr,
                      options.workingDirectory.empty() ? nullptr : options.workingDirectory.c_str(), &startup, &info))
    throw Error("cannot start " + narrow(program.wstring()) + ": " + lastErrorText());
  CloseHandle(info.hThread);
  return Process{reinterpret_cast<std::intptr_t>(info.hProcess), static_cast<int>(info.dwProcessId)};
}

std::optional<int> waitProcess(Process &process, std::chrono::milliseconds timeout) {
  HANDLE handle = reinterpret_cast<HANDLE>(process.handle);
  if (!handle || WaitForSingleObject(handle, static_cast<DWORD>(timeout.count())) != WAIT_OBJECT_0)
    return std::nullopt;
  DWORD code = 0;
  GetExitCodeProcess(handle, &code);
  return static_cast<int>(code);
}

void releaseProcess(Process &process) {
  if (process.handle)
    CloseHandle(reinterpret_cast<HANDLE>(process.handle));
  process = {};
}

bool waitForPid(int pid, std::chrono::milliseconds timeout) {
  HANDLE handle = OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(pid));
  if (!handle)
    return true;
  const bool exited = WaitForSingleObject(handle, static_cast<DWORD>(timeout.count())) == WAIT_OBJECT_0;
  CloseHandle(handle);
  return exited;
}

int currentPid() { return static_cast<int>(GetCurrentProcessId()); }

bool stderrIsTerminal() { return consoleAttached || GetFileType(GetStdHandle(STD_ERROR_HANDLE)) == FILE_TYPE_CHAR; }

} // namespace orchard::boot::platform
