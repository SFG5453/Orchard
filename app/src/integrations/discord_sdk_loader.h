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

#pragma once
#include "orchard_core.h"

#include <QString>

// Discord SDK entry points, resolved from the orchard-discord module on first use.
struct DiscordSdkApi {
    decltype(&orchard_discord_rpc_create) create;
    decltype(&orchard_discord_rpc_set_presence) setPresence;
    decltype(&orchard_discord_rpc_clear_presence) clearPresence;
    decltype(&orchard_discord_rpc_last_error) lastError;
    decltype(&orchard_discord_rpc_destroy) destroy;

    // Stays loaded once resolved; the SDK keeps internal threads past client teardown.
    static const DiscordSdkApi *get(QString *error);
};
