/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "discord.h"
#include "discord_sdk_loader.h"
#include "auth/auth_manager.h"
#include "apple_music_links.h"
#include "youtube_links.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QMetaObject>
#include <QPointer>
#include <QRegularExpression>
#include <QSettings>
#include <QStringList>
#include <QtGlobal>
#include <cmath>

namespace {
QString artworkUrl(const QVariantMap &track)
{
    for (const QString &key : {QStringLiteral("thumbnail"),
                               QStringLiteral("artworkUrl"),
                               QStringLiteral("heroThumbnail"),
                               QStringLiteral("artwork"),
                               QStringLiteral("cover")}) {
        const QString value = track.value(key).toString().trimmed();
        if (!value.isEmpty()) {
            return value;
        }
    }
    return {};
}

bool isAppleTrack(const QVariantMap &track)
{
    return track.value(QStringLiteral("source")).toString() == QLatin1String("apple_music") ||
           track.value(QStringLiteral("playbackSource")).toString() == QLatin1String("apple_music") ||
           track.value(QStringLiteral("id")).toString().startsWith(QLatin1String("am."));
}

QString listenUrl(const QVariantMap &track)
{
    const QString id = track.value(QStringLiteral("id")).toString().trimmed();
    if (isAppleTrack(track)) {
        const QString appleId = track.value(QStringLiteral("appleId")).toString().trimmed();
        return appleMusicShareUrl(isAppleMusicBrowseId(id) || appleId.isEmpty() ? id : QStringLiteral("am.songs.") + appleId);
    }
    if (!id.isEmpty()) {
        return youtubeShareUrl(id);
    }

    const QString direct = track.value(QStringLiteral("url")).toString().trimmed();
    if (!direct.isEmpty()) {
        return youtubeShareUrl(direct);
    }

    return {};
}

} // namespace

void Discord::refresh(bool force)
{
    if (!m_enabled) {
        clearPresence();
        return;
    }

    const QString song = m_track.value(QStringLiteral("title")).toString().trimmed();
    if (song.isEmpty()) {
        clearPresence();
        return;
    }

    if (!ensureClient()) {
        return;
    }

    const QString currentKey = trackKey();
    const QString lyric = m_mixText.isEmpty() && m_showSyncedLyrics && m_lyricTrackId == currentKey
                              ? m_lyric
                              : QString();
    if (!force && m_presenceSent && currentKey == m_lastTrackKey &&
        m_playing == m_lastSentPlaying &&
        lyric == m_lastSentLyric &&
        m_mixText == m_lastSentMixText &&
        std::abs(m_position - m_lastSentPosition) < 5.0 &&
        std::abs(m_duration - m_lastSentDuration) < 1.0) {
        return;
    }

    const bool apple = isAppleTrack(m_track);
    // The default "YouTube Music" entry follows the service of the playing track.
    const QString platform = apple && m_platform == QLatin1String("YouTube Music")
                                 ? QStringLiteral("Apple Music")
                                 : m_platform;
    const QJsonObject payload{
        {QStringLiteral("song"), song},
        {QStringLiteral("artist"), m_track.value(QStringLiteral("artist")).toString()},
        {QStringLiteral("album"), m_track.value(QStringLiteral("album")).toString()},
        {QStringLiteral("platform"), platform},
        {QStringLiteral("app"), QStringLiteral("Orchard V3")},
        {QStringLiteral("status"), m_playing ? QStringLiteral("Playing") : QStringLiteral("Paused")},
        {QStringLiteral("activityText"), m_activityText},
        {QStringLiteral("detailsText"), m_detailsText},
        {QStringLiteral("stateText"), m_stateText},
        {QStringLiteral("lyric"), lyric},
        {QStringLiteral("mixText"), m_mixText},
        {QStringLiteral("activityType"), m_activityType},
        {QStringLiteral("statusDisplay"), m_statusDisplay},
        // Hosted motion artwork once ready; the static cover until then.
        {QStringLiteral("artworkUrl"), !m_hostedArtwork.isEmpty() && m_artworkTrackKey == currentKey
                                           ? m_hostedArtwork
                                           : artworkUrl(m_track)},
        {QStringLiteral("artworkText"), QStringLiteral("{album}")},
        {QStringLiteral("artistImageUrl"), m_artistImage},
        {QStringLiteral("listenUrl"), listenUrl(m_track)},
        {QStringLiteral("listenButtonText"), apple ? QStringLiteral("Listen on Apple Music")
                                                              : QStringLiteral("Listen on YouTube Music")},
        {QStringLiteral("projectButton"), m_projectButtonEnabled},
        {QStringLiteral("currentTime"), m_position},
        {QStringLiteral("duration"), m_duration},
        {QStringLiteral("isPlaying"), m_playing},
    };
    const QByteArray encoded = QJsonDocument(payload).toJson(QJsonDocument::Compact);
    if (m_api->setPresence(m_handle, encoded.constData()) == 0) {
        setLastError(QString::fromUtf8(m_api->lastError(m_handle)));
        return;
    }

    m_presenceSent = true;
    m_lastTrackKey = currentKey;
    m_lastSentPlaying = m_playing;
    m_lastSentPosition = m_position;
    m_lastSentDuration = m_duration;
    m_lastSentLyric = lyric;
    m_lastSentMixText = m_mixText;
}
