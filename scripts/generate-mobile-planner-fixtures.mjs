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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { mobileTransitionCases } from '../test/fixtures/mobileTransitionPlanner.js';
import { planTransition } from '../src/audio/crossfade/transitionPlanner.js';
import { planWsolaTransition } from '../src/audio/crossfade/wsolaPlanner.js';
const output = path.resolve(process.argv[2]);
await mkdir(output, { recursive: true });
await writeFile(path.join(output, 'desktop-planner-fixtures.json'), JSON.stringify(mobileTransitionCases.map(({ name, input }) => ({
  name, input, live: planTransition(input), native: planWsolaTransition({
    analysis: input.analysis, nextAnalysis: input.nextAnalysis,
    duration: Math.max(input.duration, input.currentTrack.durationSeconds),
    nextDuration: input.nextTrack.durationSeconds
  })
}))));
