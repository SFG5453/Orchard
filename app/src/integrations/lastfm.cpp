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

#include "lastfm.h"

#include <QDateTime>
#include <QDesktopServices>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QStringList>
#include <QtGlobal>

#include <qtkeychain/keychain.h>

#include <utility>

namespace {
constexpr auto keychainService = "dev.sfg.orchard";
constexpr auto keychainEntry = "lastfm-session";
constexpr auto defaultServiceUrl = "https://lastfm.sfg545.dev";
constexpr auto settingsGroup = "integrations/lastfm";
// The worker maps this to the Orchard V3 user agent it sends Last.fm.
constexpr auto userAgent = "OrchardDesktop/3.0";
constexpr int requestTimeoutMs = 15000;
// Last.fm drops unapproved auth tokens after about an hour; ours give up sooner.
constexpr qint64 pendingLifetimeMs = 10 * 60 * 1000;
constexpr qint64 scrobbleRetryMs = 30 * 1000;
constexpr double minimumTrackSeconds = 30.0;
constexpr double maximumScrobbleWaitSeconds = 4 * 60.0;

QString cleanText(const QVariant &value) {
  return value.toString().simplified();
}

QString describeFailure(int status, const QJsonObject &body) {
  const QString error = body.value(QStringLiteral("error")).toString().trimmed();
  if (!error.isEmpty())
    return error;
  if (status == 0)
    return QObject::tr("Could not reach Last.fm.");
  return QObject::tr("Last.fm returned HTTP %1.").arg(status);
}
} // namespace

Lastfm::Lastfm(QObject *parent) : Lastfm(Options{}, parent) {}

Lastfm::Lastfm(Options options, QObject *parent)
    : QObject(parent),
      m_serviceUrl(std::move(options.serviceUrl)),
      m_useKeychain(options.useKeychain),
      m_useSettings(options.useSettings),
      m_openBrowser(std::move(options.openBrowser)),
      m_clock(std::move(options.clock)) {
  if (m_serviceUrl.isEmpty()) {
    const QString overrideUrl = qEnvironmentVariable("ORCHARD_LASTFM_URL");
    m_serviceUrl = QUrl(overrideUrl.isEmpty() ? QString::fromLatin1(defaultServiceUrl) : overrideUrl);
  }
  if (!m_openBrowser)
    m_openBrowser = [](const QUrl &url) { QDesktopServices::openUrl(url); };
  if (!m_clock)
    m_clock = [] { return QDateTime::currentMSecsSinceEpoch(); };

  if (m_useSettings) {
    QSettings settings;
    settings.beginGroup(QString::fromLatin1(settingsGroup));
    m_enabled = settings.value(QStringLiteral("enabled"), true).toBool();
    settings.endGroup();
  }

  if (m_useKeychain)
    restoreSession();
}

bool Lastfm::shouldScrobble(double duration, double playedSeconds) {
  if (!(duration > minimumTrackSeconds))
    return false;
  return playedSeconds >= qMin(duration / 2.0, maximumScrobbleWaitSeconds);
}

QJsonObject Lastfm::trackPayload(const QVariantMap &track, double duration) {
  const QString title = cleanText(track.value(QStringLiteral("title")));
  QString artist = cleanText(track.value(QStringLiteral("artist")));
  if (artist.isEmpty())
    artist = cleanText(track.value(QStringLiteral("artists")).toList().value(0));
  if (title.isEmpty() || artist.isEmpty())
    return {};

  const double known = duration > 0.0 ? duration : track.value(QStringLiteral("durationSeconds")).toDouble();
  QJsonObject payload{
      {QStringLiteral("title"), title},
      {QStringLiteral("artist"), artist},
      {QStringLiteral("album"), cleanText(track.value(QStringLiteral("album")))},
      {QStringLiteral("albumArtist"), cleanText(track.value(QStringLiteral("albumArtist")))},
      {QStringLiteral("duration"), qIsFinite(known) && known > 0.0 ? qRound(known) : 0},
  };
  return payload;
}

