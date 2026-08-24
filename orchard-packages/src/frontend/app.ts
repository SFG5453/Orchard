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

const EXTENSION_ID = "dev.sfg.orchard.packages.backend";

type ReleaseSummary = {
  version: string;
  channel: "stable" | "beta";
  sharedSize: number;
  nativeSize: number;
  installed: boolean;
};

type ManifestResponse = {
  requestId: string;
  target: string;
  releases: ReleaseSummary[];
};

type ProgressEvent = {
  requestId: string;
  phase: string;
  message: string;
  detail?: string;
  percent: number;
};

type SuccessEvent = {
  requestId: string;
  version: string;
  target: string;
  installPath: string;
};

type ErrorEvent = {
  requestId: string;
  message: string;
};

declare const Neutralino: {
  init(): void;
  app: { exit(): Promise<void> };
  events: {
    on(event: string, callback: (event: { detail: unknown }) => void): Promise<void>;
  };
  extensions: {
    dispatch(extensionId: string, event: string, data?: Record<string, unknown>): Promise<void>;
  };
};

const elements = {
  commonPackageLabel: document.querySelector<HTMLElement>("#common-package-label")!,
  installButton: document.querySelector<HTMLButtonElement>("#install-button")!,
  installButtonLabel: document.querySelector<HTMLElement>("#install-button-label")!,
  nativePackageLabel: document.querySelector<HTMLElement>("#native-package-label")!,
  operationDetail: document.querySelector<HTMLElement>("#operation-detail")!,
  operationText: document.querySelector<HTMLElement>("#operation-text")!,
  progressFill: document.querySelector<HTMLElement>("#progress-fill")!,
  progressTrack: document.querySelector<HTMLElement>("#progress-track")!,
  progressValue: document.querySelector<HTMLElement>("#progress-value")!,
  releaseChannel: document.querySelector<HTMLElement>("#release-channel")!,
  resultMessage: document.querySelector<HTMLElement>("#result-message")!,
  targetBadge: document.querySelector<HTMLElement>("#target-badge")!,
  versionDetail: document.querySelector<HTMLElement>("#version-detail")!,
  versionSelect: document.querySelector<HTMLSelectElement>("#version-select")!
};

let activeRequestId = "";
let releases: ReleaseSummary[] = [];
let installing = false;

function requestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = units[0]!;
  for (const candidate of units) {
    unit = candidate;
    if (value < 1024 || candidate === units.at(-1)) break;
    value /= 1024;
  }
  return `${value >= 100 || unit === "B" ? value.toFixed(0) : value.toFixed(1)} ${unit}`;
}

function selectedRelease(): ReleaseSummary | undefined {
  return releases.find((release) => release.version === elements.versionSelect.value);
}

function updateReleaseDetail(): void {
  const release = selectedRelease();
  if (!release) return;
  const commonSize = formatBytes(release.sharedSize);
  const nativeSize = formatBytes(release.nativeSize);
  elements.releaseChannel.textContent = release.channel === "stable" ? "Stable release" : "Beta release";
  elements.versionDetail.textContent = `${formatBytes(release.sharedSize + release.nativeSize)} download · ${commonSize} common + ${nativeSize} native.`;
  elements.commonPackageLabel.textContent = `COMMON · ${commonSize}`;
  elements.nativePackageLabel.textContent = `${elements.targetBadge.textContent?.toUpperCase()} · ${nativeSize}`;
  elements.installButtonLabel.textContent = release.installed ? "Open Orchard" : "Install Orchard";
}

function setProgress(percent: number, message: string, detail = ""): void {
  const safePercent = Math.max(0, Math.min(100, Math.round(percent)));
  elements.progressFill.style.width = `${safePercent}%`;
  elements.progressTrack.setAttribute("aria-valuenow", String(safePercent));
  elements.progressValue.textContent = `${safePercent}%`;
  elements.operationText.textContent = message;
  elements.operationDetail.textContent = detail;
}

function setResult(message: string, isError = false): void {
  elements.resultMessage.hidden = false;
  elements.resultMessage.textContent = message;
  elements.resultMessage.classList.toggle("error", isError);
}

function clearResult(): void {
  elements.resultMessage.hidden = true;
  elements.resultMessage.textContent = "";
  elements.resultMessage.classList.remove("error");
}

async function loadManifest(): Promise<void> {
  activeRequestId = requestId();
  clearResult();
  setProgress(0, "Checking available versions…", "Reading the Orchard package manifest.");
  await Neutralino.extensions.dispatch(EXTENSION_ID, "packages.requestManifest", {
    requestId: activeRequestId
  });
}

