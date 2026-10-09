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

#include "system_media_bridge.h"

#include <QByteArray>
#include <QMetaObject>
#include <QPointer>
#include <QRegularExpression>
#include <QStringList>
#include <QtGlobal>

QByteArray highResArtworkUrl(const QByteArray &url)
{
    if (url.isEmpty()) {
        return url;
    }

    QString str = QString::fromUtf8(url);
    if (str.contains(QLatin1String("googleusercontent.com"), Qt::CaseInsensitive) ||
        str.contains(QLatin1String("ggpht.com"), Qt::CaseInsensitive)) {
        static const QRegularExpression sPattern(QStringLiteral("([=-])s\\d+(?=[-/?#]|$)"), QRegularExpression::CaseInsensitiveOption);
        static const QRegularExpression wPattern(QStringLiteral("([=-])w\\d+(?=[-/?#]|$)"), QRegularExpression::CaseInsensitiveOption);
        static const QRegularExpression hPattern(QStringLiteral("-h\\d+(?=[-/?#]|$)"), QRegularExpression::CaseInsensitiveOption);

        str.replace(sPattern, QStringLiteral("\\1s1200"));
        str.replace(wPattern, QStringLiteral("\\1w1200"));
        str.replace(hPattern, QStringLiteral("-h1200"));
        return str.toUtf8();
    }

    if (str.contains(QLatin1String("ytimg.com"), Qt::CaseInsensitive)) {
        static const QRegularExpression ytPattern(QStringLiteral("/(default|mqdefault|hqdefault|sddefault)\\.jpg"), QRegularExpression::CaseInsensitiveOption);
        str.replace(ytPattern, QStringLiteral("/maxresdefault.jpg"));
        return str.toUtf8();
    }

    return url;
}

namespace {

QByteArray bytes(const QVariantMap &values, const char *key)
{
    const QVariant val = values.value(QString::fromLatin1(key));
    if (val.userType() == QMetaType::QVariantMap) {
        return val.toMap().value(QStringLiteral("title")).toString().toUtf8();
    }
    return val.toString().toUtf8();
}

uint32_t repeatMode(const QString &value)
{
    if (value == QStringLiteral("one")) {
        return 1;
    }
    if (value == QStringLiteral("queue") || value == QStringLiteral("all")) {
        return 2;
    }
    return 0;
}
}

SystemMediaBridge::SystemMediaBridge(QObject *parent)
    : QObject(parent)
{
    start();
}

SystemMediaBridge::~SystemMediaBridge()
{
    destroyHandle();
}

bool SystemMediaBridge::attached() const
{
    return m_handle != nullptr && orchard_system_media_is_attached(m_handle) != 0;
}

QString SystemMediaBridge::lastError() const
{
    return m_lastError;
}

bool SystemMediaBridge::start(qulonglong windowHandle)
{
    const bool wasAttached = attached();
    destroyHandle();

    const QByteArray displayName("Orchard");
    const QByteArray busName("Orchard");
    const QByteArray desktopEntry("dev.sfg.orchard");
    // Match the process ID and installed shortcut so SMTC can find our icon.
    const QByteArray appMediaId("dev.sfg.orchard");
    const OrchardSystemMediaOptions options{
        .display_name = displayName.constData(),
        .bus_name = busName.constData(),
        .desktop_entry = desktopEntry.constData(),
        .window_handle = static_cast<uint64_t>(windowHandle),
        .has_window_handle = static_cast<uint8_t>(windowHandle != 0),
        .app_media_id = appMediaId.constData(),
    };

    m_handle = orchard_system_media_create(&options, &SystemMediaBridge::receiveCommand, this);
    const QString error = QString::fromUtf8(orchard_system_media_last_error(m_handle));
    if (m_lastError != error) {
        m_lastError = error;
        emit lastErrorChanged();
    }
    if (wasAttached != attached()) {
        emit attachedChanged();
    }
    return attached();
}

