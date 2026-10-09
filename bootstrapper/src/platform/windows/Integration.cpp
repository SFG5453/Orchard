/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"

#include "network/Http.hpp"
#include "platform/windows/Wide.hpp"
#include "ui/ProgressUi.hpp"
#include "util/Files.hpp"
#include "util/Log.hpp"

#include <objbase.h>
#include <objidl.h>
#include <propidl.h>
#include <propsys.h>
#include <shlobj.h>
#include <shobjidl.h>

#include <cstring>

namespace orchard::boot {
std::unique_ptr<HttpSession> createWinHttpSession();
std::unique_ptr<ProgressUi> createWin32Ui();
} // namespace orchard::boot

namespace orchard::boot::platform {

namespace {

constexpr wchar_t kUninstallKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Orchard";
constexpr char kRegistryPrefix[] = "registry:HKCU\\";
// Matches SetCurrentProcessExplicitAppUserModelID in app/src/main.cpp so
// pinned shortcuts and the running window share one taskbar button.
constexpr wchar_t kAppUserModelId[] = L"dev.sfg.orchard";
// PKEY_AppUserModel_ID, spelled out to avoid linking propsys for one GUID.
constexpr PROPERTYKEY kAppUserModelIdKey = {
    {0x9F4C2855, 0x9F79, 0x4B39, {0xA8, 0xD0, 0xE1, 0xD4, 0x2D, 0xE1, 0xD5, 0xF3}}, 5};

template <typename T> struct ComPtr {
  T *p = nullptr;
  ~ComPtr() {
    if (p)
      p->Release();
  }
  T **out() { return &p; }
  T *operator->() const { return p; }
};

fs::path knownFolder(REFKNOWNFOLDERID id) {
  wchar_t *folder = nullptr;
  fs::path out;
  if (SUCCEEDED(SHGetKnownFolderPath(id, KF_FLAG_CREATE, nullptr, &folder)))
    out = folder;
  CoTaskMemFree(folder);
  return out;
}

void createShortcut(const fs::path &link, const fs::path &target, const fs::path &workingDirectory) {
  const HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  {
    ComPtr<IShellLinkW> shell;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_IShellLinkW,
                                reinterpret_cast<void **>(shell.out()))))
      throw Error("cannot create the Start menu shortcut");
    shell->SetPath(target.c_str());
    shell->SetWorkingDirectory(workingDirectory.c_str());
    shell->SetIconLocation(target.c_str(), 0);
    shell->SetDescription(L"Orchard");
    ComPtr<IPropertyStore> properties;
    if (SUCCEEDED(shell->QueryInterface(IID_IPropertyStore, reinterpret_cast<void **>(properties.out())))) {
      PROPVARIANT value;
      PropVariantInit(&value);
      value.vt = VT_LPWSTR;
      const std::size_t bytes = (wcslen(kAppUserModelId) + 1) * sizeof(wchar_t);
      value.pwszVal = static_cast<wchar_t *>(CoTaskMemAlloc(bytes));
      if (value.pwszVal) {
        std::memcpy(value.pwszVal, kAppUserModelId, bytes);
        properties->SetValue(kAppUserModelIdKey, value);
        properties->Commit();
      }
      PropVariantClear(&value);
    }
    ComPtr<IPersistFile> file;
    if (FAILED(shell->QueryInterface(IID_IPersistFile, reinterpret_cast<void **>(file.out()))) ||
        FAILED(file->Save(link.c_str(), TRUE)))
      throw Error("cannot save the Start menu shortcut");
  }
  if (SUCCEEDED(init))
    CoUninitialize();
}

void setString(HKEY key, const wchar_t *name, const std::wstring &value) {
  RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE *>(value.c_str()),
                 static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
}

void setDword(HKEY key, const wchar_t *name, DWORD value) {
  RegSetValueExW(key, name, 0, REG_DWORD, reinterpret_cast<const BYTE *>(&value), sizeof value);
}

} // namespace

std::vector<std::string> registerInstallation(const fs::path &root, const fs::path &bootstrapper, const fs::path &,
                                              const std::string &version) {
  std::vector<std::string> created;
  const fs::path programs = knownFolder(FOLDERID_Programs);
  if (!programs.empty()) {
    const fs::path link = programs / L"Orchard.lnk";
    createShortcut(link, bootstrapper, root);
    created.push_back(toUtf8(link));
  }

  HKEY key = nullptr;
  if (RegCreateKeyExW(HKEY_CURRENT_USER, kUninstallKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) ==
      ERROR_SUCCESS) {
    const std::wstring exe = L"\"" + bootstrapper.wstring() + L"\"";
    setString(key, L"DisplayName", L"Orchard");
    setString(key, L"DisplayVersion", widen(version));
    setString(key, L"Publisher", L"SFG545");
    setString(key, L"DisplayIcon", bootstrapper.wstring() + L",0");
    setString(key, L"InstallLocation", root.wstring());
    setString(key, L"UninstallString", exe + L" --uninstall");
    setString(key, L"QuietUninstallString", exe + L" --uninstall --yes");
    setDword(key, L"NoModify", 1);
    setDword(key, L"NoRepair", 1);
    RegCloseKey(key);
    created.push_back(std::string(kRegistryPrefix) + narrow(kUninstallKey));
  }
  return created;
}

void unregisterInstallation(const std::vector<std::string> &created) {
  for (const std::string &entry : created) {
    if (entry.starts_with(kRegistryPrefix))
      RegDeleteTreeW(HKEY_CURRENT_USER, widen(entry.substr(sizeof kRegistryPrefix - 1)).c_str());
    else
      DeleteFileW(widen(entry).c_str());
  }
}

void removeInstallRoot(const fs::path &root) {
  // Everything but the running Orchard.exe (and the open log) goes now.
  std::error_code error;
  for (const auto &entry : fs::directory_iterator(root, error)) {
    std::error_code ignored;
    fs::remove_all(entry.path(), ignored);
  }
  // cmd outlives us by a couple of seconds and removes the rest.
  wchar_t system[MAX_PATH];
  GetSystemDirectoryW(system, MAX_PATH);
  std::wstring command = std::wstring(L"\"") + system + L"\\cmd.exe\" /d /s /c \"ping -n 3 127.0.0.1 > nul & rmdir /s /q \"" +
                         root.wstring() + L"\"\"";
  STARTUPINFOW startup{};
  startup.cb = sizeof startup;
  PROCESS_INFORMATION info{};
  if (CreateProcessW(nullptr, command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | DETACHED_PROCESS, nullptr,
                     system, &startup, &info)) {
    CloseHandle(info.hThread);
    CloseHandle(info.hProcess);
  } else {
    log::warn("cannot schedule removal of " + toUtf8(root) + ": " + lastErrorText());
  }
}

std::unique_ptr<HttpSession> createHttpSession() { return createWinHttpSession(); }

std::unique_ptr<ProgressUi> createGraphicalUi() { return createWin32Ui(); }

} // namespace orchard::boot::platform
