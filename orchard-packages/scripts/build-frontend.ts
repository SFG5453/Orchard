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

import { access, cp, mkdir, rm } from "node:fs/promises";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dir, "..");
const sourceRoot = path.join(projectRoot, "src", "frontend");
const resourcesRoot = path.join(projectRoot, "resources");
const bundledIcon = path.join(projectRoot, "assets", "orchard-packages.png");
const repositoryIcon = path.resolve(projectRoot, "..", "build", "icon.png");

async function exists(candidate: string): Promise<boolean> {
  try {
    await access(candidate);
    return true;
  } catch {
    return false;
  }
}

await rm(resourcesRoot, { recursive: true, force: true });
await mkdir(path.join(resourcesRoot, "js"), { recursive: true });
await mkdir(path.join(resourcesRoot, "icons"), { recursive: true });

const result = await Bun.build({
  entrypoints: [path.join(sourceRoot, "app.ts")],
  outdir: resourcesRoot,
  target: "browser",
  format: "esm",
  minify: true,
  sourcemap: "none"
});

if (!result.success) {
  for (const log of result.logs) console.error(log);
  throw new Error("Frontend compilation failed.");
}

await Promise.all([
  cp(path.join(sourceRoot, "index.html"), path.join(resourcesRoot, "index.html")),
  cp(path.join(sourceRoot, "style.css"), path.join(resourcesRoot, "style.css")),
  cp(
    path.join(projectRoot, "node_modules", "@neutralinojs", "lib", "dist", "neutralino.js"),
    path.join(resourcesRoot, "js", "neutralino.js")
  ),
  cp(
    (await exists(bundledIcon)) ? bundledIcon : repositoryIcon,
    path.join(resourcesRoot, "icons", "orchard-packages.png")
  )
]);

console.log(`Frontend resources written to ${resourcesRoot}`);
