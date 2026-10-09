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

#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <QUrl>
#include <QVariantMap>

#include <functional>
#include <optional>

// Last.fm scrobbling through services/lastfm, which holds the API key and
// secret. Only the per-user session key lives on this machine (OS keyring).
class Lastfm final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY enabledChanged)
    // restoring, disconnected, authorizing, pending, completing, connected
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(bool connected READ connected NOTIFY statusChanged)
    Q_PROPERTY(QString user READ user NOTIFY userChanged)
    Q_PROPERTY(QString message READ message NOTIFY messageChanged)
    Q_PROPERTY(bool messageIsError READ messageIsError NOTIFY messageChanged)

public:
    struct Options {
        QUrl serviceUrl;
        // Off in tests so they never touch the real OS keyring.
        bool useKeychain{true};
        // Replaced in tests; defaults to the system browser.
        std::function<void(const QUrl &)> openBrowser;
        // Milliseconds since the epoch. Replaced in tests to fake listening time.
        std::function<qint64()> clock;
        // Tests skip QSettings so the enabled toggle cannot leak between runs.
        bool useSettings{true};
    };

    explicit Lastfm(QObject *parent = nullptr);
    Lastfm(Options options, QObject *parent = nullptr);

    [[nodiscard]] bool enabled() const { return m_enabled; }
    [[nodiscard]] QString status() const { return m_status; }
    [[nodiscard]] bool connected() const { return m_status == QStringLiteral("connected"); }
    [[nodiscard]] QString user() const { return m_user; }
    [[nodiscard]] QString message() const { return m_message; }
    [[nodiscard]] bool messageIsError() const { return m_messageIsError; }

    void setEnabled(bool enabled);

    // Named to stay clear of QObject::connect/disconnect.
    Q_INVOKABLE void connectAccount();
    Q_INVOKABLE void completeConnection();
    Q_INVOKABLE void cancelConnection();
    Q_INVOKABLE void disconnectAccount();

    void updatePlayback(const QVariantMap &track, bool playing, double position,
                        double duration);

    // Last.fm rule: longer than 30 s, and heard for half its length or 4 minutes.
    static bool shouldScrobble(double duration, double playedSeconds);
    // Empty when the track lacks a title or artist.
    static QJsonObject trackPayload(const QVariantMap &track, double duration);

signals:
    void enabledChanged();
    void statusChanged();
    void userChanged();
    void messageChanged();

private:
    struct Play {
        QString key;
        QJsonObject track;
        qint64 timestamp{0};
        double lastPosition{0.0};
        qint64 lastReportedAt{0};
        double playedSeconds{0.0};
        bool scrobbled{false};
        bool submitting{false};
        qint64 retryAt{0};
    };

    using ReplyHandler = std::function<void(int status, const QJsonObject &body)>;

    void post(const QString &path, const QJsonObject &body, ReplyHandler handler);
    void trackPlayback();
    void submitScrobble();
    void handleTrackFailure(int status, const QJsonObject &body);
    void acceptSession(const QString &user, const QString &sessionKey);
    void forgetSession();
    void restoreSession();
    void persistSession();
    void deleteSession();
    void setStatus(const QString &status);
    void setMessage(const QString &message, bool error = false);
    [[nodiscard]] qint64 now() const { return m_clock(); }

    QUrl m_serviceUrl;
    bool m_useKeychain{true};
    bool m_useSettings{true};
    std::function<void(const QUrl &)> m_openBrowser;
    std::function<qint64()> m_clock;
    QNetworkAccessManager m_network;

    bool m_enabled{true};
    QString m_status{QStringLiteral("disconnected")};
    QString m_user;
    QString m_sessionKey;
    QString m_message;
    bool m_messageIsError{false};

    QString m_pendingToken;
    qint64 m_pendingExpiresAt{0};

    QVariantMap m_track;
    bool m_playing{false};
    double m_position{0.0};
    double m_duration{0.0};
    std::optional<Play> m_play;

    // Bumped on connect and disconnect so late replies cannot resurrect a session.
    quint64 m_generation{0};
};
