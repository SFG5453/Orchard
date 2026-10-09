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

#include <QDateTime>
#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <QTimer>
#include <QUrl>
#include <QVariantList>

#include <functional>

class QNetworkReply;
class QTcpServer;
class QTcpSocket;

// Orchard account session (services/account). Separate from the YouTube
// session in AuthManager: this one is ours, and it follows you across devices.
class OrchardAccount final : public QObject
{
    Q_OBJECT

    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(bool isSignedIn READ isSignedIn NOTIFY statusChanged)
    Q_PROPERTY(QString userId READ userId NOTIFY userChanged)
    Q_PROPERTY(QString userName READ userName NOTIFY userChanged)
    Q_PROPERTY(QString userEmail READ userEmail NOTIFY userChanged)
    Q_PROPERTY(QString userPicture READ userPicture NOTIFY userChanged)
    Q_PROPERTY(QString deviceId READ deviceId NOTIFY userChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
    Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
    Q_PROPERTY(bool devicesLoading READ devicesLoading NOTIFY devicesChanged)

public:
    struct Options {
        QUrl serviceUrl;
        // Off in tests so they never touch the real OS keyring.
        bool useKeychain{true};
        // Replaced in tests; defaults to the system browser.
        std::function<void(const QUrl &)> openBrowser;
    };

    struct CallbackResult {
        bool valid{false};
        QString code;
        QString state;
        QString error;
    };

    explicit OrchardAccount(QObject *parent = nullptr);
    OrchardAccount(Options options, QObject *parent = nullptr);
    ~OrchardAccount() override;

    [[nodiscard]] QString status() const { return m_status; }
    [[nodiscard]] bool isSignedIn() const { return m_status == QStringLiteral("signed_in"); }
    [[nodiscard]] QString userId() const { return m_userId; }
    [[nodiscard]] QString userName() const { return m_userName; }
    [[nodiscard]] QString userEmail() const { return m_userEmail; }
    [[nodiscard]] QString userPicture() const { return m_userPicture; }
    [[nodiscard]] QString deviceId() const { return m_deviceId; }
    [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
    [[nodiscard]] QVariantList devices() const { return m_devices; }
    [[nodiscard]] bool devicesLoading() const { return m_devicesLoading; }
    [[nodiscard]] QUrl serviceUrl() const { return m_serviceUrl; }

    Q_INVOKABLE void signIn();
    Q_INVOKABLE void cancelSignIn();
    Q_INVOKABLE void signOut();
    Q_INVOKABLE void refreshDevices();
    Q_INVOKABLE void removeDevice(const QString &deviceId);

    // Calls back with a valid access token, refreshing first if needed.
    // An empty token means there is no usable session.
    void withAccessToken(std::function<void(const QString &)> callback);

    static QString pkceChallenge(const QString &verifier);
    static CallbackResult parseCallbackRequest(const QByteArray &requestHead);
    static QString defaultDeviceName();
    static QString platformName();

signals:
    void statusChanged();
    void userChanged();
    void errorChanged();
    void devicesChanged();
    void signedIn();

private:
    void setStatus(const QString &status);
    void setErrorMessage(const QString &message);
    void restoreSession();
    void persistSession();
    void deleteSession();
    void stopCallbackServer();
    void handleCallbackConnection(QTcpSocket *socket);
    void redeemCode(const QString &code);
    void acceptTokens(const QJsonObject &body);
    void refreshAccessToken();
    void finishTokenWaiters(const QString &token);
    void clearSession();
    [[nodiscard]] QNetworkRequest jsonRequest(const QString &path) const;
    [[nodiscard]] QNetworkRequest authorizedRequest(const QString &path, const QString &token) const;

    QUrl m_serviceUrl;
    bool m_useKeychain{true};
    std::function<void(const QUrl &)> m_openBrowser;
    QNetworkAccessManager m_network;

    QString m_status{QStringLiteral("signed_out")};
    QString m_errorMessage;
    QString m_userId;
    QString m_userName;
    QString m_userEmail;
    QString m_userPicture;
    QString m_deviceId;
    QString m_refreshToken;
    QString m_accessToken;
    QDateTime m_accessExpiry;
    QVariantList m_devices;
    bool m_devicesLoading{false};

    QTcpServer *m_callbackServer{nullptr};
    QString m_pkceVerifier;
    QString m_signInState;
    QString m_redirectUri;
    QTimer m_signInTimeout;

    QNetworkReply *m_refreshReply{nullptr};
    QList<std::function<void(const QString &)>> m_tokenWaiters;
    // Bumped on sign-in and sign-out so late replies cannot resurrect a session.
    quint64 m_generation{0};
};
