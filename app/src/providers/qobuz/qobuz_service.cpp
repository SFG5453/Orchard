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

#include "qobuz_service.h"
#include "qobuz_oauth_callback.h"
#include "providers/runtime/provider_runtime.h"
#include "provider_host/provider_host.h"

#include <QDebug>
#include <QDesktopServices>
#include <QJsonArray>
#include <QJsonDocument>
#include <QUrl>
#include <qtkeychain/keychain.h>

namespace {
// MAX asks for the best master the plan allows, up to 24-bit/192 kHz.
const QString kQuality = QStringLiteral("auto");
constexpr auto kKeychainService = "dev.sfg.orchard";
constexpr auto kKeychainEntry = "qobuz-session";

// Provider failures can carry a QuickJS stack; listeners only need the first line.
QString shortError(const QString &error) {
  return error.section(QLatin1Char('\n'), 0, 0).left(240);
}

QJsonObject trackPayload(const QVariantMap &track) {
  QJsonArray artists;
  for (const QVariant &artist : track.value(QStringLiteral("artists")).toList()) {
    const QString name = artist.typeId() == QMetaType::QVariantMap
                             ? artist.toMap().value(QStringLiteral("name")).toString()
                             : artist.toString();
    if (!name.trimmed().isEmpty())
      artists.append(name.trimmed());
  }
  return {{QStringLiteral("title"), track.value(QStringLiteral("title")).toString()},
          {QStringLiteral("artist"), track.value(QStringLiteral("artist")).toString()},
          {QStringLiteral("artists"), artists},
          {QStringLiteral("album"), track.value(QStringLiteral("album")).toString()},
          {QStringLiteral("durationSeconds"), track.value(QStringLiteral("durationSeconds")).toDouble()},
          {QStringLiteral("explicit"), track.value(QStringLiteral("explicit")).toBool()},
          {QStringLiteral("isrc"), track.value(QStringLiteral("isrc")).toString()}};
}
} // namespace

QobuzService::QobuzService(QObject *parent, bool restore)
    : QObject(parent),
      m_runtime(new ProviderRuntime(orchard::provider::qobuzBundle(), this)),
      m_callback(new QobuzOAuthCallback(this)) {
  connect(m_runtime, &ProviderRuntime::invocationSucceeded, this,
          [this](quint64 id, const QJsonValue &result) {
            if (const Reply done = m_replies.take(id))
              done(result, {});
            else if (const Bytes read = m_reads.take(id))
              read({}, tr("Qobuz returned no audio for this range."));
          });
  connect(m_runtime, &ProviderRuntime::invocationBytes, this,
          [this](quint64 id, const QByteArray &bytes) {
            if (const Bytes read = m_reads.take(id))
              read(bytes, {});
            else if (const Reply done = m_replies.take(id))
              done(QJsonValue(), tr("Qobuz returned audio where data was expected."));
          });
  connect(m_runtime, &ProviderRuntime::invocationFailed, this,
          [this](quint64 id, const QString &message) {
            const QString error = shortError(message);
            if (const Reply done = m_replies.take(id))
              done(QJsonValue(), error);
            else if (const Bytes read = m_reads.take(id))
              read({}, error);
          });
  connect(m_callback, &QobuzOAuthCallback::codeReceived, this, &QobuzService::finishConnection);
  connect(m_callback, &QobuzOAuthCallback::timedOut, this, [this] {
    if (m_status != QStringLiteral("connecting"))
      return;
    ++m_connectGeneration;
    setStatus(QStringLiteral("disconnected"));
    setMessage(tr("Qobuz sign-in timed out. Try again."), true);
  });
  if (restore)
    restoreSession();
}

QobuzService::~QobuzService() = default;

quint64 QobuzService::call(const QString &method, const QJsonValue &payload, Reply done) {
  const quint64 id = m_runtime->invoke(method, payload);
  if (done)
    m_replies.insert(id, std::move(done));
  return id;
}

void QobuzService::setEnabled(bool enabled) {
  if (m_enabled == enabled)
    return;
  m_enabled = enabled;
  emit changed();
}

void QobuzService::setStatus(const QString &status) {
  if (m_status == status)
    return;
  m_status = status;
  emit changed();
}

