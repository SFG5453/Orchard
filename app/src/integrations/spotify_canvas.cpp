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

#include "spotify_canvas.h"
#include "auth/auth_session_channel.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QJsonDocument>
#include <QProcess>
#include <QRegularExpression>
#include <qtkeychain/keychain.h>
#include <utility>

namespace {
constexpr auto kKeychainService = "dev.sfg.orchard";
constexpr auto kKeychainEntry = "spotify-session";
constexpr qint64 kTokenRetryMs = 10 * 60 * 1000;

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

QString helperPath() {
#ifdef Q_OS_WIN
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper.exe"));
#else
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper"));
#endif
}
} // namespace

SpotifyCanvas::SpotifyCanvas(QObject *parent, bool restore) : QObject(parent), m_network(this) {
  if (restore)
    restoreSession();
}

SpotifyCanvas::~SpotifyCanvas() {
  if (m_helper) {
    m_helper->disconnect(this);
    m_helper->kill();
    m_helper->waitForFinished(1000);
  }
}

bool SpotifyCanvas::loginAvailable() const {
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
  return true;
#else
  return false;
#endif
}

QString SpotifyCanvas::extractSpdc(const QString &input) {
  const QString text = input.trimmed();
  if (!text.contains(QLatin1Char('=')))
    return text;
  static const QRegularExpression cookie(QStringLiteral("(?:^|;\\s*)sp_dc=([^;]+)"));
  return cookie.match(text).captured(1).trimmed();
}

void SpotifyCanvas::setStatus(const QString &status) {
  if (m_status == status)
    return;
  m_status = status;
  emit changed();
}

void SpotifyCanvas::setMessage(const QString &message, bool error) {
  if (m_message == message && m_messageIsError == error)
    return;
  m_message = message;
  m_messageIsError = error;
  emit changed();
}

void SpotifyCanvas::restoreSession() {
  setStatus(QStringLiteral("restoring"));
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *base) {
    if (m_status != QStringLiteral("restoring"))
      return;
    auto *read = static_cast<QKeychain::ReadPasswordJob *>(base);
    const QString spdc = QJsonDocument::fromJson(read->textData().toUtf8())
                             .object()
                             .value(QStringLiteral("spdc"))
                             .toString();
    if (read->error() == QKeychain::NoError && !spdc.isEmpty()) {
      applyCookie(spdc, false);
      return;
    }
    if (read->error() != QKeychain::NoError && read->error() != QKeychain::EntryNotFound)
      qWarning().noquote() << "Could not read the Spotify session from the OS keychain:"
                           << read->errorString();
    setStatus(QStringLiteral("disconnected"));
  });
  job->start();
}

void SpotifyCanvas::applyCookie(const QString &spdc, bool persist) {
  m_spdc = spdc;
  m_accessToken.clear();
  m_accessTokenExpiresMs = 0;
  m_tokenRetryAfterMs = 0;
  m_canvases.clear();
  if (persist) {
    auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(kKeychainService), this);
    job->setKey(QString::fromLatin1(kKeychainEntry));
    job->setInsecureFallback(false);
    job->setTextData(QString::fromUtf8(
        QJsonDocument(QJsonObject{{QStringLiteral("spdc"), spdc}}).toJson(QJsonDocument::Compact)));
    connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *done) {
      if (done->error() != QKeychain::NoError)
        setMessage(tr("Spotify is connected until Orchard quits: %1").arg(done->errorString()), true);
    });
    job->start();
  }
  setStatus(QStringLiteral("connected"));
  emit accountConnected();
}

bool SpotifyCanvas::saveCookie(const QString &input) {
  const QString spdc = extractSpdc(input);
  if (spdc.isEmpty() || spdc.contains(QRegularExpression(QStringLiteral("[\\s;,\"]")))) {
    setMessage(tr("That doesn't look like an sp_dc cookie."), true);
    return false;
  }
  applyCookie(spdc, true);
  setMessage(tr("Spotify cookie saved. Canvas loops will appear when other mirrors have none."));
  return true;
}

