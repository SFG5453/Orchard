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

#include "auth_manager.h"
#include "auth_diagnostics.h"
#include "auth_session_channel.h"
#include "browser_cookie_adapter.h"
#include "providers/youtube/youtube_provider.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTimer>
#include <QUrl>

AuthManager::AuthManager(YouTubeProvider *youtubeProvider, bool embeddedWebAuth,
                         QObject *parent)
    : QObject(parent), m_youtubeProvider(youtubeProvider),
      m_browserCookies(new BrowserCookieAdapter(this)),
      m_embeddedWebAuth(embeddedWebAuth) {
  connect(m_browserCookies, &BrowserCookieAdapter::errorOccurred, this,
          [this](const QString &message) {
            if (m_authWebViewActive)
              failProfileResolution(message);
          });
  connect(m_browserCookies, &BrowserCookieAdapter::cookiesChanged, this,
          [this] {
            const QString complete =
                normalizeYouTubeAuthCookie(m_browserCookies->cookies());
            const auto browser = parseCookieString(complete);
            const auto saved = parseCookieString(m_cookie);
            // Only repair the same account's session; switching accounts stays
            // in login.
            if (m_cookie.isEmpty() ||
                browser.value(QStringLiteral("SAPISID")).isEmpty() ||
                browser.value(QStringLiteral("SAPISID")) !=
                    saved.value(QStringLiteral("SAPISID")) ||
                complete == m_cookie)
              return;
            m_cookie = complete;
            if (isSignedIn())
              persistSecretSession();
            emit sessionChanged();
          });
  connect(m_youtubeProvider, &YouTubeProvider::resultReady, this,
          [this](quint64 requestId, const QJsonValue &result) {
            if (m_switchValidationRequestId && requestId == m_switchValidationRequestId) {
              finishSwitchedAccount();
              return;
            }
            if (m_switchProfileRequestId && requestId == m_switchProfileRequestId) {
              receiveSwitchedProfile(result.toObject());
              return;
            }
            if (!m_profileLoading || requestId != m_profileRequestId ||
                !result.isObject())
              return;
            mergeProfile(result.toObject());
            if (hasCompleteProfile() || !m_authWebViewActive) {
              finishProfileResolution();
              return;
            }
            const quint64 generation = m_profileGeneration;
            emit profileProbeRequested();
            QTimer::singleShot(5000, this, [this, generation] {
              if (m_profileLoading && generation == m_profileGeneration)
                finishProfileResolution();
            });
          });
  connect(m_youtubeProvider, &YouTubeProvider::requestFailed, this,
          [this](quint64 requestId, const QString &message) {
            if (m_switchValidationRequestId && requestId == m_switchValidationRequestId) {
              failSwitchedAccount(tr("Could not use the selected YouTube channel: %1").arg(message));
              return;
            }
            if (m_switchProfileRequestId && requestId == m_switchProfileRequestId) {
              m_switchProfileRequestId = 0;
              return;
            }
            if (!m_profileLoading || requestId != m_profileRequestId)
              return;
            qWarning().noquote() << "YouTube profile lookup failed:" << message;
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
            if (hasYouTubeSessionCookies(m_cookie)) {
              // profile metadata is best effort once the browser's
              // native cookie session has been captured.
              finishProfileResolution();
              return;
            }
#endif
            if (!m_authWebViewActive) {
              // document.cookie omits HttpOnly credentials. Let the browser
              // restore and validate its own complete session.
              m_profileLoading = false;
              m_profileRequestId = 0;
              startLogin();
              return;
            }
            failProfileResolution(message);
          });
  // Cookie refresh timer, the v4 equivalent of v2's refreshBrowserAuth(). (get
  // it? becuase qml uses a v4 engine im so hilarious)
  connect(&m_cookieRefreshTimer, &QTimer::timeout, this,
          &AuthManager::refreshSession);
  // The helper starts browser authentication explicitly. Restoring the keychain
  // here can race startLogin() and enter signed_in without loginCompleted.
  if (!m_embeddedWebAuth)
    restoreSession();
  if (m_status == QStringLiteral("signed_in") &&
      (m_userName.isEmpty() || m_userHandle.isEmpty() ||
       m_userAvatar.isEmpty())) {
    QTimer::singleShot(0, this, [this] {
      if (m_status == QStringLiteral("signed_in"))
        startProfileResolution();
    });
  }
  // If we restored a signed-in session, kick off the refresh timer and do
  // one immediate check once the event loop is running.
  if (isSignedIn() && !m_embeddedWebAuth) {
    scheduleCookieRefresh();
    QTimer::singleShot(0, this, &AuthManager::refreshSession);
  }
}