void QobuzService::setMessage(const QString &message, bool error) {
  if (m_message == message && m_messageIsError == error)
    return;
  m_message = message;
  m_messageIsError = error;
  emit changed();
}

void QobuzService::connectAccount() {
  if (m_status == QStringLiteral("connecting") || m_status == QStringLiteral("restoring"))
    return;
  const QUrl redirect = m_callback->listen();
  if (redirect.isEmpty()) {
    setMessage(tr("Could not open a local port for the Qobuz sign-in."), true);
    return;
  }
  const quint64 generation = ++m_connectGeneration;
  setStatus(QStringLiteral("connecting"));
  setMessage(tr("Opening Qobuz in your browser..."));
  call(QStringLiteral("oauth.start"), QJsonObject{{QStringLiteral("redirectUrl"), redirect.toString()}},
       [this, generation](const QJsonValue &result, const QString &error) {
         if (generation != m_connectGeneration)
           return;
         const QUrl url(result.toObject().value(QStringLiteral("url")).toString());
         if (!error.isEmpty() || url.scheme() != QStringLiteral("https") ||
             !QDesktopServices::openUrl(url)) {
           m_callback->close();
           setStatus(QStringLiteral("disconnected"));
           setMessage(error.isEmpty() ? tr("Could not open the Qobuz sign-in page.")
                                      : tr("Qobuz sign-in is unavailable: %1").arg(error),
                      true);
           return;
         }
         setMessage(tr("Sign in to Qobuz in your browser, then come back here."));
       });
}

void QobuzService::finishConnection(const QString &code) {
  if (m_status != QStringLiteral("connecting"))
    return;
  const quint64 generation = m_connectGeneration;
  setMessage(tr("Finishing the Qobuz connection..."));
  call(QStringLiteral("oauth.finish"), QJsonObject{{QStringLiteral("code"), code}},
       [this, generation](const QJsonValue &result, const QString &error) {
         if (generation != m_connectGeneration)
           return;
         const QJsonObject account = result.toObject();
         const QString token = account.value(QStringLiteral("token")).toString();
         const qint64 userId = static_cast<qint64>(account.value(QStringLiteral("userId")).toDouble());
         if (!error.isEmpty() || token.isEmpty() || userId <= 0) {
           setStatus(QStringLiteral("disconnected"));
           setMessage(error.isEmpty() ? tr("Qobuz did not return an account.")
                                      : tr("Qobuz sign-in failed: %1").arg(error),
                      true);
           return;
         }
         storeSession(token, userId);
         applySession(token, userId);
         setStatus(QStringLiteral("connected"));
         setMessage(tr("Qobuz is connected, and streaming quality is now MAX."));
         emit accountConnected();
       });
}

void QobuzService::cancelConnection() {
  if (m_status != QStringLiteral("connecting"))
    return;
  ++m_connectGeneration;
  m_callback->close();
  setStatus(QStringLiteral("disconnected"));
  setMessage({});
}

void QobuzService::disconnectAccount() {
  ++m_connectGeneration;
  m_callback->close();
  call(QStringLiteral("close"), QJsonValue());
  forgetSession();
  m_albumQuality.clear();
  setStatus(QStringLiteral("disconnected"));
  setMessage(tr("Qobuz is disconnected and its sign-in was removed from this device."));
  emit accountDisconnected();
}

void QobuzService::applySession(const QString &token, qint64 userId) {
  m_albumQuality.clear();
  call(QStringLiteral("session.set"),
       QJsonObject{{QStringLiteral("token"), token}, {QStringLiteral("userId"), userId}});
}

void QobuzService::restoreSession() {
  setStatus(QStringLiteral("restoring"));
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *base) {
    if (m_status != QStringLiteral("restoring"))
      return;
    auto *read = static_cast<QKeychain::ReadPasswordJob *>(base);
    const QJsonObject saved = QJsonDocument::fromJson(read->textData().toUtf8()).object();
    const QString token = saved.value(QStringLiteral("token")).toString();
    const qint64 userId = static_cast<qint64>(saved.value(QStringLiteral("userId")).toDouble());
    if (read->error() == QKeychain::NoError && !token.isEmpty() && userId > 0) {
      applySession(token, userId);
      setStatus(QStringLiteral("connected"));
      return;
    }
    if (read->error() != QKeychain::NoError && read->error() != QKeychain::EntryNotFound)
      qWarning().noquote() << "Could not read the Qobuz session from the OS keychain:"
                           << read->errorString();
    setStatus(QStringLiteral("disconnected"));
  });
  job->start();
}

