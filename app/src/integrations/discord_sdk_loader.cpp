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

#include "discord_sdk_loader.h"

#include <QCoreApplication>
#include <QDir>
#include <QLibrary>
#include <QMutex>

namespace {
QString modulePath()
{
    const QString override = qEnvironmentVariable("ORCHARD_DISCORD_MODULE");
    if (!override.isEmpty())
        return override;
#if defined(Q_OS_WIN)
    const QString name = QStringLiteral("orchard-discord.dll");
#elif defined(Q_OS_MACOS)
    const QString name = QStringLiteral("orchard-discord.dylib");
#else
    const QString name = QStringLiteral("orchard-discord.so");
#endif
    return QDir(QCoreApplication::applicationDirPath()).filePath(name);
}
} // namespace

const DiscordSdkApi *DiscordSdkApi::get(QString *error)
{
    static QMutex mutex;
    static DiscordSdkApi api{};
    static bool loaded = false;
    static QString failure;
    QMutexLocker lock(&mutex);
    if (!loaded && failure.isEmpty()) {
        auto *library = new QLibrary(modulePath());
        const auto resolve = [library](const char *symbol) { return library->resolve(symbol); };
        if (!library->load()) {
            failure = library->errorString();
        } else {
            api.create = reinterpret_cast<decltype(api.create)>(resolve("orchard_discord_rpc_create"));
            api.setPresence = reinterpret_cast<decltype(api.setPresence)>(resolve("orchard_discord_rpc_set_presence"));
            api.clearPresence = reinterpret_cast<decltype(api.clearPresence)>(resolve("orchard_discord_rpc_clear_presence"));
            api.lastError = reinterpret_cast<decltype(api.lastError)>(resolve("orchard_discord_rpc_last_error"));
            api.destroy = reinterpret_cast<decltype(api.destroy)>(resolve("orchard_discord_rpc_destroy"));
            loaded = api.create && api.setPresence && api.clearPresence && api.lastError && api.destroy;
            if (!loaded)
                failure = QStringLiteral("Discord module is missing entry points");
        }
    }
    if (!loaded && error)
        *error = failure;
    return loaded ? &api : nullptr;
}
