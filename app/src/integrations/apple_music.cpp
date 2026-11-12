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

#include "apple_music.h"

#include "account/orchard_account.h"
#include "auth/auth_session_channel.h"
#include "playback/native_apple_player.h"
#include "provider_host/provider_host.h"
#include "providers/runtime/provider_runtime.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QProcess>
#include <QSettings>
#include <qtkeychain/keychain.h>

namespace {
constexpr auto kKeychainService = "dev.sfg.orchard";
constexpr auto kKeychainEntry = "apple-music-session";
// Refetch the developer token a day before Apple would reject it.
constexpr qint64 kRefreshMarginSeconds = 24 * 60 * 60;

qint64 nowSeconds() { return QDateTime::currentSecsSinceEpoch(); }

QString helperPath() {
#ifdef Q_OS_WIN
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper.exe"));
#else
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper"));
#endif
}
} // namespace

AppleMusic::AppleMusic(OrchardAccount *account, QObject *parent, bool restore)
    : QObject(parent), m_account(account), m_network(this),
      m_runtime(new ProviderRuntime(orchard::provider::appleMusicBundle(), this)) {
  connect(m_runtime, &ProviderRuntime::invocationSucceeded, this,
          [this](quint64 id, const QJsonValue &result) { finishCall(id, result, {}); });
  connect(m_runtime, &ProviderRuntime::invocationFailed, this,
          [this](quint64 id, const QString &error) { finishCall(id, {}, error); });
  m_playHistoryEnabled = QSettings().value(QStringLiteral("appleMusic/playHistoryEnabled"), true).toBool();
  if (QSettings().value(QStringLiteral("appleMusic/losslessSource")).toString() == QLatin1String("qobuz"))
    m_losslessSource = QStringLiteral("qobuz");
  if (restore)
    restoreSession();
}

AppleMusic::~AppleMusic() {
  if (m_helper) {
    m_helper->disconnect(this);
    m_helper->kill();
    m_helper->waitForFinished(1000);
  }
}

bool AppleMusic::losslessAvailable() const { return connected() && NativeApplePlayer::available(); }

void AppleMusic::setLosslessSource(const QString &source) {
  if ((source != QLatin1String("apple") && source != QLatin1String("qobuz")) || source == m_losslessSource)
    return;
  m_losslessSource = source;
  QSettings().setValue(QStringLiteral("appleMusic/losslessSource"), source);
  emit changed();
}

bool AppleMusic::loginAvailable() const {
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
  return true;
#else
  return false;
#endif
}

void AppleMusic::setStatus(const QString &status) {
  if (m_status == status)
    return;
  m_status = status;
  emit changed();
}

void AppleMusic::setMessage(const QString &message, bool error) {
  if (m_message == message && m_messageIsError == error)
    return;
  m_message = message;
  m_messageIsError = error;
  emit changed();
}

void AppleMusic::restoreSession() {
  setStatus(QStringLiteral("restoring"));
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *base) {
    if (m_status != QStringLiteral("restoring"))
      return;
    auto *read = static_cast<QKeychain::ReadPasswordJob *>(base);
    const QJsonObject stored = QJsonDocument::fromJson(read->textData().toUtf8()).object();
    m_userToken = stored.value(QStringLiteral("userToken")).toString();
    m_developerToken = stored.value(QStringLiteral("developerToken")).toString();
    m_developerExpiresAt = stored.value(QStringLiteral("developerExpiresAt")).toInteger();
    if (read->error() != QKeychain::NoError && read->error() != QKeychain::EntryNotFound)
      qWarning().noquote() << "Could not read the Apple Music session from the OS keychain:"
                           << read->errorString();
    setStatus(m_userToken.isEmpty() ? QStringLiteral("disconnected") : QStringLiteral("connected"));
  });
  job->start();
}