void QobuzService::storeSession(const QString &token, qint64 userId) {
  auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  job->setTextData(QString::fromUtf8(
      QJsonDocument(QJsonObject{{QStringLiteral("token"), token}, {QStringLiteral("userId"), userId}})
          .toJson(QJsonDocument::Compact)));
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *done) {
    // Without a keychain the connection still works until Orchard quits.
    if (done->error() != QKeychain::NoError)
      setMessage(tr("Qobuz is connected for this session only: %1").arg(done->errorString()), true);
  });
  job->start();
}

void QobuzService::forgetSession() {
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(QString::fromLatin1(kKeychainEntry));
  job->setInsecureFallback(false);
  job->start();
}

void QobuzService::resolveTrack(const QVariantMap &track, Reply done, bool forPeer) {
  if (!(forPeer ? connected() : active())) {
    done(QJsonValue(), {});
    return;
  }
  call(QStringLiteral("playback.resolve"),
       QJsonObject{{QStringLiteral("track"), trackPayload(track)}, {QStringLiteral("quality"), kQuality}},
       [this, done = std::move(done)](const QJsonValue &result, const QString &error) {
         if (!error.isEmpty())
           setMessage(tr("Qobuz playback fell back to YouTube: %1").arg(error), true);
         // Every fallback states its reason; silent misses are hard to tell from working playback.
         const QJsonObject miss = result.toObject().value(QStringLiteral("miss")).toObject();
         if (!error.isEmpty())
           qWarning().noquote() << "Qobuz resolve failed:" << error;
         else if (!miss.isEmpty())
           qWarning().noquote() << "Qobuz miss:" << QJsonDocument(miss).toJson(QJsonDocument::Compact);
         else
           qInfo().noquote() << "Qobuz matched:" << result.toObject().value(QStringLiteral("match"))
                                                        .toObject().value(QStringLiteral("title")).toString();
         done(result, error);
       });
}

void QobuzService::readRange(const QString &playbackId, qint64 start, qint64 end, Bytes done) {
  const quint64 id = m_runtime->invoke(
      QStringLiteral("playback.read"),
      QJsonObject{{QStringLiteral("playbackId"), playbackId},
                  {QStringLiteral("start"), static_cast<double>(start)},
                  {QStringLiteral("end"), static_cast<double>(end)}});
  m_reads.insert(id, std::move(done));
}

void QobuzService::playbackStarted(const QString &playbackId, double position) {
  call(QStringLiteral("playback.started"),
       QJsonObject{{QStringLiteral("playbackId"), playbackId}, {QStringLiteral("position"), position}});
}

void QobuzService::playbackEnded(const QString &playbackId, double position) {
  call(QStringLiteral("playback.ended"),
       QJsonObject{{QStringLiteral("playbackId"), playbackId}, {QStringLiteral("position"), position}});
}

void QobuzService::albumQuality(const QVariantMap &album, Reply done) {
  const QString title = album.value(QStringLiteral("title")).toString().trimmed();
  const QString artist = album.value(QStringLiteral("artist")).toString().trimmed();
  if (!active() || title.isEmpty() || artist.isEmpty()) {
    done(QJsonValue(), {});
    return;
  }
  const QString key = title.toLower() + QLatin1Char('\n') + artist.toLower();
  if (const auto cached = m_albumQuality.constFind(key); cached != m_albumQuality.cend()) {
    done(*cached, {});
    return;
  }
  call(QStringLiteral("album.quality"),
       QJsonObject{{QStringLiteral("title"), title},
                   {QStringLiteral("artist"), artist},
                   {QStringLiteral("year"), album.value(QStringLiteral("year")).toString().left(4).toInt()},
                   {QStringLiteral("trackCount"), album.value(QStringLiteral("trackCount")).toInt()},
                   {QStringLiteral("quality"), kQuality}},
       [this, key, done = std::move(done)](const QJsonValue &result, const QString &error) {
         if (error.isEmpty()) {
           if (m_albumQuality.size() >= 200)
             m_albumQuality.clear();
           m_albumQuality.insert(key, result);
         }
         done(result, error);
       });
}
