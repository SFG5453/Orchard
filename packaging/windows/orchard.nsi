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

Unicode True
ManifestSupportedOS win10
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "Orchard Packages"
Icon "${ICON}"
UninstallIcon "${ICON}"

!include "MUI2.nsh"

Name "Orchard"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\Orchard"
InstallDirRegKey HKCU "Software\Orchard Packages" "InstallLocation"

VIProductVersion "${VERSION}.0"
VIFileVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Orchard"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "Orchard package installer"
VIAddVersionKey "LegalCopyright" "Copyright (C) 2026 SFG545"

!define MUI_ABORTWARNING
!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\orchard-packages.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Orchard Packages"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${LICENSE}"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Section "Orchard Packages" OrchardPackages
  SetShellVarContext current
  ; The default location is the old Orchard Electron install location. Clearing
  ; this exact application directory removes the old direct install on upgrade;
  ; user data and package-service versions live under %APPDATA%\orchard.
  RMDir /r "$INSTDIR"
  SetOutPath "$INSTDIR"
  File /r "${BUNDLE}\*"
  File /oname=orchard.cmd "${LAUNCHER}"
  WriteUninstaller "$INSTDIR\Uninstall Orchard.exe"

  ; Electron-builder used the application ID for its uninstall registry key.
  Delete "$SMPROGRAMS\Orchard.lnk"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\dev.sfg.orchard"
  CreateDirectory "$SMPROGRAMS\Orchard"
  CreateShortCut "$SMPROGRAMS\Orchard\Orchard.lnk" "$INSTDIR\orchard-packages.exe" "" "$INSTDIR\orchard-packages.exe" 0 SW_SHOWNORMAL "" "Launch Orchard"
  CreateShortCut "$DESKTOP\Orchard.lnk" "$INSTDIR\orchard-packages.exe" "" "$INSTDIR\orchard-packages.exe" 0 SW_SHOWNORMAL "" "Launch Orchard"

  WriteRegStr HKCU "Software\Orchard Packages" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "DisplayName" "Orchard"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "Publisher" "SFG545"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "DisplayIcon" "$INSTDIR\orchard-packages.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "UninstallString" "$\"$INSTDIR\Uninstall Orchard.exe$\""
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  Delete "$SMPROGRAMS\Orchard.lnk"
  Delete "$SMPROGRAMS\Orchard\Orchard.lnk"
  Delete "$DESKTOP\Orchard.lnk"
  RMDir "$SMPROGRAMS\Orchard"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\dev.sfg.orchard"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard"
  DeleteRegKey HKCU "Software\Orchard Packages"
  RMDir /r "$INSTDIR"
SectionEnd
