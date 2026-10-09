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
#include "providers/youtube/youtube_provider.h"

#include <QCoreApplication>
#include <QDir>
#include <QJsonObject>

namespace {
QString helperPath() {
#ifdef Q_OS_WIN
  const QString name = QStringLiteral("orchard-auth-helper.exe");
#else
  const QString name = QStringLiteral("orchard-auth-helper");
#endif
  return QDir(QCoreApplication::applicationDirPath()).filePath(name);
}
} // namespace

void AuthManager::switchAccount() {
  authDiagnostic(QStringLiteral("account-switch-request"));
  if (m_embeddedWebAuth || !isSignedIn() || m_switchingAccount)
    return;
  setErrorMessage(QString());
  launchHelper(true);
}

void AuthManager::helperFailed(const QString &message) {
  if (m_switchingAccount) {
    // The current account keeps playing; only the switch attempt failed.
    m_pendingSwitch = {};
    m_switchValidationRequestId = 0;
    m_switchingAccount = false;
    emit statusChanged();
  } else {
    setStatus(QStringLiteral("signed_out"));
  }
  setErrorMessage(message);
}

void AuthManager::launchHelper(bool switching) {
  if (m_authHelper && m_authHelper->state() != QProcess::NotRunning)
    return;
  if (!m_authHelper) {
    m_authHelper = new QProcess(this);
    // Credentials travel over a dedicated socket, independent of GUI stdout.
    m_authHelper->setStandardOutputFile(QProcess::nullDevice());
    m_authSessionServer = new AuthSessionServer(
        [this](const QJsonObject &session) {
          authDiagnostic(QStringLiteral("helper-session-received status=%1 switching=%2")
                             .arg(m_status)
                             .arg(m_switchingAccount));
          if (m_switchingAccount)
            return acceptSwitchedAccount(session);
          if (m_status != QStringLiteral("starting"))
            return false;
          acceptHelperResult(session);
          return isSignedIn();
        },
        this);
    connect(m_authHelper, &QProcess::errorOccurred, this,
            [this](QProcess::ProcessError error) {
              if (error != QProcess::FailedToStart)
                return;
              m_authSessionServer->close();
              helperFailed(QStringLiteral("Could not start the sign-in window."));
            });
    connect(m_authHelper, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
            [this](int exitCode, QProcess::ExitStatus exitStatus) {
              authDiagnostic(QStringLiteral("helper-exited status=%1 code=%2 crash=%3")
                                 .arg(m_status)
                                 .arg(exitCode)
                                 .arg(exitStatus == QProcess::CrashExit));
              m_authSessionServer->close();
              if (m_switchingAccount && !m_switchValidationRequestId) {
                // Closing the chooser keeps the account you already had.
                m_switchingAccount = false;
                emit statusChanged();
                return;
              }
              if (m_status == QStringLiteral("starting")) {
                setStatus(QStringLiteral("signed_out"));
                // A clean exit is still a failed login without a session.
                setErrorMessage(
                    QStringLiteral("The sign-in window closed without returning "
                                   "a session (%1, code %2). Please try again.")
                        .arg(exitStatus == QProcess::CrashExit ? QStringLiteral("crash")
                                                               : QStringLiteral("exit"))
                        .arg(exitCode));
              }
            });
  }
  if (switching) {
    m_switchingAccount = true;
    emit statusChanged();
  }
  if (!m_authSessionServer->start()) {
    helperFailed(QStringLiteral("Could not open the connection to the "
                                "sign-in window. Please try again."));
    return;
  }
  if (!switching) {
    // A late keychain read must not replace a login already in progress.
    ++m_profileGeneration;
    m_profileLoading = false;
    setStatus(QStringLiteral("starting"));
  }
  authDiagnostic(QStringLiteral("helper-launch switch=%1").arg(switching));
  QStringList arguments{QStringLiteral("--auth-socket"), m_authSessionServer->name()};
  if (switching)
    arguments.append(QStringLiteral("--switch-account"));
  m_authHelper->start(helperPath(), arguments);
}

// Same Google cookies, different chair: brand channels differ by page id, other
// Google accounts by session index.
QString AuthManager::accountIdentity() const {
  return QStringLiteral("%1\n%2\n%3")
      .arg(m_dataSyncId)
      .arg(m_accountIndex)
      .arg(parseCookieString(m_cookie).value(QStringLiteral("SAPISID")));
}

