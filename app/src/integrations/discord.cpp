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
constexpr auto kApplicationId = "1531666622312353803";

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

QString normalizedActivityType(const QString &value)
{
    const QString lowered = value.trimmed().toLower();
    return QStringList{QStringLiteral("playing"), QStringLiteral("watching"),
                       QStringLiteral("competing"), QStringLiteral("listening")}
        .contains(lowered)
        ? lowered
        : QStringLiteral("listening");
}

QString normalizedStatusDisplay(const QString &value)
{
    const QString lowered = value.trimmed().toLower();
    return QStringList{QStringLiteral("state"), QStringLiteral("details"), QStringLiteral("name")}
        .contains(lowered)
        ? lowered
        : QStringLiteral("name");
}

QString normalizedLyric(const QString &text)
{
    const QString clean = text.simplified();
    static const QRegularExpression instrumental(
        QStringLiteral(R"(^(?:instrumental|\[instrumental\]|\(instrumental\))$)"),
        QRegularExpression::CaseInsensitiveOption);
    return instrumental.match(clean).hasMatch() ? QString() : clean;
}
} // namespace

Discord::Discord(OrchardAccount *account, YouTubeCatalog *catalog, AuthManager *auth, QObject *parent)
    : QObject(parent), m_artwork(account, this), m_catalog(catalog), m_auth(auth), m_artistNetwork(this)
{
    QSettings settings;
    settings.beginGroup(QStringLiteral("integrations/discord"));
    m_enabled = settings.value(QStringLiteral("enabled"), true).toBool();
    m_activityText = settings.value(
        QStringLiteral("activityText"),
        QStringLiteral("{song}")).toString();
    m_detailsText = settings.value(QStringLiteral("detailsText"), QString()).toString();
    m_stateText = settings.value(QStringLiteral("stateText"), QString()).toString();
    m_platform = settings.value(QStringLiteral("platform"), QStringLiteral("YouTube Music"))
                     .toString();
    m_activityType = normalizedActivityType(
        settings.value(QStringLiteral("activityType"), QStringLiteral("listening")).toString());
    m_statusDisplay = normalizedStatusDisplay(
        settings.value(QStringLiteral("statusDisplay"), QStringLiteral("name")).toString());
    m_projectButtonEnabled =
        settings.value(QStringLiteral("projectButtonEnabled"), true).toBool();
    m_animatedArtworkEnabled =
        settings.value(QStringLiteral("animatedArtworkEnabled"), true).toBool();
    m_showSyncedLyrics = settings.value(QStringLiteral("showSyncedLyrics"), false).toBool();
    settings.endGroup();

    const QByteArray applicationId(kApplicationId);
    m_handle = orchard_discord_rpc_create(applicationId.constData(), &Discord::receiveEvent, this);

    connectArtistCatalog();
}

Discord::~Discord()
{
    clearPresence();
    if (m_handle != nullptr) {
        orchard_discord_rpc_destroy(m_handle);
        m_handle = nullptr;
    }
}

void Discord::setEnabled(bool enabled)
{
    if (m_enabled == enabled) {
        return;
    }

    m_enabled = enabled;
    save(QStringLiteral("enabled"), enabled);
    emit enabledChanged();
    updateArtwork();
    updateArtistArtwork();
    if (enabled) {
        refresh(true);
    } else {
        clearPresence();
    }
}

void Discord::setActivityText(const QString &value)
{
    if (m_activityText == value) {
        return;
    }

    m_activityText = value;
    save(QStringLiteral("activityText"), value);
    emit activityTextChanged();
    refresh(true);
}

void Discord::setDetailsText(const QString &value)
{
    if (m_detailsText == value) {
        return;
    }

    m_detailsText = value;
    save(QStringLiteral("detailsText"), value);
    emit detailsTextChanged();
    refresh(true);
}

void Discord::setStateText(const QString &value)
{
    if (m_stateText == value) {
        return;
    }

    m_stateText = value;
    save(QStringLiteral("stateText"), value);
    emit stateTextChanged();
    refresh(true);
}

void Discord::setPlatform(const QString &value)
{
    if (m_platform == value) {
        return;
    }

    m_platform = value;
    save(QStringLiteral("platform"), value);
    emit platformChanged();
    refresh(true);
}

void Discord::setActivityType(const QString &value)
{
    const QString normalized = normalizedActivityType(value);
    if (m_activityType == normalized) {
        return;
    }

    m_activityType = normalized;
    save(QStringLiteral("activityType"), normalized);
    emit activityTypeChanged();
    refresh(true);
}

void Discord::setStatusDisplay(const QString &value)
{
    const QString normalized = normalizedStatusDisplay(value);
    if (m_statusDisplay == normalized) {
        return;
    }

    m_statusDisplay = normalized;
    save(QStringLiteral("statusDisplay"), normalized);
    emit statusDisplayChanged();
    refresh(true);
}

void Discord::setProjectButtonEnabled(bool enabled)
{
    if (m_projectButtonEnabled == enabled) {
        return;
    }

    m_projectButtonEnabled = enabled;
    save(QStringLiteral("projectButtonEnabled"), enabled);
    emit projectButtonEnabledChanged();
    refresh(true);
}

void Discord::setAnimatedArtworkEnabled(bool enabled)
{
    if (m_animatedArtworkEnabled == enabled) {
        return;
    }

    m_animatedArtworkEnabled = enabled;
    save(QStringLiteral("animatedArtworkEnabled"), enabled);
    emit animatedArtworkEnabledChanged();
    updateArtwork();
    refresh(true);
}