void Lastfm::setEnabled(bool enabled) {
  if (m_enabled == enabled)
    return;
  m_enabled = enabled;
  if (m_useSettings) {
    QSettings settings;
    settings.beginGroup(QString::fromLatin1(settingsGroup));
    settings.setValue(QStringLiteral("enabled"), enabled);
    settings.endGroup();
  }
  emit enabledChanged();
  m_play.reset();
  trackPlayback();
}

void Lastfm::setStatus(const QString &status) {
  if (m_status == status)
    return;
  m_status = status;
  emit statusChanged();
}

void Lastfm::setMessage(const QString &message, bool error) {
  if (m_message == message && m_messageIsError == error)
    return;
  m_message = message;
  m_messageIsError = error;
  emit messageChanged();
}

void Lastfm::post(const QString &path, const QJsonObject &body, ReplyHandler handler) {
  QNetworkRequest request(m_serviceUrl.resolved(QUrl(path)));
  request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
  request.setHeader(QNetworkRequest::UserAgentHeader, QString::fromLatin1(userAgent));
  request.setRawHeader("Accept", "application/json");
  request.setTransferTimeout(requestTimeoutMs);
  QNetworkReply *reply = m_network.post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));
  connect(reply, &QNetworkReply::finished, this, [reply, handler = std::move(handler)] {
    reply->deleteLater();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    handler(status, QJsonDocument::fromJson(reply->readAll()).object());
  });
}

void Lastfm::connectAccount() {
  if (m_status != QStringLiteral("disconnected") && m_status != QStringLiteral("pending"))
    return;
  const quint64 generation = ++m_generation;
  m_pendingToken.clear();
  setMessage(QString());
  setStatus(QStringLiteral("authorizing"));
  post(QStringLiteral("/auth/token"), {}, [this, generation](int status, const QJsonObject &body) {
    if (generation != m_generation)
      return;
    if (status != 200) {
      setStatus(QStringLiteral("disconnected"));
      setMessage(describeFailure(status, body), true);
      return;
    }
    const QString token = body.value(QStringLiteral("token")).toString().trimmed();
    const QUrl url(body.value(QStringLiteral("authorizationUrl")).toString());
    // Only ever send the browser to Last.fm itself.
    if (token.isEmpty() || url.scheme() != QStringLiteral("https") || url.host() != QStringLiteral("www.last.fm")) {
      setStatus(QStringLiteral("disconnected"));
      setMessage(tr("The Last.fm service returned an invalid authorization link."), true);
      return;
    }
    m_pendingToken = token;
    m_pendingExpiresAt = now() + pendingLifetimeMs;
    setStatus(QStringLiteral("pending"));
    setMessage(tr("Approve Orchard in your browser, then finish connecting here."));
    m_openBrowser(url);
  });
}

void Lastfm::completeConnection() {
  if (m_status != QStringLiteral("pending"))
    return;
  if (m_pendingToken.isEmpty() || now() >= m_pendingExpiresAt) {
    cancelConnection();
    setMessage(tr("The Last.fm authorization expired. Start again."), true);
    return;
  }
  const quint64 generation = m_generation;
  setStatus(QStringLiteral("completing"));
  post(QStringLiteral("/auth/session"), {{QStringLiteral("token"), m_pendingToken}},
       [this, generation](int status, const QJsonObject &body) {
         if (generation != m_generation)
           return;
         if (status == 200) {
           const QString user = cleanText(body.value(QStringLiteral("user")).toVariant());
           const QString key = body.value(QStringLiteral("sessionKey")).toString().trimmed();
           if (!user.isEmpty() && !key.isEmpty()) {
             acceptSession(user, key);
             return;
           }
         }
         // Last.fm error 14 (409): the token exists but has not been approved yet.
         if (status == 409) {
           setStatus(QStringLiteral("pending"));
           setMessage(tr("Approve Orchard on Last.fm first, then finish connecting."), true);
           return;
         }
         if (status == 401) {
           cancelConnection();
           setMessage(tr("The Last.fm authorization expired. Start again."), true);
           return;
         }
         setStatus(QStringLiteral("pending"));
         setMessage(status == 200 ? tr("Last.fm returned an invalid session.") : describeFailure(status, body), true);
       });
}

