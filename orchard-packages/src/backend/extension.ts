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

import { readFileSync } from "node:fs";
import { fetchManifest } from "./manifest.ts";
import { installRelease } from "./installer.ts";
import { detectTarget } from "./target.ts";

type ExtensionBootstrap = {
  nlPort: string;
  nlToken: string;
  nlConnectToken: string;
  nlExtensionId: string;
};

type ExtensionEvent = {
  event?: string;
  data?: Record<string, unknown>;
};

function bootstrapInput(): ExtensionBootstrap {
  let value: unknown;
  try {
    value = JSON.parse(readFileSync(0, "utf8"));
  } catch (error) {
    throw new Error(`Neutralino extension bootstrap data is invalid: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (!value || typeof value !== "object") throw new Error("Neutralino extension bootstrap data is missing.");
  const input = value as Partial<ExtensionBootstrap>;
  for (const key of ["nlPort", "nlToken", "nlConnectToken", "nlExtensionId"] as const) {
    if (!input[key]) throw new Error(`Neutralino extension bootstrap field ${key} is missing.`);
  }
  return input as ExtensionBootstrap;
}

function message(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error || "Unknown package service error.");
}

export async function startExtension(): Promise<void> {
  const bootstrap = bootstrapInput();
  const socket = new WebSocket(
    `ws://127.0.0.1:${bootstrap.nlPort}?extensionId=${encodeURIComponent(bootstrap.nlExtensionId)}` +
    `&connectToken=${encodeURIComponent(bootstrap.nlConnectToken)}`
  );
  let installController: AbortController | undefined;
  let activeInstall: Promise<void> | undefined;
  let installing = false;

  const broadcast = (event: string, data: Record<string, unknown>): void => {
    if (socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({
      id: crypto.randomUUID(),
      method: "app.broadcast",
      accessToken: bootstrap.nlToken,
      data: { event, data }
    }));
  };

  socket.onmessage = (incoming) => {
    void (async () => {
      let payload: ExtensionEvent;
      try {
        payload = JSON.parse(String(incoming.data)) as ExtensionEvent;
      } catch {
        return;
      }

      const requestId = String(payload.data?.requestId || "");
      if (!requestId) return;

      if (payload.event === "packages.requestManifest") {
        try {
          const target = detectTarget();
          const manifest = await fetchManifest();
          const releases = manifest.releases
            .filter((release) => Boolean(release.native[target]))
            .sort((left, right) => right.version.localeCompare(left.version, undefined, { numeric: true }))
            .map((release) => ({
              version: release.version,
              channel: release.channel,
              sharedSize: release.shared.size,
              nativeSize: release.native[target]!.size
            }));
          broadcast("packages.manifest", { requestId, target, releases });
        } catch (error) {
          broadcast("packages.error", { requestId, message: message(error) });
        }
        return;
      }

      if (payload.event !== "packages.install") return;
      if (installing) {
        broadcast("packages.error", { requestId, message: "Another Orchard installation is already running." });
        return;
      }

      const version = String(payload.data?.version || "");
      installing = true;
      installController = new AbortController();
      activeInstall = (async () => {
        try {
          const result = await installRelease(version, (progress) => {
            broadcast("packages.progress", { requestId, ...progress });
          }, installController!.signal);
          broadcast("packages.success", { requestId, ...result });
        } catch (error) {
          broadcast("packages.error", { requestId, message: message(error) });
        } finally {
          installing = false;
          installController = undefined;
        }
      })();
      await activeInstall;
      activeInstall = undefined;
    })();
  };

  socket.onerror = () => console.error("The Orchard Packages extension lost its Neutralino connection.");
  socket.onclose = () => {
    installController?.abort();
    void (async () => {
      await activeInstall?.catch(() => undefined);
      process.exit(0);
    })();
  };

  await new Promise<void>((resolve, reject) => {
    socket.onopen = () => resolve();
    const previousError = socket.onerror;
    socket.onerror = (event) => {
      if (typeof previousError === "function") previousError.call(socket, event);
      reject(new Error("Could not connect the package backend to Neutralino."));
    };
  });
}
