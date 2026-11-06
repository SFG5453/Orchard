/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"
#include "platform/windows/Wide.hpp"
#include "util/Error.hpp"
#include "util/Files.hpp"

#include <commctrl.h>
#include <shlobj.h>

namespace orchard::boot::platform {
namespace {
fs::path knownFolder(REFKNOWNFOLDERID id) {
  PWSTR value = nullptr;
  if (FAILED(SHGetKnownFolderPath(id, KF_FLAG_DEFAULT, nullptr, &value)))
    throw Error("Could not find the Orchard data folder");
  fs::path result(value);
  CoTaskMemFree(value);
  return result;
}
void deleteKey(const wchar_t *path) {
  const LSTATUS result = RegDeleteTreeW(HKEY_CURRENT_USER, path);
  if (result != ERROR_SUCCESS && result != ERROR_FILE_NOT_FOUND)
    throw Error("Could not remove Orchard settings: " + lastErrorText(result));
}
}

void troubleshoot(int pid, bool crashed) {
  const TASKDIALOG_BUTTON buttons[] = {{101, L"Fix missing window"}, {102, L"Clear app data"}};
  TASKDIALOGCONFIG config{};
  config.cbSize = sizeof(config);
  config.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION | TDF_USE_COMMAND_LINKS;
  config.dwCommonButtons = TDCBF_CLOSE_BUTTON;
  config.pszWindowTitle = L"Orchard Troubleshooter";
  config.pszMainInstruction = crashed ? L"Orchard closed while starting" : L"Orchard Troubleshooter";
  config.pszContent = L"Choose an action.";
  config.cButtons = 2;
  config.pButtons = buttons;
  int choice = 0;
  if (FAILED(TaskDialogIndirect(&config, &choice, nullptr, nullptr)) || (choice != 101 && choice != 102))
    return;
  if (choice == 102 && MessageBoxW(nullptr,
      L"Clear Orchard app data and settings? This deletes downloads and local library data. Saved system credentials may remain.",
      L"Orchard Troubleshooter", MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2) != IDYES)
    return;
  if (pid > 0) {
    if (MessageBoxW(nullptr, L"Quit Orchard completely, then select OK.", L"Orchard Troubleshooter",
                    MB_OKCANCEL | MB_ICONINFORMATION) != IDOK)
      return;
    if (!waitForPid(pid, std::chrono::seconds(3))) {
      MessageBoxW(nullptr, L"Orchard is still running. Quit Orchard and try again.", L"Orchard Troubleshooter", MB_OK | MB_ICONERROR);
      return;
    }
  }
  try {
    if (choice == 101) {
      deleteKey(L"Software\\SFG545\\Orchard\\window");
    } else {
      const fs::path roaming = knownFolder(FOLDERID_RoamingAppData) / "SFG545" / "Orchard";
      const fs::path local = knownFolder(FOLDERID_LocalAppData) / "SFG545" / "Orchard";
      deleteKey(L"Software\\SFG545\\Orchard");
      if (!removeTree(roaming) || !removeTree(local))
        throw Error("Could not remove all Orchard app data");
    }
    MessageBoxW(nullptr, L"Reset complete. Start Orchard again.", L"Orchard Troubleshooter", MB_OK | MB_ICONINFORMATION);
  } catch (const std::exception &e) {
    MessageBoxW(nullptr, widen(e.what()).c_str(), L"Orchard Troubleshooter", MB_OK | MB_ICONERROR);
  }
}
} // namespace orchard::boot::platform
