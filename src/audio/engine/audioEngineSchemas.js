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

import { z } from 'zod';

// Zod's optional JIT probes Function() before compiling object validators.
// Orchard's renderer deliberately forbids that under its strict CSP.
z.config({ jitless: true });

const finiteNumber = z.number().finite();

export const audioEngineConfigSchema = z.object({
  enabled: z.boolean().optional(),
  autoEqEnabled: z.boolean().optional(),
  eqEnabled: z.boolean().optional(),
  gains: z.array(finiteNumber).length(10).optional(),
  preampDb: finiteNumber.optional(),
  outputGainDb: finiteNumber.optional(),
  q: finiteNumber.optional(),
  balance: finiteNumber.optional(),
  outputDeviceId: z.string().optional()
}).passthrough();

export const audioEngineProfileSchema = z.object({
  app: z.literal('orchard'),
  type: z.literal('audio-engine-profile'),
  version: z.number().int().min(1),
  config: audioEngineConfigSchema
});

export const learnedAudioProfileSchema = z.object({
  trackId: z.string().min(1),
  title: z.string().default(''),
  features: z.array(finiteNumber).length(9),
  gains: z.array(finiteNumber).length(10),
  tempo: finiteNumber.nullable().default(null),
  sampleCount: z.number().int().positive(),
  updatedAt: finiteNumber
});

export const learnedAudioProfilesSchema = z.array(learnedAudioProfileSchema);

export function parseAudioEngineProfile(value) {
  const result = audioEngineProfileSchema.safeParse(value);
  if (!result.success) throw new Error('That file is not a valid Orchard audio profile.');
  return result.data;
}
