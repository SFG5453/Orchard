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