void Lastfm::cancelConnection() {
  if (m_status != QStringLiteral("authorizing") && m_status != QStringLiteral("pending") &&
      m_status != QStringLiteral("completing"))
    return;
  ++m_generation;
  m_pendingToken.clear();
  m_pendingExpiresAt = 0;
  setStatus(QStringLiteral("disconnected"));
  setMessage(QString());
}

void Lastfm::disconnectAccount() {
  forgetSession();
  setMessage(tr("Last.fm disconnected."));
}

void Lastfm::acceptSession(const QString &user, const QString &sessionKey) {
  m_user = user;
  m_sessionKey = sessionKey;
  m_pendingToken.clear();
  m_pendingExpiresAt = 0;
  emit userChanged();
  setStatus(QStringLiteral("connected"));
  setMessage(tr("Connected as %1.").arg(user));
  persistSession();
  // Pick up whatever is already playing.
  m_play.reset();
  trackPlayback();
}

void Lastfm::forgetSession() {
  ++m_generation;
  m_play.reset();
  m_pendingToken.clear();
  m_pendingExpiresAt = 0;
  const bool hadUser = !m_user.isEmpty();
  m_user.clear();
  m_sessionKey.clear();
  deleteSession();
  if (hadUser)
    emit userChanged();
  setStatus(QStringLiteral("disconnected"));
}

void Lastfm::updatePlayback(const QVariantMap &track, bool playing, double position,
                            double duration) {
  m_track = track;
  m_playing = playing;
  m_position = qIsFinite(position) && position >= 0.0 ? position : 0.0;
  m_duration = qIsFinite(duration) && duration > 0.0 ? duration : 0.0;
  trackPlayback();
}

void Lastfm::trackPlayback() {
  if (!m_enabled || !connected() || m_track.value(QStringLiteral("isLive")).toBool()) {
    m_play.reset();
    return;
  }
  const QJsonObject payload = trackPayload(m_track, m_duration);
  if (payload.isEmpty()) {
    m_play.reset();
    return;
  }

  const QString key = QStringList{m_track.value(QStringLiteral("id")).toString(),
                                  payload.value(QStringLiteral("artist")).toString(),
                                  payload.value(QStringLiteral("title")).toString()}
                          .join(QLatin1Char('\n'));
  const qint64 t = now();
  // Back at the start of the same track after real progress: repeat-one, so a new listen.
  const bool repeated = m_play && m_play->key == key && m_position < 2.0 && m_play->lastPosition > 5.0;
  if (!m_play || m_play->key != key || repeated) {
    m_play.reset();
    if (!m_playing)
      return;
    Play play;
    play.key = key;
    play.track = payload;
    play.timestamp = qMax<qint64>(1, t / 1000);
    play.lastPosition = m_position;
    play.lastReportedAt = t;
    m_play = play;

    const quint64 generation = m_generation;
    post(QStringLiteral("/now-playing"),
         {{QStringLiteral("sessionKey"), m_sessionKey}, {QStringLiteral("track"), payload}},
         [this, generation](int status, const QJsonObject &body) {
           if (generation == m_generation && status != 200)
             handleTrackFailure(status, body);
         });
    return;
  }

  Play &play = *m_play;
  const double progress = m_position - play.lastPosition;
  const double elapsed = qMax(0.0, double(t - play.lastReportedAt) / 1000.0);
  // Seeks jump further than the wall clock moved; only real listening counts.
  if (m_playing && progress > 0.0 && progress <= elapsed + 2.0)
    play.playedSeconds += qMin(progress, elapsed);
  play.lastPosition = m_position;
  play.lastReportedAt = t;

  const int duration = qMax(play.track.value(QStringLiteral("duration")).toInt(),
                            payload.value(QStringLiteral("duration")).toInt());
  play.track.insert(QStringLiteral("duration"), duration);
  if (play.scrobbled || play.submitting || t < play.retryAt || !shouldScrobble(duration, play.playedSeconds))
    return;
  submitScrobble();
}

