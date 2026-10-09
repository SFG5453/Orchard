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

#pragma once

#include "discord_artwork.h"
#include "orchard_core.h"

#include <QObject>
#include <QHash>
#include <QNetworkAccessManager>
#include <QString>
#include <QVariantMap>

class OrchardAccount;
class AuthManager;
class YouTubeCatalog;
class QNetworkReply;

class Discord final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY enabledChanged)
    Q_PROPERTY(QString activityText READ activityText WRITE setActivityText NOTIFY activityTextChanged)
    Q_PROPERTY(QString detailsText READ detailsText WRITE setDetailsText NOTIFY detailsTextChanged)
    Q_PROPERTY(QString stateText READ stateText WRITE setStateText NOTIFY stateTextChanged)
    Q_PROPERTY(QString platform READ platform WRITE setPlatform NOTIFY platformChanged)
    Q_PROPERTY(QString activityType READ activityType WRITE setActivityType NOTIFY activityTypeChanged)
    Q_PROPERTY(QString statusDisplay READ statusDisplay WRITE setStatusDisplay NOTIFY statusDisplayChanged)
    Q_PROPERTY(bool projectButtonEnabled READ projectButtonEnabled WRITE setProjectButtonEnabled NOTIFY projectButtonEnabledChanged)
    Q_PROPERTY(bool animatedArtworkEnabled READ animatedArtworkEnabled WRITE setAnimatedArtworkEnabled NOTIFY animatedArtworkEnabledChanged)
    Q_PROPERTY(bool showSyncedLyrics READ showSyncedLyrics WRITE setShowSyncedLyrics NOTIFY showSyncedLyricsChanged)
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)

public:
    explicit Discord(OrchardAccount *account = nullptr, YouTubeCatalog *catalog = nullptr,
                     AuthManager *auth = nullptr, QObject *parent = nullptr);
    ~Discord() override;

    [[nodiscard]] bool enabled() const { return m_enabled; }
    [[nodiscard]] QString activityText() const { return m_activityText; }
    [[nodiscard]] QString detailsText() const { return m_detailsText; }
    [[nodiscard]] QString stateText() const { return m_stateText; }
    [[nodiscard]] QString platform() const { return m_platform; }
    [[nodiscard]] QString activityType() const { return m_activityType; }
    [[nodiscard]] QString statusDisplay() const { return m_statusDisplay; }
    [[nodiscard]] bool projectButtonEnabled() const { return m_projectButtonEnabled; }
    [[nodiscard]] bool animatedArtworkEnabled() const { return m_animatedArtworkEnabled; }
    [[nodiscard]] bool showSyncedLyrics() const { return m_showSyncedLyrics; }
    [[nodiscard]] bool connected() const { return m_connected; }
    [[nodiscard]] QString lastError() const { return m_lastError; }

    void setEnabled(bool enabled);
    void setActivityText(const QString &value);
    void setDetailsText(const QString &value);
    void setStateText(const QString &value);
    void setPlatform(const QString &value);
    void setActivityType(const QString &value);
    void setStatusDisplay(const QString &value);
    void setProjectButtonEnabled(bool enabled);
    void setAnimatedArtworkEnabled(bool enabled);
    void setShowSyncedLyrics(bool enabled);
    void updateLyric(const QString &trackId, const QString &text);

    Q_INVOKABLE void refresh();

    void updatePlayback(const QVariantMap &track, bool playing, double position,
                        double duration, bool mixing, const QString &incomingTitle);

signals:
    void enabledChanged();
    void activityTextChanged();
    void detailsTextChanged();
    void stateTextChanged();
    void platformChanged();
    void activityTypeChanged();
    void statusDisplayChanged();
    void projectButtonEnabledChanged();
    void animatedArtworkEnabledChanged();
    void showSyncedLyricsChanged();
    void connectedChanged();
    void lastErrorChanged();

private:
    static void receiveEvent(void *context, const char *message, uint8_t connected);
    void clearPresence();
    void updateArtwork();
    void updateArtistArtwork();
    void requestYouTubeArtistArtwork(const QString &key, const QString &browseId);
    void finishArtistArtwork(const QString &key, const QString &imageUrl);
    [[nodiscard]] QString trackKey() const;
    void refresh(bool force);
    void save(const QString &key, const QVariant &value);
    void setLastError(const QString &message);
    void setConnected(bool connected);

    OrchardDiscordRpcHandle *m_handle = nullptr;
    bool m_enabled = true;
    QString m_activityText;
    QString m_detailsText;
    QString m_stateText;
    QString m_platform;
    QString m_activityType;
    QString m_statusDisplay;
    bool m_projectButtonEnabled = true;
    bool m_animatedArtworkEnabled = true;
    bool m_showSyncedLyrics = false;
    bool m_connected = false;
    QString m_lastError;

    QVariantMap m_track;
    bool m_playing = false;
    double m_position = 0.0;
    double m_duration = 0.0;
    bool m_presenceSent = false;
    QString m_lastTrackKey;
    bool m_lastSentPlaying = false;
    double m_lastSentPosition = 0.0;
    double m_lastSentDuration = 0.0;
    QString m_lyricTrackId;
    QString m_lyric;
    QString m_lastSentLyric;
    QString m_mixText;
    QString m_lastSentMixText;

    DiscordArtwork m_artwork;
    // Hosted animated artwork for m_artworkTrackKey, once uploaded.
    QString m_artworkTrackKey;
    QString m_artworkSource;
    QString m_hostedArtwork;
    // Bumped per request so a slow upload for an old track is ignored.
    quint64 m_artworkGeneration = 0;

    YouTubeCatalog *m_catalog = nullptr;
    AuthManager *m_auth = nullptr;
    QNetworkAccessManager m_artistNetwork;
    QNetworkReply *m_artistReply = nullptr;
    QHash<QString, QString> m_artistImageCache;
    QString m_artistArtworkKey;
    QString m_artistImage;
    quint64 m_artistYouTubeRequest = 0;
};