QString AuthManager::loginUrl() const {
  return QStringLiteral(
      "https://accounts.google.com/"
      "ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com");
}

QString AuthManager::channelSwitcherUrl() const {
  return QStringLiteral("https://www.youtube.com/channel_switcher");
}

void AuthManager::startLogin() {
  authDiagnostic(
      QStringLiteral("login-request embedded=%1").arg(m_embeddedWebAuth));
  setErrorMessage(QString());
  if (!m_embeddedWebAuth) {
    launchHelper(false);
    return;
  }
  ++m_profileGeneration;
  m_profileLoading = false;
  m_authCaptureState = -1;
  m_browserCookies->start();
  if (!m_authWebViewActive) {
    m_authWebViewActive = true;
    emit statusChanged();
  }
  setStatus(QStringLiteral("starting"));
}

void AuthManager::acceptHelperResult(const QJsonObject &result) {
  const QString cookie = normalizeYouTubeAuthCookie(
      result.value(QStringLiteral("cookie")).toString());
  if (!hasYouTubeLoginCookie(cookie)) {
    setStatus(QStringLiteral("signed_out"));
    setErrorMessage(
        QStringLiteral("The sign-in window returned an invalid session."));
    return;
  }
  ++m_profileGeneration;
  m_profileLoading = false;
  authDiagnostic(QStringLiteral("helper-session-accepted"));
  m_cookie = cookie;
  m_visitorData = result.value(QStringLiteral("visitorData")).toString();
  m_dataSyncId = result.value(QStringLiteral("dataSyncId")).toString();
  m_clientVersion = result.value(QStringLiteral("clientVersion")).toString();
  m_accountIndex =
      qMax(0, result.value(QStringLiteral("accountIndex")).toInt());
  m_userName = result.value(QStringLiteral("userName")).toString();
  m_userEmail = result.value(QStringLiteral("userEmail")).toString();
  m_userAvatar = result.value(QStringLiteral("userAvatar")).toString();
  m_userHandle = result.value(QStringLiteral("userHandle")).toString();
  m_switchProfileRequestId = 0;
  persistSecretSession();
  setStatus(QStringLiteral("signed_in"));
  emit sessionChanged();
  emit userChanged();
  emit loginCompleted();
  requestMissingProfile();
}

QJsonObject AuthManager::publicChannelProfile() const {
  QJsonObject publicChannel;
  if (m_dataSyncId.startsWith(QStringLiteral("UC")) && m_dataSyncId.size() == 24) {
    publicChannel.insert(QStringLiteral("channelId"), m_dataSyncId);
  } else if (m_userHandle.startsWith(QLatin1Char('@'))) {
    // A handle gives the public page a second chance when accounts_list sulks.
    const QString encoded = QString::fromLatin1(
        QUrl::toPercentEncoding(m_userHandle, QByteArray("@")));
    publicChannel.insert(QStringLiteral("channelUrl"),
                         QStringLiteral("https://www.youtube.com/%1").arg(encoded));
  }
  return publicChannel;
}

void AuthManager::requestMissingProfile() {
  if (!isSignedIn() || (!m_userName.isEmpty() && !m_userAvatar.isEmpty()))
    return;
  const QJsonObject publicChannel = publicChannelProfile();
  m_switchProfileRequestId = publicChannel.isEmpty()
                                 ? m_youtubeProvider->loadAccountProfile(sessionObject())
                                 : m_youtubeProvider->enrichAccountProfile(publicChannel);
}

void AuthManager::cancelLogin() {
  authDiagnostic(QStringLiteral("login-cancelled"));
  if (!m_embeddedWebAuth && m_authHelper &&
      m_authHelper->state() != QProcess::NotRunning) {
    m_authSessionServer->close();
    m_authHelper->terminate();
    setStatus(QStringLiteral("signed_out"));
    return;
  }
  if (m_authWebViewActive &&
      (m_status == QStringLiteral("starting") || m_profileLoading)) {
    signOut();
  }
}