async function installSelected(): Promise<void> {
  const release = selectedRelease();
  if (!release || installing) return;

  installing = true;
  activeRequestId = requestId();
  clearResult();
  elements.installButton.disabled = true;
  elements.versionSelect.disabled = true;
  elements.installButtonLabel.textContent = "Installing…";
  setProgress(1, `Preparing Orchard ${release.version}…`, "Resolving packages for this computer.");

  await Neutralino.extensions.dispatch(EXTENSION_ID, "packages.install", {
    requestId: activeRequestId,
    version: release.version
  });
}

async function runSelectedAction(): Promise<void> {
  const release = selectedRelease();
  if (!release || installing) return;
  if (!release.installed) {
    await installSelected();
    return;
  }

  installing = true;
  activeRequestId = requestId();
  clearResult();
  elements.installButton.disabled = true;
  elements.versionSelect.disabled = true;
  elements.installButtonLabel.textContent = "Opening…";
  await Neutralino.extensions.dispatch(EXTENSION_ID, "packages.open", {
    requestId: activeRequestId,
    version: release.version
  });
}

function onManifest(payload: ManifestResponse): void {
  if (payload.requestId !== activeRequestId) return;

  releases = payload.releases;
  elements.targetBadge.textContent = payload.target;
  elements.versionSelect.replaceChildren();

  for (const release of releases) {
    const option = document.createElement("option");
    option.value = release.version;
    option.textContent = release.version;
    elements.versionSelect.append(option);
  }

  if (releases.length === 0) {
    elements.versionSelect.append(new Option("No compatible releases", ""));
    elements.versionSelect.disabled = true;
    elements.installButton.disabled = true;
    setProgress(0, "No compatible release found.", `The manifest has no native package for ${payload.target}.`);
    return;
  }

  elements.versionSelect.disabled = false;
  elements.installButton.disabled = false;
  updateReleaseDetail();
  const release = selectedRelease()!;
  setProgress(
    release.installed ? 100 : 0,
    release.installed ? `Orchard ${release.version} is installed.` : "Ready to install.",
    release.installed ? "Open the installed version." : "The existing installation stays in place until the new one is verified."
  );
}

function onProgress(payload: ProgressEvent): void {
  if (payload.requestId !== activeRequestId) return;
  setProgress(payload.percent, payload.message, payload.detail ?? "");
}

function onSuccess(payload: SuccessEvent): void {
  if (payload.requestId !== activeRequestId) return;
  installing = false;
  const release = releases.find((candidate) => candidate.version === payload.version);
  if (release) release.installed = true;
  elements.installButton.disabled = false;
  elements.versionSelect.disabled = false;
  elements.installButtonLabel.textContent = "Open Orchard";
  setProgress(100, `Orchard ${payload.version} installed.`, `${payload.target} package verified and activated.`);
  setResult(`Installed to ${payload.installPath}`);
}

function onOpened(payload: { requestId: string }): void {
  if (payload.requestId !== activeRequestId) return;
  void Neutralino.app.exit();
}

function onError(payload: ErrorEvent): void {
  if (payload.requestId !== activeRequestId) return;
  installing = false;
  elements.installButton.disabled = releases.length === 0;
  elements.versionSelect.disabled = releases.length === 0;
  elements.installButtonLabel.textContent = selectedRelease()?.installed ? "Open Orchard" : "Try again";
  elements.operationText.textContent = "Operation stopped.";
  elements.operationDetail.textContent = "No existing Orchard installation was changed.";
  setResult(payload.message, true);
}

Neutralino.init();
void Neutralino.events.on("windowClose", () => {
  void Neutralino.app.exit();
});
void Neutralino.events.on("packages.manifest", (event) => onManifest(event.detail as ManifestResponse));
void Neutralino.events.on("packages.progress", (event) => onProgress(event.detail as ProgressEvent));
void Neutralino.events.on("packages.success", (event) => onSuccess(event.detail as SuccessEvent));
void Neutralino.events.on("packages.opened", (event) => onOpened(event.detail as { requestId: string }));
void Neutralino.events.on("packages.error", (event) => onError(event.detail as ErrorEvent));
void Neutralino.events.on("ready", () => {
  void loadManifest().catch((error: unknown) => {
    onError({ requestId: activeRequestId, message: error instanceof Error ? error.message : String(error) });
  });
});

elements.versionSelect.addEventListener("change", updateReleaseDetail);
elements.installButton.addEventListener("click", () => {
  void runSelectedAction().catch((error: unknown) => {
    onError({ requestId: activeRequestId, message: error instanceof Error ? error.message : String(error) });
  });
});
