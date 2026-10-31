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

#include "auth/youtube_session_keeper.h"

#include "auth/auth_diagnostics.h"
#include "auth/auth_manager.h"
#include "auth/auth_session_channel.h"
#include "providers/youtube/catalog/youtube_catalog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QJsonDocument>
#include <QProcess>
#include <QSettings>

#include <utility>

namespace {

constexpr auto kRenewedAtKey = "heartkeeper/youtubeSessionRenewedAt";
constexpr qint64 kPeriodMs = 6LL * 60 * 60 * 1000;
// A rejected session retries at most this often; a dead profile should not spin the helper.
constexpr qint64 kMinGapMs = 10LL * 60 * 1000;
constexpr int kStartupDelayMs = 90 * 1000;
constexpr auto kRejected = "did not accept the saved login session";

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

QString helperPath() {
#ifdef Q_OS_WIN
  const QString name = QStringLiteral("orchard-auth-helper.exe");
#else
  const QString name = QStringLiteral("orchard-auth-helper");
#endif
  return QDir(QCoreApplication::applicationDirPath()).filePath(name);
}

} // namespace

YouTubeSessionKeeper::YouTubeSessionKeeper(AuthManager *auth, YouTubeCatalog *catalog, QObject *parent)
    : QObject(parent), m_auth(auth) {
#ifdef Q_OS_WIN
  if (catalog) {
    connect(catalog, &YouTubeCatalog::requestFailed, this, [this](quint64, const QString &message) {
      if (message.contains(QLatin1String(kRejected)))
        renew(QStringLiteral("rejected"));
    });
  }
  m_periodic.setInterval(static_cast<int>(kPeriodMs));
  connect(&m_periodic, &QTimer::timeout, this, [this] { renew(QStringLiteral("periodic")); });
  m_periodic.start();
  QTimer::singleShot(kStartupDelayMs, this, [this] {
    const qint64 renewedAt = QSettings().value(QLatin1String(kRenewedAtKey), 0).toLongLong();
    if (nowMs() - renewedAt > kPeriodMs)
      renew(QStringLiteral("startup"));
  });
#else
  Q_UNUSED(catalog);
#endif
}

void YouTubeSessionKeeper::renew(const QString &reason) {
  if (!m_auth || !m_auth->isSignedIn() || m_helper)
    return;
  if (m_lastAttemptMs > 0 && nowMs() - m_lastAttemptMs < kMinGapMs)
    return;
  m_lastAttemptMs = nowMs();
  m_reason = reason;
  m_result = {};
  m_server = new AuthSessionServer(
      [this](const QJsonObject &result) {
        m_result = result;
        return true;
      },
      this);
  if (!m_server->start()) {
    m_server->deleteLater();
    m_server = nullptr;
    return;
  }
  authDiagnostic(QStringLiteral("renewal-start reason=%1").arg(reason));
  m_helper = new QProcess(this);
  m_helper->setStandardOutputFile(QProcess::nullDevice());
  connect(m_helper, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this,
          [this](int exitCode, QProcess::ExitStatus status) { finish(status == QProcess::CrashExit ? 3 : exitCode); });
  connect(m_helper, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
    if (error == QProcess::FailedToStart)
      finish(3);
  });
  m_helper->start(helperPath(), {QStringLiteral("--auth-socket"), m_server->name(), QStringLiteral("--youtube-refresh")});
  const QJsonObject request{{QStringLiteral("accountIndex"), m_auth->sessionObject().value(QStringLiteral("accountIndex"))}};
  m_helper->write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
  m_helper->closeWriteChannel();
}

void YouTubeSessionKeeper::finish(int exitCode) {
  if (!m_helper)
    return;
  m_helper->deleteLater();
  m_helper = nullptr;
  if (m_server) {
    m_server->close();
    m_server->deleteLater();
    m_server = nullptr;
  }
  const QJsonObject result = std::exchange(m_result, {});
  if (exitCode != 0 || result.isEmpty()) {
    // 2: the browser profile is signed out too, so only a new sign-in helps.
    authDiagnostic(QStringLiteral("renewal-failed reason=%1 code=%2").arg(m_reason).arg(exitCode));
    return;
  }
  if (m_auth && m_auth->absorbRenewedSession(result))
    QSettings().setValue(QLatin1String(kRenewedAtKey), nowMs());
}

// AuthManager's side of renewal, kept here: auth_manager.cpp is already past the file size limit.
bool AuthManager::absorbRenewedSession(const QJsonObject &session) {
  if (m_embeddedWebAuth || !isSignedIn())
    return false;
  const QString cookie = normalizeYouTubeAuthCookie(session.value(QStringLiteral("cookie")).toString());
  if (!hasYouTubeLoginCookie(cookie))
    return false;
  const QString freshSapisid = parseCookieString(cookie).value(QStringLiteral("SAPISID"));
  const QString freshDelegated = delegatedSessionIdFromPageAuth(session.value(QStringLiteral("dataSyncId")).toString(),
                                                                session.value(QStringLiteral("delegatedSessionId")).toString());
  // Same Google login and same channel, or nothing: never swap identities behind the user's back.
  if (freshSapisid.isEmpty() || freshSapisid != parseCookieString(m_cookie).value(QStringLiteral("SAPISID")) ||
      freshDelegated != m_dataSyncId) {
    authDiagnostic(QStringLiteral("renewal-refused other-account"));
    return false;
  }
  if (cookie == m_cookie) {
    authDiagnostic(QStringLiteral("renewal-unchanged"));
    return true;
  }
  m_cookie = cookie;
  const QString visitorData = session.value(QStringLiteral("visitorData")).toString();
  if (!visitorData.isEmpty())
    m_visitorData = visitorData;
  const QString clientVersion = session.value(QStringLiteral("clientVersion")).toString();
  if (!clientVersion.isEmpty())
    m_clientVersion = clientVersion;
  persistSecretSession();
  authDiagnostic(QStringLiteral("renewal-accepted"));
  emit sessionChanged();
  return true;
}
