<#
 Copyright (C) 2026 SFG545

 This file is part of Orchard.

 Orchard is free software: you can redistribute it and/or modify it under the
 terms of the GNU Affero General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any
 later version.

 Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 details.

 You should have received a copy of the GNU Affero General Public License
 along with Orchard. If not, see <https://www.gnu.org/licenses/>.
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('x64', 'arm64')]
  [string] $Architecture
)

$ErrorActionPreference = 'Stop'

function Find-Makensis {
  $command = Get-Command makensis.exe -ErrorAction SilentlyContinue
  if ($command) {
    return $command.Source
  }

  $candidates = @(
    "${env:ProgramFiles(x86)}\NSIS\makensis.exe",
    "$env:ProgramFiles\NSIS\makensis.exe"
  ) | Where-Object { $_ -and (Test-Path $_) }
  $candidateList = @($candidates)
  if ($candidateList.Count -gt 0) {
    return $candidateList[0]
  }

  $choco = Get-Command choco.exe -ErrorAction SilentlyContinue
  if (-not $choco) {
    throw 'NSIS is not installed and Chocolatey is unavailable.'
  }
  & $choco.Source install nsis --yes --no-progress | Out-Host
  if ($LASTEXITCODE -ne 0) {
    throw "Chocolatey could not install NSIS (exit code $LASTEXITCODE)."
  }

  $candidates = @(
    "${env:ProgramFiles(x86)}\NSIS\makensis.exe",
    "$env:ProgramFiles\NSIS\makensis.exe"
  ) | Where-Object { $_ -and (Test-Path $_) }
  $candidateList = @($candidates)
  if ($candidateList.Count -eq 0) {
    throw 'NSIS was installed but makensis.exe could not be found.'
  }
  return $candidateList[0]
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$managerDir = Split-Path -Parent $scriptDir
$repositoryDir = Split-Path -Parent $managerDir
$target = "win32-$Architecture"
Push-Location $managerDir
try {
  $version = (node -p "require('./package.json').version").Trim()
} finally {
  Pop-Location
}
$majorVersion = "$($version.Split('.')[0]).0.0"
$bundle = Join-Path $managerDir "dist\releases\orchard-packages-$version-$target"
$template = Join-Path $repositoryDir 'packaging\windows\orchard.nsi'
$launcherTemplate = Join-Path $repositoryDir 'packaging\windows\orchard.cmd.in'
$icon = Join-Path $repositoryDir 'build\icon.ico'
$license = Join-Path $repositoryDir 'LICENSE'
$outputDir = Join-Path $repositoryDir "artifacts\windows\$target"
$artifactName = if ($Architecture -eq 'x64') { "Orchard-Setup-$version.exe" } else { "Orchard-Setup-$version-arm64.exe" }
$output = Join-Path $outputDir $artifactName

foreach ($required in @($bundle, $template, $launcherTemplate, $icon, $license)) {
  if (-not (Test-Path $required)) {
    throw "Required Orchard Packages input is missing: $required"
  }
}
foreach ($required in @(
  (Join-Path $bundle 'orchard-packages.exe'),
  (Join-Path $bundle 'resources.neu'),
  (Join-Path $bundle 'extensions\orchard-packages-backend.exe')
)) {
  if (-not (Test-Path $required)) {
    throw "The Windows manager bundle is incomplete: $required"
  }
}

$makensis = Find-Makensis
$launcher = Join-Path ([IO.Path]::GetTempPath()) "orchard-packages-$PID-$Architecture.cmd"
try {
  $launcherText = (Get-Content -Raw $launcherTemplate).Replace('@ORCHARD_MAJOR_VERSION@', $majorVersion)
  Set-Content -Path $launcher -Value $launcherText -Encoding ascii -NoNewline

  Remove-Item -Recurse -Force $outputDir -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force $outputDir | Out-Null
  $defines = @(
    "/DVERSION=$version",
    "/DBUNDLE=$bundle",
    "/DOUTPUT=$output",
    "/DICON=$icon",
    "/DLICENSE=$license",
    "/DLAUNCHER=$launcher"
  )
  & $makensis @defines $template
  if ($LASTEXITCODE -ne 0) {
    throw "NSIS failed to build the Orchard installer (exit code $LASTEXITCODE)."
  }
  if (-not (Test-Path $output)) {
    throw "NSIS completed without creating $output"
  }
  Write-Output $output
} finally {
  Remove-Item -Force $launcher -ErrorAction SilentlyContinue
}