void SpotifyCanvas::connectAccount() {
  if (!loginAvailable() || m_helper || m_status == QStringLiteral("restoring"))
    return;
  setStatus(QStringLiteral("connecting"));
  setMessage(tr("Log in to Spotify in the window that opened."));
  runHelper(QStringLiteral("--spotify-login"), {}, [this](const QJsonObject &result) {
    const QString spdc = extractSpdc(result.value(QStringLiteral("spdc")).toString());
    if (spdc.isEmpty()) {
      setStatus(m_spdc.isEmpty() ? QStringLiteral("disconnected") : QStringLiteral("connected"));
      setMessage(tr("The Spotify login closed before it finished."));
      return;
    }
    applyCookie(spdc, true);
    setMessage(tr("Connected to Spotify. Canvas loops will appear when other mirrors have none."));
  });
}

void SpotifyCanvas::cancelConnection() {
  if (m_helper && m_status == QStringLiteral("connecting"))
    m_helper->terminate();
}

void SpotifyCanvas::disconnectAccount() {
  m_spdc.clear();
  m_accessToken.clear();
  m_clientToken.clear();
  m_canvases.clear();
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  job->start();
  setStatus(QStringLiteral("disconnected"));
  setMessage(tr("Disconnected from Spotify."));
}

// One helper at a time; its answer arrives over the same private socket as YouTube sign-in.
void SpotifyCanvas::runHelper(const QString &mode, const QByteArray &input,
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
  if (!input.isEmpty()) {
    m_helper->write(input);
    m_helper->closeWriteChannel();
  }
}

void SpotifyCanvas::withAccessToken(TokenReply done) {
  if (!m_accessToken.isEmpty() && m_accessTokenExpiresMs > nowMs() + 60000) {
    done(m_accessToken);
    return;
  }
  if (m_spdc.isEmpty() || nowMs() < m_tokenRetryAfterMs || !loginAvailable()) {
    done({});
    return;
  }
  m_tokenWaiters.append(std::move(done));
  if (m_tokenWaiters.size() > 1)
    return;
  const QByteArray input =
      QJsonDocument(QJsonObject{{QStringLiteral("spdc"), m_spdc}}).toJson(QJsonDocument::Compact) + '\n';
  runHelper(QStringLiteral("--spotify-token"), input, [this](const QJsonObject &result) {
    m_accessToken = result.value(QStringLiteral("accessToken")).toString();
    const qint64 expires = static_cast<qint64>(result.value(QStringLiteral("expiresAt")).toDouble());
    m_accessTokenExpiresMs = expires > nowMs() ? expires : nowMs() + 3600 * 1000;
    if (m_accessToken.isEmpty()) {
      m_tokenRetryAfterMs = nowMs() + kTokenRetryMs;
      qWarning().noquote() << "Spotify canvas: the web player gave no access token";
    }
    for (const TokenReply &waiter : std::exchange(m_tokenWaiters, {}))
      waiter(m_accessToken);
  });
}

void SpotifyCanvas::tokenRejected() {
  m_accessToken.clear();
  m_accessTokenExpiresMs = 0;
}

void SpotifyCanvas::canvasFor(const QString &title, const QString &artist, CanvasReply done) {
  if (!connected() || title.trimmed().isEmpty()) {
    done({}, false);
    return;
  }
  const QString key = title.trimmed().toLower() + QStringLiteral("::") + artist.trimmed().toLower();
  if (const auto cached = m_canvases.constFind(key); cached != m_canvases.cend()) {
    done(*cached, false);
    return;
  }
  withAccessToken([this, key, title, artist, done](const QString &accessToken) {
    if (accessToken.isEmpty()) {
      done({}, true);
      return;
    }
    withClientToken([this, key, title, artist, accessToken, done](const QString &clientToken) {
      searchTrack(accessToken, clientToken, title, artist,
                  [this, key, accessToken, clientToken, done](const QString &trackId, bool failed) {
        if (trackId.isEmpty()) {
          if (!failed)
            m_canvases.insert(key, QString());
          done({}, failed);
          return;
        }
        fetchCanvas(trackId, accessToken, clientToken,
                    [this, key, done](const QString &url, bool failed) {
          if (!failed) {
            if (m_canvases.size() >= 2000)
              m_canvases.clear();
            m_canvases.insert(key, url);
          }
          done(url, failed);
        });
      });
    });
  });
}
