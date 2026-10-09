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

#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

uint32_t orchard_core_abi_version(void);
double orchard_smart_crossfade_max_seconds(void);

typedef struct OrchardAudioEngineHandle OrchardAudioEngineHandle;

typedef struct OrchardAudioEngineConfig {
    uint8_t enabled;
    uint8_t auto_eq_enabled;
    uint8_t eq_enabled;
    uint8_t normalization_enabled;
    float gains_db[10];
    float preamp_db;
    float output_gain_db;
    float q;
    float balance;
    float track_gain_db;
} OrchardAudioEngineConfig;

OrchardAudioEngineHandle *orchard_audio_engine_create(uint32_t sample_rate,
                                                       uint32_t channels);
uint8_t orchard_audio_engine_configure(OrchardAudioEngineHandle *handle,
                                       const OrchardAudioEngineConfig *config);
uint8_t orchard_audio_engine_process(OrchardAudioEngineHandle *handle,
                                     float *samples, size_t frame_count);
void orchard_audio_engine_reset(OrchardAudioEngineHandle *handle);
uint8_t orchard_audio_engine_auto_gains(const OrchardAudioEngineHandle *handle,
                                        float gains[10]);
uint8_t orchard_audio_engine_spectrum(const OrchardAudioEngineHandle *handle,
                                      float levels[10]);
void orchard_audio_engine_destroy(OrchardAudioEngineHandle *handle);

// Returns owned UTF-8 JSON containing extraction data or an error. Source is
// borrowed for this call only. Release the result with orchard_youtube_extract_free.
char *orchard_youtube_extract(const uint8_t *source, size_t length);
void orchard_youtube_extract_free(char *result);

// Media keys for segmented provider streams. Each returns 1 on success.
uint8_t orchard_hkdf_sha256(const uint8_t *key, size_t key_length,
                            const uint8_t *salt, size_t salt_length,
                            const uint8_t *info, size_t info_length,
                            uint8_t *out, size_t out_length);
// `out` holds `length` bytes; `written` receives the unpadded length.
uint8_t orchard_aes128_cbc_decrypt(const uint8_t *key, const uint8_t *iv,
                                   const uint8_t *data, size_t length,
                                   uint8_t *out, size_t *written);
// 128-bit big-endian counter, applied in place.
uint8_t orchard_aes128_ctr_apply(const uint8_t *key, const uint8_t *iv,
                                 uint8_t *data, size_t length);

typedef struct OrchardSystemMediaHandle OrchardSystemMediaHandle;

typedef struct OrchardSystemMediaOptions {
    const char *display_name;
    const char *bus_name;
    const char *desktop_entry;
    uint64_t window_handle;
    uint8_t has_window_handle;
    const char *app_media_id;
} OrchardSystemMediaOptions;

typedef struct OrchardSystemMediaTrack {
    const char *id;
    const char *title;
    const char *const *artists;
    size_t artist_count;
    const char *album;
    const char *artwork_url;
    const char *url;
} OrchardSystemMediaTrack;

typedef struct OrchardSystemMediaState {
    const OrchardSystemMediaTrack *track;
    uint8_t playing;
    uint8_t can_go_next;
    uint8_t can_go_previous;
    uint8_t can_seek;
    double position_seconds;
    double duration_seconds;
    uint8_t has_duration;
    double volume;
    uint32_t repeat_mode;
    uint8_t shuffle;
} OrchardSystemMediaState;

typedef struct OrchardSystemMediaCommand {
    const char *kind;
    double number_value;
    uint8_t has_number_value;
    uint8_t bool_value;
    uint8_t has_bool_value;
    const char *string_value;
} OrchardSystemMediaCommand;

typedef void (*OrchardSystemMediaCallback)(
    void *context,
    const OrchardSystemMediaCommand *command
);

uint32_t orchard_system_media_api_version(void);
OrchardSystemMediaHandle *orchard_system_media_create(
    const OrchardSystemMediaOptions *options,
    OrchardSystemMediaCallback callback,
    void *callback_context
);
uint8_t orchard_system_media_is_attached(const OrchardSystemMediaHandle *handle);
uint8_t orchard_system_media_set_state(
    OrchardSystemMediaHandle *handle,
    const OrchardSystemMediaState *state
);
const char *orchard_system_media_last_error(const OrchardSystemMediaHandle *handle);
void orchard_system_media_stop(OrchardSystemMediaHandle *handle);
void orchard_system_media_destroy(OrchardSystemMediaHandle *handle);

typedef struct OrchardDiscordRpcHandle OrchardDiscordRpcHandle;

typedef void (*OrchardDiscordRpcCallback)(
    void *context,
    const char *message,
    uint8_t connected
);

OrchardDiscordRpcHandle *orchard_discord_rpc_create(
    const char *application_id,
    OrchardDiscordRpcCallback callback,
    void *callback_context
);
uint8_t orchard_discord_rpc_set_presence(
    OrchardDiscordRpcHandle *handle,
    const char *payload
);
uint8_t orchard_discord_rpc_clear_presence(OrchardDiscordRpcHandle *handle);
const char *orchard_discord_rpc_last_error(const OrchardDiscordRpcHandle *handle);
void orchard_discord_rpc_destroy(OrchardDiscordRpcHandle *handle);

#ifdef __cplusplus
}
#endif