void AppleMusic::persistSession() {
  auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  job->setTextData(QString::fromUtf8(QJsonDocument(QJsonObject{
      {QStringLiteral("userToken"), m_userToken},
      {QStringLiteral("developerToken"), m_developerToken},
      {QStringLiteral("developerExpiresAt"), m_developerExpiresAt},
  }).toJson(QJsonDocument::Compact)));
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *done) {
    if (done->error() != QKeychain::NoError && connected())
      setMessage(tr("Apple Music is connected until Orchard quits: %1").arg(done->errorString()), true);
  });
  job->start();
}

void AppleMusic::connectAccount() {
  if (!loginAvailable() || m_helper || m_status == QStringLiteral("restoring") || m_status == QStringLiteral("connecting"))
    return;
  const quint64 generation = ++m_loginGeneration;
  setStatus(QStringLiteral("connecting"));
  setMessage(tr("Getting Apple Music ready..."));
  withDeveloperToken([this, generation](const QString &token, const QString &error) {
    if (generation != m_loginGeneration) return;
    if (token.isEmpty()) {
      setStatus(m_userToken.isEmpty() ? QStringLiteral("disconnected") : QStringLiteral("connected"));
      setMessage(error, true);
      return;
    }
    setMessage(tr("Sign in to Apple Music in the window that opened."));
    const QByteArray input =
        QJsonDocument(QJsonObject{{QStringLiteral("developerToken"), token}}).toJson(QJsonDocument::Compact) + '\n';
    runHelper(QStringLiteral("--apple-music-login"), input, [this, generation](const QJsonObject &result) {
      if (generation != m_loginGeneration) return;
      const QString userToken = result.value(QStringLiteral("token")).toString();
      if (userToken.isEmpty()) {
        setStatus(m_userToken.isEmpty() ? QStringLiteral("disconnected") : QStringLiteral("connected"));
        const QString error = result.value(QStringLiteral("error")).toString();
        setMessage(error.isEmpty() ? tr("The Apple Music sign-in closed before it finished.") : error,
                   !error.isEmpty());
        return;
      }
      m_userToken = userToken;
      m_configuredFor.clear();
      persistSession();
      setStatus(QStringLiteral("connected"));
      setMessage(tr("Connected to Apple Music. Choose Apple under General, Home source for Apple Music recommendations."));
    });
  });
}

void AppleMusic::cancelConnection() {
  if (m_status != QStringLiteral("connecting")) return;
  ++m_loginGeneration;
  if (m_helper)
    m_helper->terminate();
  setStatus(m_userToken.isEmpty() ? QStringLiteral("disconnected") : QStringLiteral("connected"));
  setMessage(tr("Apple Music sign-in cancelled."));
}

void AppleMusic::disconnectAccount() {
  ++m_loginGeneration;
  m_userToken.clear();
  m_configuredFor.clear();
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  job->start();
  // Apple's cookies and MusicKit's stored token live in the helper's profile.
  if (loginAvailable())
    runHelper(QStringLiteral("--apple-music-logout"), {}, [](const QJsonObject &) {});
  setStatus(QStringLiteral("disconnected"));
  setMessage(tr("Disconnected from Apple Music."));
}

// One helper at a time; its answer arrives over the same private socket as YouTube sign-in.
void AppleMusic::runHelper(const QString &mode, const QByteArray &input,
                           std::function<void(const QJsonObject &)> done) {
  if (m_helper) {
    done({});
    return;
  }
  m_helperResult = {};
  m_helperDone = std::move(done);
  m_helperServer = new AuthSessionServer(
      [this](const QJsonObject &result) {
        m_helperResult = result;
        return true;
      },
      this);
  if (!m_helperServer->start()) {
    m_helperServer->deleteLater();
    m_helperServer = nullptr;
    std::exchange(m_helperDone, {})({});
    return;
  }
  m_helper = new QProcess(this);
  m_helper->setStandardOutputFile(QProcess::nullDevice());
  const auto finish = [this] {
    if (!m_helper)
      return;
    m_helper->deleteLater();
    m_helper = nullptr;
    m_helperServer->close();
    m_helperServer->deleteLater();
    m_helperServer = nullptr;
    if (auto callback = std::exchange(m_helperDone, {}))
      callback(std::exchange(m_helperResult, {}));
  };
  connect(m_helper, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), this, finish);
  connect(m_helper, &QProcess::errorOccurred, this, [finish](QProcess::ProcessError error) {
    if (error == QProcess::FailedToStart)
      finish();
  });
  m_helper->start(helperPath(), {QStringLiteral("--auth-socket"), m_helperServer->name(), mode});
  if (!input.isEmpty())
    m_helper->write(input);
  m_helper->closeWriteChannel();
}

