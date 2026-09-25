/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { existsSync } from 'node:fs';
import path from 'node:path';

export function isDevelopmentBuild({ app, isDev, pathExists = existsSync }) {
  if (isDev) return true;
  if (app.isPackaged) return false;

  return !pathExists(path.join(app.getAppPath(), '.orchard-package.json'));
}
