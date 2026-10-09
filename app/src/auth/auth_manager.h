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
#include <QMap>
#include <QObject>
#include <QJsonObject>
#include <QSettings>
#include <QString>
#include <QProcess>
#include <QTimer>

class YouTubeProvider;
class BrowserCookieAdapter;
class AuthSessionServer;

class AuthManager final : public QObject
{
    Q_OBJECT

    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(bool isSignedIn READ isSignedIn NOTIFY statusChanged)
    Q_PROPERTY(QString userName READ userName NOTIFY userChanged)
    Q_PROPERTY(QString userEmail READ userEmail NOTIFY userChanged)
    Q_PROPERTY(QString userAvatar READ userAvatar NOTIFY userChanged)
    Q_PROPERTY(QString userHandle READ userHandle NOTIFY userChanged)
    Q_PROPERTY(bool profileLoading READ profileLoading NOTIFY statusChanged)
    Q_PROPERTY(bool authWebViewActive READ authWebViewActive NOTIFY statusChanged)
    Q_PROPERTY(QString cookie READ cookie NOTIFY sessionChanged)
    Q_PROPERTY(QString visitorData READ visitorData NOTIFY sessionChanged)
    Q_PROPERTY(QString dataSyncId READ dataSyncId NOTIFY sessionChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
    Q_PROPERTY(QString loginUrl READ loginUrl CONSTANT)
    Q_PROPERTY(QString channelSwitcherUrl READ channelSwitcherUrl CONSTANT)
    // Parent: the account chooser is open. Helper: this window is the chooser.
    Q_PROPERTY(bool accountSwitching READ accountSwitching NOTIFY statusChanged)
    Q_PROPERTY(bool switchMode READ switchMode NOTIFY statusChanged)

public:
    explicit AuthManager(YouTubeProvider *youtubeProvider,
                         bool embeddedWebAuth = false,
                         QObject *parent = nullptr);

    [[nodiscard]] QString status() const { return m_status; }
    [[nodiscard]] bool isSignedIn() const { return m_status == QStringLiteral("signed_in"); }
    [[nodiscard]] QString userName() const { return m_userName; }
    [[nodiscard]] QString userEmail() const { return m_userEmail; }
    [[nodiscard]] QString userAvatar() const { return m_userAvatar; }
    [[nodiscard]] QString userHandle() const { return m_userHandle; }
    [[nodiscard]] bool profileLoading() const { return m_profileLoading; }
    [[nodiscard]] bool authWebViewActive() const { return m_authWebViewActive; }
    [[nodiscard]] QString cookie() const { return m_cookie; }
    [[nodiscard]] QString visitorData() const { return m_visitorData; }
    [[nodiscard]] QString dataSyncId() const { return m_dataSyncId; }
    [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
    [[nodiscard]] bool accountSwitching() const { return m_switchingAccount; }
    [[nodiscard]] bool switchMode() const { return m_switchMode; }
    // Helper only: open YouTube's channel switcher instead of the login page.
    void setSwitchMode(bool switchMode) { m_switchMode = switchMode; }
    [[nodiscard]] QString loginUrl() const;
    [[nodiscard]] QString channelSwitcherUrl() const;
    [[nodiscard]] QJsonObject sessionObject() const;

    Q_INVOKABLE void startLogin();
    Q_INVOKABLE void cancelLogin();
    Q_INVOKABLE void signOut();
    // Picks another YouTube channel or Google account without signing out.
    Q_INVOKABLE void switchAccount();
    Q_INVOKABLE bool setSession(const QString &cookie, const QString &visitorData = QString(), const QString &dataSyncId = QString());
    Q_INVOKABLE bool handlePageAuth(const QString &authJson);
    Q_INVOKABLE void submitPageAccount(const QString &token, const QString &responseJson);
    Q_INVOKABLE void submitPageProfile(const QString &profileJson);
    Q_INVOKABLE QString computeAuthorizationHeader(const QString &cookie, const QString &origin = QStringLiteral("https://music.youtube.com")) const;
    Q_INVOKABLE static bool hasYouTubeLoginCookie(const QString &cookie);
    static bool hasYouTubeSessionCookies(const QString &cookie);
    Q_INVOKABLE static QString normalizeYouTubeAuthCookie(const QString &cookie);
    Q_INVOKABLE static QMap<QString, QString> parseCookieString(const QString &cookie);
    static QString mergeCookies(const QString &base, const QString &overlay);
    Q_INVOKABLE void refreshSession();
    void acceptHelperResult(const QJsonObject &result);

signals:
    void statusChanged();
    void userChanged();
    void sessionChanged();
    void errorChanged();
    void loginCompleted();
    // A different account or channel replaced the signed-in one.
    void accountChanged();
    void cookieRefreshCompleted(bool valid);
    void profileProbeRequested();
    void pageAccountRequested(const QString &token, const QString &authorization);

private:
    friend class AuthManagerTest;
    void setStatus(const QString &status);
    void setErrorMessage(const QString &error);
    // auth_account_switch.cpp
    void launchHelper(bool switching);
    void helperFailed(const QString &message);
    bool acceptSwitchedAccount(const QJsonObject &result);
    void finishSwitchedAccount();
    void failSwitchedAccount(const QString &message);
    void receiveSwitchedProfile(const QJsonObject &profile);
    [[nodiscard]] QJsonObject publicChannelProfile() const;
    void requestMissingProfile();
    [[nodiscard]] QString accountIdentity() const;
    void restoreSession();
    void restoreSecretSession();
    void persistSecretSession();
    void deleteSecretSession();
    void startProfileResolution();
    void finishProfileResolution();
    void failProfileResolution(const QString &message);
    void mergeProfile(const QJsonObject &profile);
    [[nodiscard]] bool hasCompleteProfile() const;
    static QString delegatedSessionIdFromPageAuth(const QString &dataSyncId, const QString &delegatedSessionId);
    void scheduleCookieRefresh();
    void stopCookieRefresh();

    YouTubeProvider *m_youtubeProvider;
    BrowserCookieAdapter *m_browserCookies;
    QProcess *m_authHelper{nullptr};
    AuthSessionServer *m_authSessionServer{nullptr};
    QString m_status{QStringLiteral("signed_out")};
    QString m_userName;
    QString m_userEmail;
    QString m_userAvatar;
    QString m_userHandle;
    QString m_cookie;
    QString m_visitorData;
    QString m_dataSyncId;
    QString m_clientVersion;
    QString m_errorMessage;
    QJsonObject m_pendingProfile;
    quint64 m_profileRequestId{0};
    quint64 m_switchProfileRequestId{0};
    quint64 m_switchValidationRequestId{0};
    QJsonObject m_pendingSwitch;
    quint64 m_profileGeneration{0};
    int m_accountIndex{0};
    int m_authCaptureState{-1};
    bool m_profileLoading{false};
    bool m_authWebViewActive{false};
    bool m_embeddedWebAuth{false};
    bool m_switchMode{false};
    bool m_switchingAccount{false};
    QTimer m_cookieRefreshTimer;
};