bool SystemMediaBridge::publish(const QVariantMap &state)
{
    if (m_handle == nullptr) {
        return false;
    }

    const QVariantMap trackMap = state.value(QStringLiteral("track")).toMap();
    const QByteArray id = bytes(trackMap, "id");
    const QByteArray title = bytes(trackMap, "title");
    const QByteArray album = bytes(trackMap, "album");
    QByteArray artworkUrl = bytes(trackMap, "heroThumbnail");
    if (artworkUrl.isEmpty()) {
        artworkUrl = bytes(trackMap, "artworkUrl");
    }
    if (artworkUrl.isEmpty()) {
        artworkUrl = bytes(trackMap, "thumbnail");
    }
    if (artworkUrl.isEmpty()) {
        artworkUrl = bytes(trackMap, "artwork");
    }
    if (artworkUrl.isEmpty()) {
        artworkUrl = bytes(trackMap, "cover");
    }
    if (artworkUrl.isEmpty()) {
        const QVariant thumbs = trackMap.value(QStringLiteral("thumbnails"));
        if (thumbs.canConvert<QVariantList>()) {
            const QVariantList list = thumbs.toList();
            if (!list.isEmpty()) {
                const QVariant last = list.last();
                if (last.userType() == QMetaType::QVariantMap) {
                    artworkUrl = last.toMap().value(QStringLiteral("url")).toString().toUtf8();
                }
            }
        }
    }
    artworkUrl = highResArtworkUrl(artworkUrl);
    QByteArray url = bytes(trackMap, "url");
    if (url.isEmpty() && !id.isEmpty()) {
        url = "https://music.youtube.com/watch?v=" + id;
    }

    QStringList artistStrings;
    const QVariant artistsValue = trackMap.value(QStringLiteral("artists"));
    if (artistsValue.canConvert<QStringList>()) {
        artistStrings = artistsValue.toStringList();
    }
    if (artistStrings.isEmpty()) {
        const QString artist = trackMap.value(QStringLiteral("artist")).toString();
        if (!artist.isEmpty()) {
            artistStrings.append(artist);
        }
    }

    QList<QByteArray> artistBytes;
    QList<const char *> artistPointers;
    artistBytes.reserve(artistStrings.size());
    artistPointers.reserve(artistStrings.size());
    for (const QString &artist : artistStrings) {
        artistBytes.append(artist.toUtf8());
    }
    for (const QByteArray &artist : artistBytes) {
        artistPointers.append(artist.constData());
    }

    const OrchardSystemMediaTrack track{
        .id = id.constData(),
        .title = title.constData(),
        .artists = artistPointers.constData(),
        .artist_count = static_cast<size_t>(artistPointers.size()),
        .album = album.constData(),
        .artwork_url = artworkUrl.constData(),
        .url = url.constData(),
    };

    double duration = state.value(QStringLiteral("durationSeconds")).toDouble();
    if (!qIsFinite(duration) || duration <= 0.0) {
        duration = state.value(QStringLiteral("duration")).toDouble();
    }
    const bool hasDuration = qIsFinite(duration) && duration > 0.0;

    double position = state.value(QStringLiteral("currentTime")).toDouble();
    if (!state.contains(QStringLiteral("currentTime")) && state.contains(QStringLiteral("position"))) {
        position = state.value(QStringLiteral("position")).toDouble();
    }
    if (!qIsFinite(position) || position < 0.0) {
        position = 0.0;
    }

    bool playing = state.value(QStringLiteral("isPlaying")).toBool();
    if (!state.contains(QStringLiteral("isPlaying")) && state.contains(QStringLiteral("playing"))) {
        playing = state.value(QStringLiteral("playing")).toBool();
    }

    const OrchardSystemMediaState mediaState{
        .track = trackMap.isEmpty() ? nullptr : &track,
        .playing = static_cast<uint8_t>(playing),
        .can_go_next = static_cast<uint8_t>(state.value(QStringLiteral("canGoNext")).toBool()),
        .can_go_previous = static_cast<uint8_t>(state.value(QStringLiteral("canGoPrevious")).toBool()),
        .can_seek = static_cast<uint8_t>(state.value(QStringLiteral("canSeek")).toBool()),
        .position_seconds = position,
        .duration_seconds = duration,
        .has_duration = static_cast<uint8_t>(hasDuration),
        .volume = state.value(QStringLiteral("volume"), 1.0).toDouble(),
        .repeat_mode = repeatMode(state.value(QStringLiteral("repeatMode")).toString()),
        .shuffle = static_cast<uint8_t>(state.value(QStringLiteral("shuffleEnabled")).toBool()),
    };

    const bool published = orchard_system_media_set_state(m_handle, &mediaState) != 0;
    const QString error = QString::fromUtf8(orchard_system_media_last_error(m_handle));
    if (m_lastError != error) {
        m_lastError = error;
        emit lastErrorChanged();
    }
    return published;
}

void SystemMediaBridge::stop()
{
    if (m_handle == nullptr) {
        return;
    }

    const bool wasAttached = attached();
    orchard_system_media_stop(m_handle);
    if (wasAttached) {
        emit attachedChanged();
    }
}

void SystemMediaBridge::receiveCommand(
    void *context,
    const OrchardSystemMediaCommand *command
)
{
    if (context == nullptr || command == nullptr) {
        return;
    }

    auto *bridge = static_cast<SystemMediaBridge *>(context);
    const QString kind = QString::fromUtf8(command->kind);
    QVariant value;
    if (command->has_number_value != 0) {
        value = command->number_value;
    } else if (command->has_bool_value != 0) {
        value = command->bool_value != 0;
    } else if (command->string_value != nullptr) {
        value = QString::fromUtf8(command->string_value);
    }

    const QPointer<SystemMediaBridge> guard(bridge);
    QMetaObject::invokeMethod(
        bridge,
        [guard, kind, value]() {
            if (guard) {
                emit guard->commandReceived(kind, value);
            }
        },
        Qt::QueuedConnection
    );
}

void SystemMediaBridge::destroyHandle()
{
    if (m_handle == nullptr) {
        return;
    }

    orchard_system_media_destroy(m_handle);
    m_handle = nullptr;
}