// Your listening history, now with receipts.
void Lastfm::submitScrobble() {
  Play &play = *m_play;
  play.submitting = true;
  const quint64 generation = m_generation;
  const QString key = play.key;
  const qint64 timestamp = play.timestamp;
  const QString title = play.track.value(QStringLiteral("title")).toString();
  post(QStringLiteral("/scrobble"),
       {{QStringLiteral("sessionKey"), m_sessionKey},
        {QStringLiteral("track"), play.track},
        {QStringLiteral("timestamp"), timestamp}},
       [this, generation, key, timestamp, title](int status, const QJsonObject &body) {
         if (generation != m_generation)
           return;
         Play *target = m_play && m_play->key == key && m_play->timestamp == timestamp ? &*m_play : nullptr;
         if (target)
           target->submitting = false;
         if (status == 200) {
           if (target)
             target->scrobbled = true;
           if (body.value(QStringLiteral("ignored")).toBool()) {
             const QString reason = body.value(QStringLiteral("message")).toString();
             setMessage(reason.isEmpty() ? tr("Last.fm ignored %1.").arg(title) : reason);
           } else {
             setMessage(tr("Scrobbled %1.").arg(title));
           }
           return;
         }
         if (target) {
           // A 4xx will not improve with retries; anything else gets another go shortly.
           if (status >= 400 && status < 500 && status != 429)
             target->scrobbled = true;
           else
             target->retryAt = now() + scrobbleRetryMs;
         }
         handleTrackFailure(status, body);
       });
}

void Lastfm::handleTrackFailure(int status, const QJsonObject &body) {
  if (status == 401) {
    forgetSession();
    setMessage(tr("Last.fm revoked Orchard's access. Connect again."), true);
    return;
  }
  setMessage(describeFailure(status, body), true);
}

void Lastfm::restoreSession() {
  setStatus(QStringLiteral("restoring"));
  const quint64 generation = m_generation;
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this, generation](QKeychain::Job *baseJob) {
    // A connection started while the keyring was thinking wins.
    if (generation != m_generation)
      return;
    auto *readJob = static_cast<QKeychain::ReadPasswordJob *>(baseJob);
    const QJsonObject secret = QJsonDocument::fromJson(readJob->textData().toUtf8()).object();
    const QString user = secret.value(QStringLiteral("user")).toString();
    const QString key = secret.value(QStringLiteral("sessionKey")).toString();
    if (readJob->error() != QKeychain::NoError || user.isEmpty() || key.isEmpty()) {
      if (readJob->error() != QKeychain::NoError && readJob->error() != QKeychain::EntryNotFound)
        qWarning().noquote() << "Could not read the Last.fm session from the OS keyring:" << readJob->errorString();
      setStatus(QStringLiteral("disconnected"));
      return;
    }
    // Last.fm session keys never expire; a revoked one surfaces as a 401 later.
    m_user = user;
    m_sessionKey = key;
    emit userChanged();
    setStatus(QStringLiteral("connected"));
    trackPlayback();
  });
  job->start();
}

void Lastfm::persistSession() {
  if (!m_useKeychain || m_sessionKey.isEmpty())
    return;
  const QJsonObject secret{{QStringLiteral("user"), m_user}, {QStringLiteral("sessionKey"), m_sessionKey}};
  auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setTextData(QString::fromUtf8(QJsonDocument(secret).toJson(QJsonDocument::Compact)));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this](QKeychain::Job *finishedJob) {
    if (finishedJob->error() == QKeychain::NoError)
      return;
    qWarning().noquote() << "Could not store the Last.fm session in the OS keyring:" << finishedJob->errorString();
    setMessage(tr("The OS keyring is unavailable, so this Last.fm connection lasts until Orchard closes."), true);
  });
  job->start();
}

void Lastfm::deleteSession() {
  if (!m_useKeychain)
    return;
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [](QKeychain::Job *finishedJob) {
    if (finishedJob->error() != QKeychain::NoError && finishedJob->error() != QKeychain::EntryNotFound)
      qWarning().noquote() << "Could not remove the Last.fm session from the OS keyring:" << finishedJob->errorString();
  });
  job->start();
}
