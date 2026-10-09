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

// Orchard account service: Google sign-in in, Orchard sessions out.
// workerd only accepts handlers as exports here, so the logic lives in service.js.

import { handleRequest, purgeExpired } from "./service.js";
import { sweepSupport } from "./support_sync.js";

export const SUPPORT_SWEEP_CRON = "*/10 * * * *";

// Durable Object classes must be exported from the entry module.
export { ConnectHub } from "./connect.js";

export default {
  fetch: handleRequest,

  async scheduled(event, env, ctx) {
    // Paper boy round: check GitHub for news on everyone's bug reports.
    if (event.cron === SUPPORT_SWEEP_CRON) {
      ctx.waitUntil(sweepSupport(env));
      return;
    }
    // Janitor shift: sweep abandoned sign-ins and devices nobody has opened in months.
    ctx.waitUntil(purgeExpired(env));
  },
};
