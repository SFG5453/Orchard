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
#include "discord_sdk_presence.h"
#include "orchard_core.h"

#include <QJsonDocument>
#include <QJsonParseError>
#include <QJsonObject>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

using namespace std::chrono_literals;

struct OrchardDiscordRpcHandle {
    OrchardDiscordRpcCallback callback = nullptr;
    void *context = nullptr;
    uint64_t applicationId = 1531666622312353803ULL;
    std::mutex mutex;
    std::condition_variable wake;
    std::thread worker;
    std::optional<QJsonObject> command;
    bool commandReady = false;
    bool stopping = false;
    std::string lastError;
};

namespace {
void notify(OrchardDiscordRpcHandle *handle, const std::string &message, bool connected)
{
    if (handle->callback) handle->callback(handle->context, message.c_str(), connected ? 1 : 0);
}

void runWorker(OrchardDiscordRpcHandle *handle)
{
    discordpp::Client client;
    client.SetApplicationId(handle->applicationId);
    std::optional<QJsonObject> latest;
    bool dirty = false;
    bool requestOutstanding = false;
    uint64_t generation = 0;
    auto nextAttempt = std::chrono::steady_clock::now();

    while (true) {
        std::optional<QJsonObject> command;
        bool hasCommand = false;
        {
            std::unique_lock lock(handle->mutex);
            handle->wake.wait_for(lock, 50ms, [&] { return handle->stopping || handle->commandReady; });
            if (handle->stopping) break;
            if (handle->commandReady) {
                command = std::move(handle->command);
                handle->commandReady = false;
                hasCommand = true;
            }
        }

        if (hasCommand) {
            ++generation; // Stale callbacks can leave the stage without an encore.
            latest = std::move(command);
            dirty = latest.has_value();
            if (!latest) client.ClearRichPresence();
        }

        if (latest && dirty && !requestOutstanding &&
            std::chrono::steady_clock::now() >= nextAttempt) {
            auto activity = orchardDiscordActivity(*latest);
            if (activity) {
                const uint64_t sentGeneration = generation;
                requestOutstanding = true;
                dirty = false;
                // Discord isn't a karaoke teleprompter, however loudly we ask.
                // Keep the newest line queued instead of pelting it with updates.
                nextAttempt = std::chrono::steady_clock::now() + 5s;
                client.UpdateRichPresence(std::move(*activity),
                    [&, sentGeneration](discordpp::ClientResult result) {
                        requestOutstanding = false;
                        if (sentGeneration != generation) return;
                        if (result.Type() == discordpp::ErrorType::None) {
                            notify(handle, {}, true);
                        } else {
                            dirty = true;
                            nextAttempt = std::chrono::steady_clock::now() + 5s;
                            notify(handle, result.ToString(), false);
                        }
                    });
            } else {
                latest.reset();
                dirty = false;
                client.ClearRichPresence();
            }
        }
        discordpp::RunCallbacks();
    }

    ++generation;
    client.ClearRichPresence();
    discordpp::RunCallbacks();
}
} // namespace

extern "C" OrchardDiscordRpcHandle *orchard_discord_rpc_create(
    const char *application_id, OrchardDiscordRpcCallback callback, void *context)
{
    auto handle = std::make_unique<OrchardDiscordRpcHandle>();
    handle->callback = callback;
    handle->context = context;
    if (application_id && *application_id) {
        try { handle->applicationId = std::stoull(application_id); }
        catch (...) { handle->lastError = "Invalid Discord application ID"; return nullptr; }
    }
    try { handle->worker = std::thread(runWorker, handle.get()); }
    catch (...) { return nullptr; }
    return handle.release();
}

extern "C" uint8_t orchard_discord_rpc_set_presence(OrchardDiscordRpcHandle *handle,
                                                       const char *payload)
{
    if (!handle || !payload) return 0;
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(payload, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
        handle->lastError = "Invalid Discord presence JSON: " + error.errorString().toStdString();
        return 0;
    }
    {
        std::lock_guard lock(handle->mutex);
        if (handle->stopping) {
            handle->lastError = "Discord SDK worker has stopped";
            return 0;
        }
        handle->command = document.object();
        handle->commandReady = true;
    }
    handle->lastError.clear();
    handle->wake.notify_one();
    return 1;
}

extern "C" uint8_t orchard_discord_rpc_clear_presence(OrchardDiscordRpcHandle *handle)
{
    if (!handle) return 0;
    {
        std::lock_guard lock(handle->mutex);
        if (handle->stopping) {
            handle->lastError = "Discord SDK worker has stopped";
            return 0;
        }
        handle->command.reset();
        handle->commandReady = true;
    }
    handle->lastError.clear();
    handle->wake.notify_one();
    return 1;
}

extern "C" const char *orchard_discord_rpc_last_error(const OrchardDiscordRpcHandle *handle)
{
    return handle ? handle->lastError.c_str() : "Invalid Discord SDK handle";
}

extern "C" void orchard_discord_rpc_destroy(OrchardDiscordRpcHandle *handle)
{
    if (!handle) return;
    {
        std::lock_guard lock(handle->mutex);
        handle->stopping = true;
    }
    handle->wake.notify_one();
    if (handle->worker.joinable()) handle->worker.join();
    delete handle;
}