void AuthManager::signOut() {
  authDiagnostic(QStringLiteral("sign-out"));
  m_pendingSwitch = {};
  m_switchValidationRequestId = 0;
  m_switchProfileRequestId = 0;
  m_switchingAccount = false;
  m_cookie.clear();
  m_visitorData.clear();
  m_dataSyncId.clear();
  m_accountIndex = 0;
  m_userName.clear();
  m_userEmail.clear();
  m_userAvatar.clear();
  m_userHandle.clear();
  m_clientVersion.clear();
  m_pendingProfile = {};
  m_profileLoading = false;
  m_authWebViewActive = false;
  m_profileRequestId = 0;
  ++m_profileGeneration;
  m_youtubeProvider->cancelAll();
  deleteSecretSession();
  setErrorMessage(QString());

  QSettings settings;
  settings.beginGroup(QStringLiteral("auth"));
  settings.remove(QString());
  settings.endGroup();

  setStatus(QStringLiteral("signed_out"));
  emit sessionChanged();
  emit userChanged();
}

bool AuthManager::setSession(const QString &cookie, const QString &visitorData,
                             const QString &dataSyncId) {
  const QString normalized = normalizeYouTubeAuthCookie(cookie.trimmed());
  if (!hasYouTubeLoginCookie(normalized)) {
    setErrorMessage(
        QStringLiteral("The provided cookie does not contain required YouTube "
                       "credentials (SAPISID or __Secure-3PAPISID)."));
    return false;
  }

  m_cookie = normalized;
  if (!visitorData.isEmpty()) {
    m_visitorData = visitorData.trimmed();
  }
  if (!dataSyncId.isEmpty()) {
    m_dataSyncId = dataSyncId.trimmed();
  }
  setErrorMessage(QString());
  m_authWebViewActive = false;
  startProfileResolution();
  return true;
}

void AuthManager::setStatus(const QString &status) {
  if (m_status != status) {
    authDiagnostic(QStringLiteral("status %1 -> %2").arg(m_status, status));
    m_status = status;
    // Keep the cookie refresh timer in sync with the auth lifecycle.
    if (status == QStringLiteral("signed_in"))
      scheduleCookieRefresh();
    else if (status == QStringLiteral("signed_out"))
      stopCookieRefresh();
    emit statusChanged();
  }
}

void AuthManager::setErrorMessage(const QString &error) {
  if (m_errorMessage != error) {
    authDiagnostic(error.isEmpty() ? QStringLiteral("error-cleared")
                                   : QStringLiteral("error-set"));
    m_errorMessage = error;
    emit errorChanged();
  }
}

QJsonObject AuthManager::sessionObject() const {
  return {{QStringLiteral("cookie"), m_cookie},
          {QStringLiteral("visitorData"), m_visitorData},
          {QStringLiteral("dataSyncId"), m_dataSyncId},
          {QStringLiteral("clientVersion"), m_clientVersion},
          {QStringLiteral("accountIndex"), m_accountIndex}};
}

// re-reads the browser cookie store and checks that credentials haven't gone for a smoke break.
// Called on a 15-minute timer and before authenticated network requests.
void AuthManager::refreshSession() {
  if (m_embeddedWebAuth || m_authWebViewActive || m_profileLoading)
    return;
  if (!isSignedIn() || m_cookie.isEmpty())
    return;

  const QString fresh = normalizeYouTubeAuthCookie(m_browserCookies->cookies());
  if (!fresh.isEmpty() && fresh != m_cookie) {
    const auto freshParsed = parseCookieString(fresh);
    const auto savedParsed = parseCookieString(m_cookie);
    // Only absorb cookies from the same account don't silently swap
    // identities.
    if (!freshParsed.value(QStringLiteral("SAPISID")).isEmpty() &&
        freshParsed.value(QStringLiteral("SAPISID")) ==
            savedParsed.value(QStringLiteral("SAPISID"))) {
      m_cookie = fresh;
      persistSecretSession();
      emit sessionChanged();
    }
  }

  // Validate that the stored cookie still has login credentials.
  // If something ate our cookies (browser cleanup, expiry, gremlins),
  // transition to signed_out so the UI can prompt re-login.
  if (!hasYouTubeLoginCookie(m_cookie)) {
    authDiagnostic(QStringLiteral("cookie-refresh-session-expired"));
    setStatus(QStringLiteral("signed_out"));
    setErrorMessage(QStringLiteral(
        "Your YouTube session has expired. Please sign in again."));
    emit cookieRefreshCompleted(false);
    return;
  }

  emit cookieRefreshCompleted(true);
}

// 15 minutes. frequent enough to catch stale cookies before they cause
// playback failures, rare enough to not annoy the event loop.
void AuthManager::scheduleCookieRefresh() {
  if (m_embeddedWebAuth)
    return;
  constexpr int refreshIntervalMs = 15 * 60 * 1000;
  m_cookieRefreshTimer.start(refreshIntervalMs);
}

void AuthManager::stopCookieRefresh() { m_cookieRefreshTimer.stop(); }