void Discord::setShowSyncedLyrics(bool enabled)
{
    if (m_showSyncedLyrics == enabled) {
        return;
    }
    m_showSyncedLyrics = enabled;
    save(QStringLiteral("showSyncedLyrics"), enabled);
    emit showSyncedLyricsChanged();
    // Turning this off puts the artist or custom state back right away.
    refresh(false);
}

void Discord::updateLyric(const QString &trackId, const QString &text)
{
    const QString clean = normalizedLyric(text);
    if (m_lyricTrackId == trackId && m_lyric == clean) {
        return;
    }
    m_lyricTrackId = trackId;
    m_lyric = clean;
    if (m_showSyncedLyrics && m_mixText.isEmpty() && trackId == trackKey()) {
        refresh(false);
    }
}

void Discord::refresh()
{
    refresh(true);
}

void Discord::updatePlayback(const QVariantMap &track, bool playing, double position,
                             double duration, bool mixing, const QString &incomingTitle)
{
    m_track = track;
    m_playing = playing;
    m_position = qIsFinite(position) && position >= 0.0 ? position : 0.0;
    m_duration = qIsFinite(duration) && duration > 0.0 ? duration : 0.0;
    const QString title = incomingTitle.simplified();
    m_mixText = mixing ? (title.isEmpty() ? QStringLiteral("Mixing")
                                          : QStringLiteral("Mixing into %1").arg(title))
                       : QString();
    updateArtwork();
    updateArtistArtwork();
    refresh(false);
}

QString Discord::trackKey() const
{
    const QString id = m_track.value(QStringLiteral("id")).toString().trimmed();
    return id.isEmpty() ? m_track.value(QStringLiteral("title")).toString().trimmed() : id;
}

void Discord::updateArtwork()
{
    const QString key = trackKey();
    // Set by PlaybackController only once it has resolved for this track.
    const QString source = m_track.value(QStringLiteral("animatedArtworkUrl")).toString().trimmed();
    if (!m_enabled || !m_animatedArtworkEnabled || key.isEmpty() || source.isEmpty()) {
        if (!m_artworkSource.isEmpty()) {
            ++m_artworkGeneration;
            m_artwork.cancel();
            m_artworkTrackKey.clear();
            m_artworkSource.clear();
            m_hostedArtwork.clear();
        }
        return;
    }
    if (key == m_artworkTrackKey && source == m_artworkSource) {
        return;
    }

    m_artworkTrackKey = key;
    m_artworkSource = source;
    m_hostedArtwork.clear();
    const quint64 generation = ++m_artworkGeneration;
    qInfo().noquote() << "Discord artwork: preparing" << key;
    // prepare() replaces any job for the previous track.
    m_artwork.prepare(source, [this, generation, key](const QString &url) {
        if (generation != m_artworkGeneration || key != trackKey()) {
            qInfo().noquote() << "Discord artwork: stale result ignored for" << key;
            return;
        }
        if (url.isEmpty()) {
            return;
        }
        m_hostedArtwork = url;
        refresh(true);
    });
}

void Discord::refresh(bool force)
{
    if (!m_enabled || m_handle == nullptr) {
        clearPresence();
        return;
    }

    const QString song = m_track.value(QStringLiteral("title")).toString().trimmed();
    if (song.isEmpty()) {
        clearPresence();
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
    if (orchard_discord_rpc_set_presence(m_handle, encoded.constData()) == 0) {
        setLastError(QString::fromUtf8(orchard_discord_rpc_last_error(m_handle)));
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

void Discord::clearPresence()
{
    if (m_handle != nullptr && m_presenceSent) {
        if (orchard_discord_rpc_clear_presence(m_handle) == 0) {
            setLastError(QString::fromUtf8(orchard_discord_rpc_last_error(m_handle)));
        }
    }

    m_presenceSent = false;
    m_lastTrackKey.clear();
    m_lastSentPosition = 0.0;
    m_lastSentDuration = 0.0;
    m_lastSentLyric.clear();
    m_lastSentMixText.clear();
    setConnected(false);
}

void Discord::save(const QString &key, const QVariant &value)
{
    QSettings settings;
    settings.beginGroup(QStringLiteral("integrations/discord"));
    settings.setValue(key, value);
    settings.endGroup();
}

void Discord::setLastError(const QString &message)
{
    if (m_lastError == message) {
        return;
    }

    m_lastError = message;
    emit lastErrorChanged();
}

void Discord::setConnected(bool connected)
{
    if (m_connected == connected) {
        return;
    }

    m_connected = connected;
    emit connectedChanged();
}

void Discord::receiveEvent(void *context, const char *message, uint8_t connected)
{
    if (context == nullptr) {
        return;
    }

    auto *discord = static_cast<Discord *>(context);
    const QString copiedMessage = message == nullptr ? QString() : QString::fromUtf8(message);
    const bool isConnected = connected != 0;
    const QPointer<Discord> guard(discord);
    QMetaObject::invokeMethod(
        discord,
        [guard, copiedMessage, isConnected]() {
            if (!guard) {
                return;
            }

            // A queued success from a request that was cleared or disabled
            // must not make the settings page look connected again.
            if (!guard->m_enabled || !guard->m_presenceSent) {
                guard->setConnected(false);
                return;
            }

            guard->setConnected(isConnected);
            if (isConnected) {
                guard->setLastError(QString());
            } else if (!copiedMessage.isEmpty()) {
                guard->setLastError(copiedMessage);
            }
        },
        Qt::QueuedConnection);
}