bool AuthManager::acceptSwitchedAccount(const QJsonObject &result) {
  QString cookie =
      normalizeYouTubeAuthCookie(result.value(QStringLiteral("cookie")).toString());
  if (!hasYouTubeLoginCookie(cookie)) {
    m_switchingAccount = false;
    emit statusChanged();
    setErrorMessage(QStringLiteral("The account chooser returned an invalid session."));
    return false;
  }
  const auto chosenCookies = parseCookieString(cookie);
  const auto currentCookies = parseCookieString(m_cookie);
  if (!chosenCookies.value(QStringLiteral("SAPISID")).isEmpty() &&
      chosenCookies.value(QStringLiteral("SAPISID")) ==
          currentCookies.value(QStringLiteral("SAPISID"))) {
    // Only the delegated page identity needs to change for this Google login.
    cookie = m_cookie;
    authDiagnostic(QStringLiteral("account-switch-reused-session"));
  } else if (!hasYouTubeSessionCookies(cookie)) {
    m_switchingAccount = false;
    emit statusChanged();
    setErrorMessage(QStringLiteral("That account needs a fresh sign-in. Sign out, then sign in with it."));
    return false;
  }
  // A chooser result is only a candidate until Music accepts its credentials.
  // Otherwise the old account would disappear behind a very decorative 401.
  m_pendingSwitch = result;
  m_pendingSwitch.insert(QStringLiteral("cookie"), cookie);
  QJsonObject candidateSession = sessionObject();
  candidateSession.insert(QStringLiteral("cookie"), cookie);
  candidateSession.insert(QStringLiteral("dataSyncId"), result.value(QStringLiteral("dataSyncId")));
  candidateSession.insert(QStringLiteral("accountIndex"), qMax(0, result.value(QStringLiteral("accountIndex")).toInt()));
  const QString visitorData = result.value(QStringLiteral("visitorData")).toString();
  if (!visitorData.isEmpty())
    candidateSession.insert(QStringLiteral("visitorData"), visitorData);
  const QString clientVersion = result.value(QStringLiteral("clientVersion")).toString();
  if (!clientVersion.isEmpty())
    candidateSession.insert(QStringLiteral("clientVersion"), clientVersion);
  m_switchValidationRequestId = m_youtubeProvider->invoke(
      QStringLiteral("catalog.playlists"),
      QJsonObject{{QStringLiteral("session"), candidateSession}});
  const quint64 requestId = m_switchValidationRequestId;
  QTimer::singleShot(20000, this, [this, requestId] {
    if (requestId && requestId == m_switchValidationRequestId)
      failSwitchedAccount(tr("Could not verify the selected YouTube channel. Please try again."));
  });
  return true;
}

void AuthManager::finishSwitchedAccount() {
  if (!m_switchValidationRequestId || m_pendingSwitch.isEmpty())
    return;
  const QJsonObject result = m_pendingSwitch;
  m_pendingSwitch = {};
  m_switchValidationRequestId = 0;
  m_switchingAccount = false;
  m_switchProfileRequestId = 0;
  authDiagnostic(QStringLiteral("account-switch-accepted"));
  const QString previousIdentity = accountIdentity();
  const QString previousCookie = m_cookie;
  m_cookie = result.value(QStringLiteral("cookie")).toString();
  m_dataSyncId = result.value(QStringLiteral("dataSyncId")).toString();
  m_accountIndex = qMax(0, result.value(QStringLiteral("accountIndex")).toInt());
  const QString visitorData = result.value(QStringLiteral("visitorData")).toString();
  if (!visitorData.isEmpty())
    m_visitorData = visitorData;
  // The chooser lives on www.youtube.com, whose client version is not YouTube Music's.
  const QString clientVersion = result.value(QStringLiteral("clientVersion")).toString();
  if (!clientVersion.isEmpty())
    m_clientVersion = clientVersion;
  const bool changed = accountIdentity() != previousIdentity;
  if (changed) {
    // Another person's name under the new avatar would be worse than none.
    m_userName = result.value(QStringLiteral("userName")).toString();
    m_userEmail = result.value(QStringLiteral("userEmail")).toString();
    m_userAvatar = result.value(QStringLiteral("userAvatar")).toString();
    m_userHandle = result.value(QStringLiteral("userHandle")).toString();
  }
  persistSecretSession();
  emit statusChanged();
  emit userChanged();
  if (changed || m_cookie != previousCookie)
    emit sessionChanged();
  if (changed)
    emit accountChanged();
  requestMissingProfile();
}

void AuthManager::failSwitchedAccount(const QString &message) {
  if (!m_switchValidationRequestId)
    return;
  authDiagnostic(QStringLiteral("account-switch-validation-failed"));
  m_pendingSwitch = {};
  m_switchValidationRequestId = 0;
  m_switchingAccount = false;
  emit statusChanged();
  setErrorMessage(message);
}

// Fills in a switched account's name and avatar without leaving signed_in.
void AuthManager::receiveSwitchedProfile(const QJsonObject &profile) {
  m_switchProfileRequestId = 0;
  const auto take = [&profile](QString &field, std::initializer_list<const char *> keys) {
    for (const char *key : keys) {
      const QString value = profile.value(QLatin1String(key)).toString().trimmed();
      if (!value.isEmpty()) {
        field = value;
        return;
      }
    }
  };
  if (m_userName.isEmpty()) take(m_userName, {"name"});
  if (m_userAvatar.isEmpty()) take(m_userAvatar, {"avatarUrl", "avatar", "thumbnail"});
  if (m_userHandle.isEmpty()) take(m_userHandle, {"handle"});
  if (!m_userHandle.isEmpty() && !m_userHandle.startsWith(QLatin1Char('@')))
    m_userHandle.prepend(QLatin1Char('@'));
  persistSecretSession();
  emit userChanged();
}

// Overlay wins per cookie name; order follows base, then new names.
QString AuthManager::mergeCookies(const QString &base, const QString &overlay) {
  QMap<QString, QString> merged = parseCookieString(base);
  const QMap<QString, QString> fresh = parseCookieString(overlay);
  for (auto it = fresh.cbegin(); it != fresh.cend(); ++it)
    merged.insert(it.key(), it.value());
  QStringList parts;
  for (auto it = merged.cbegin(); it != merged.cend(); ++it)
    parts.append(QStringLiteral("%1=%2").arg(it.key(), it.value()));
  return parts.join(QStringLiteral("; "));
}