void AppleMusic::withDeveloperToken(TokenReply done) {
  if (!m_developerToken.isEmpty() && m_developerExpiresAt > nowSeconds() + kRefreshMarginSeconds) {
    done(m_developerToken, {});
    return;
  }
  m_tokenWaiters.append(std::move(done));
  if (m_tokenWaiters.size() > 1)
    return;
  requestToken(QStringLiteral("/apple/developer-token"), m_tokenWaiters, [this](const QString &token, qint64 expiresAt) {
    m_developerToken = token;
    m_developerExpiresAt = expiresAt;
    if (!m_userToken.isEmpty())
      persistSession();
  });
}

void AppleMusic::withNativeDeveloperToken(std::function<void(const QString &, const QString &)> done) {
  if (!m_nativeToken.isEmpty() && m_nativeExpiresAt > nowSeconds() + kRefreshMarginSeconds) {
    done(m_nativeToken, {});
    return;
  }
  m_nativeTokenWaiters.append(std::move(done));
  if (m_nativeTokenWaiters.size() > 1)
    return;
  // The Worker's origin-free token, the one ShazamKit and Android's catalog client use.
  requestToken(QStringLiteral("/apple/shazam-token"), m_nativeTokenWaiters, [this](const QString &token, qint64 expiresAt) {
    m_nativeToken = token;
    m_nativeExpiresAt = expiresAt;
  });
}

void AppleMusic::requestToken(const QString &path, QList<TokenReply> &waiters,
                              std::function<void(const QString &, qint64)> store) {
  const auto finish = [&waiters](const QString &token, const QString &error) {
    for (const auto &waiter : std::exchange(waiters, {}))
      waiter(token, error);
  };
  m_account->withAccessToken([this, path, finish, store](const QString &accessToken) {
    if (accessToken.isEmpty()) {
      finish({}, tr("Sign in to your Orchard account to use Apple Music."));
      return;
    }
    QNetworkRequest request(m_account->serviceUrl().resolved(QUrl(path)));
    request.setRawHeader("Authorization", "Bearer " + accessToken.toUtf8());
    request.setTransferTimeout(15000);
    auto *reply = m_network.get(request);
    connect(reply, &QNetworkReply::finished, this, [reply, finish, store] {
      reply->deleteLater();
      const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
      const QJsonObject body = QJsonDocument::fromJson(reply->readAll()).object();
      const QString token = body.value(QStringLiteral("developer_token")).toString();
      const qint64 expiresAt = body.value(QStringLiteral("expires_at")).toInteger();
      if (status == 200 && !token.isEmpty() && expiresAt > nowSeconds() + 60) {
        store(token, expiresAt);
        finish(token, {});
        return;
      }
      QString error = tr("Could not reach Apple Music. Check your connection and try again.");
      if (status == 401)
        error = tr("Sign in to your Orchard account again.");
      else if (status == 429)
        error = body.value(QStringLiteral("error")).toString() == QStringLiteral("apple_daily_limit")
                    ? tr("Apple Music's daily limit for this account was reached. Try again tomorrow.")
                    : tr("Too many attempts. Try again in a minute.");
      else if (status == 503)
        error = tr("Apple Music is not available right now. Try again later.");
      finish({}, error);
    });
  });
}

void AppleMusic::forgetDeveloperToken() {
  m_developerToken.clear();
  m_developerExpiresAt = 0;
  m_configuredFor.clear();
}
