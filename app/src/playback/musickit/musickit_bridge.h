/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <stdint.h>

// C entry points of musickit_bridge.swift. Every call must come from the main
// thread, and done always runs there.

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*OrchardMusicKitDone)(void *context, int32_t ok, const char *error);

// Nonzero inside an app bundle, the only place MusicKit issues tokens.
int32_t orchard_musickit_available(void);
// Replaces the player's queue with one catalog song. done reports the outcome
// unless a later play or stop supersedes it, in which case it reports an empty error.
void orchard_musickit_play(const char *song_id, double start_seconds, int32_t paused, void *context,
                           OrchardMusicKitDone done);
void orchard_musickit_resume(void);
void orchard_musickit_pause(void);
void orchard_musickit_stop(void);
void orchard_musickit_seek(double seconds);
// 0 stopped, 1 playing, 2 paused, 3 interrupted.
int32_t orchard_musickit_status(void);
double orchard_musickit_position(void);
double orchard_musickit_duration(void);
int32_t orchard_musickit_has_entry(void);
// 0 unknown, 1 lossy stereo, 2 lossless, 3 hi-res lossless, 4 Dolby Atmos,
// 5 Dolby Audio, 6 spatial audio.
int32_t orchard_musickit_variant(void);

#ifdef __cplusplus
}
#endif
