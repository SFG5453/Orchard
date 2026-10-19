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

#include "apple_music_mix.h"

#include "adaptive_mix/adaptive_mix_controller.h"
#include "apple_youtube_match.h"
#include "auth/auth_manager.h"
#include "providers/youtube/youtube_provider.h"

#include <QDebug>
#include <algorithm>
#include <cstring>

namespace {

QString songId(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("appleId")).toString();
  return id.isEmpty() ? track.value(QStringLiteral("id")).toString() : id;
}

// f32 samples to little-endian int16, base64; the page rebuilds floats from it.
QString pcm16(const char *data, qsizetype count) {
  QByteArray out(count * 2, Qt::Uninitialized);
  auto *dst = reinterpret_cast<qint16 *>(out.data());
  for (qsizetype i = 0; i < count; ++i) {
    float sample;
    std::memcpy(&sample, data + i * 4, 4);
    dst[i] = static_cast<qint16>(std::clamp(sample, -1.0f, 1.0f) * 32767.0f);
  }
  return QString::fromLatin1(out.toBase64());
}

} // namespace

AppleMusicMix::AppleMusicMix(YouTubeProvider *provider, AuthManager *auth, AdaptiveMixController *mix,
                             QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth), m_mix(mix) {
  connect(m_provider, &YouTubeProvider::resultReady, this, &AppleMusicMix::receive);
  connect(m_provider, &YouTubeProvider::requestFailed, this, &AppleMusicMix::requestFailed);
  connect(m_mix, &AdaptiveMixController::changed, this, [this] {
    if (m_pair.isEmpty() || m_mix->pair() != m_pair || m_mix->preparing())
      return;
    // The worker has read both songs.
    m_proxies[0].clear();
    m_proxies[1].clear();
    emit changed();
  });
}

QString AppleMusicMix::pairKey(const QVariantMap &current, const QVariantMap &next) {
  return QStringLiteral("apple:") + songId(current) + QLatin1Char('\n') + songId(next);
}

void AppleMusicMix::clear() {
  m_pair.clear();
  for (int side = 0; side < 2; ++side) {
    m_songs[side].clear();
    m_videos[side].clear();
    m_streams[side] = {};
    m_searches[side] = m_resolves[side] = 0;
    m_proxies[side].clear();
  }
  m_noMatch = m_failed = m_analyse = false;
}

void AppleMusicMix::prepare(const QVariantMap &current, const QVariantMap &next, double position,
                            int fadeSeconds, bool albumSequential, bool analyseNow) {
  const QString pair = pairKey(current, next);
  if (pair == m_pair && analyseNow && !m_analyse) {
    m_analyse = true;
    m_position = position;
    resolve();
    return;
  }
  if (pair == m_pair) {
    // Playback cleared the shared controller; analyse again with the streams already open.
    if (!m_noMatch && !m_failed && !m_streams[0].isEmpty() && !m_streams[1].isEmpty() &&
        m_mix->pair() != m_pair && !m_mix->preparing())
      analyse();
    return;
  }
  clear();
  m_pair = pair;
  m_songs[0] = current;
  m_songs[1] = next;
  m_position = position;
  m_fadeSeconds = fadeSeconds;
  m_albumSequential = albumSequential;
  m_analyse = analyseNow;
  match(0);
  match(1);
  emit changed();
}

AppleMusicMix::State AppleMusicMix::state() const {
  if (m_pair.isEmpty())
    return State::Idle;
  if (m_noMatch)
    return State::NoMatch;
  if (m_failed)
    return State::Standard;
  if (!m_analyse || m_mix->pair() != m_pair || m_mix->preparing())
    return State::Working;
  if (m_mix->ready())
    return State::Ready;
  return m_mix->keepsBoundary() ? State::Boundary : State::Standard;
}

void AppleMusicMix::match(int side) {
  const QString id = songId(m_songs[side]);
  if (const auto found = m_matches.constFind(id); found != m_matches.constEnd()) {
    if (found->isEmpty()) {
      giveUp(tr("No YouTube Music match for “%1” to plan from.")
                 .arg(m_songs[side].value(QStringLiteral("title")).toString()),
             true);
      return;
    }
    m_videos[side] = *found;
    resolve();
    return;
  }
  // Guest lookups keep these searches out of the account's history.
  m_searches[side] = m_provider->invoke(QStringLiteral("catalog.search"),
                                        QJsonObject{{QStringLiteral("guest"), true},
                                                    {QStringLiteral("query"), appleyt::searchQuery(m_songs[side])},
                                                    {QStringLiteral("filter"), QStringLiteral("songs")}});
}

void AppleMusicMix::resolve() {
  if (!m_analyse || m_noMatch || m_failed || m_videos[0].isEmpty() || m_videos[1].isEmpty())
    return;
  for (int i = 0; i < 2; ++i) {
    if (!m_streams[i].isEmpty() || m_resolves[i])
      continue;
    const QVariantMap &song = m_songs[i];
    m_resolves[i] = m_provider->invoke(
        QStringLiteral("playback.resolve"),
        QJsonObject{{QStringLiteral("track"),
                     QJsonObject{{QStringLiteral("id"), m_videos[i]},
                                 {QStringLiteral("type"), QStringLiteral("song")},
                                 {QStringLiteral("durationSeconds"), song.value(QStringLiteral("durationSeconds")).toDouble()}}},
                    {QStringLiteral("session"), m_auth->sessionObject()},
                    {QStringLiteral("streamQuality"), QStringLiteral("high")}});
  }
}

void AppleMusicMix::analyse() {
  QUrl urls[2];
  for (int side = 0; side < 2; ++side) {
    urls[side] = m_proxies[side].open(m_streams[side]);
    if (urls[side].isEmpty()) {
      giveUp(tr("Could not open the YouTube Music audio."), false);
      return;
    }
  }
  // Plans in the YouTube upload's own time; the page maps it onto Apple's by correlation.
  const double duration = m_streams[0].value(QStringLiteral("durationSeconds")).toDouble(
      m_songs[0].value(QStringLiteral("durationSeconds")).toDouble());
  m_mix->prepare(m_pair, urls[0], urls[1], duration, m_position, m_songs[0], m_songs[1], m_fadeSeconds,
                 m_albumSequential, true);
  emit changed();
}

void AppleMusicMix::receive(quint64 request, const QJsonValue &result) {
  for (int side = 0; side < 2; ++side) {
    if (request == m_searches[side]) {
      m_searches[side] = 0;
      const QString video = appleyt::bestVideoId(m_songs[side], appleyt::songCandidates(result.toObject()));
      m_matches.insert(songId(m_songs[side]), video);
      if (video.isEmpty()) {
        giveUp(tr("No YouTube Music match for “%1” to plan from.")
                   .arg(m_songs[side].value(QStringLiteral("title")).toString()),
               true);
        return;
      }
      m_videos[side] = video;
      resolve();
      return;
    }
    if (request == m_resolves[side]) {
      m_resolves[side] = 0;
      m_streams[side] = result.toObject();
      if (!m_streams[0].isEmpty() && !m_streams[1].isEmpty())
        analyse();
      return;
    }
  }
}

void AppleMusicMix::requestFailed(quint64 request, const QString &message) {
  for (int side = 0; side < 2; ++side) {
    if (request == m_searches[side] || request == m_resolves[side]) {
      m_searches[side] = m_resolves[side] = 0;
      // A network failure is not cached as "no match", so the next pair asks again.
      giveUp(message, true);
      return;
    }
  }
}

void AppleMusicMix::giveUp(const QString &message, bool noMatch) {
  if (m_noMatch || m_failed)
    return;
  m_noMatch = noMatch;
  m_failed = !noMatch;
  for (int side = 0; side < 2; ++side) {
    m_searches[side] = m_resolves[side] = 0;
    m_proxies[side].clear();
  }
  qInfo().noquote() << "AdaptiveMix: Apple Music pair without a plan |" << message;
  m_mix->failPair(m_pair, message);
  emit changed();
}

QJsonObject AppleMusicMix::scanRequest() const {
  if (m_pair.isEmpty())
    return {};
  // The planner reads these from both tracks; artwork and the rest stay behind.
  const auto track = [](const QVariantMap &song) {
    QJsonObject out;
    for (const char *key : {"id", "title", "artist", "album", "type", "durationSeconds", "queueOrigin", "index"}) {
      if (song.contains(QLatin1String(key)))
        out.insert(QLatin1String(key), QJsonValue::fromVariant(song.value(QLatin1String(key))));
    }
    return out;
  };
  return {{QStringLiteral("outgoingDuration"), m_songs[0].value(QStringLiteral("durationSeconds")).toDouble()},
          {QStringLiteral("incomingDuration"), m_songs[1].value(QStringLiteral("durationSeconds")).toDouble()},
          {QStringLiteral("position"), m_position},
          {QStringLiteral("fadeSeconds"), m_fadeSeconds},
          {QStringLiteral("albumSequential"), m_albumSequential},
          {QStringLiteral("currentTrack"), track(m_songs[0])},
          {QStringLiteral("nextTrack"), track(m_songs[1])}};
}

QJsonObject AppleMusicMix::plan() const {
  if (state() != State::Ready)
    return {};
  const QByteArray &render = m_mix->pcm();
  const QByteArray &references = m_mix->references();
  const QJsonObject info = m_mix->referenceInfo();
  const qsizetype outgoingFrames = info.value(QStringLiteral("outgoingFrames")).toInteger();
  const qsizetype incomingFrames = info.value(QStringLiteral("incomingFrames")).toInteger();
  if (outgoingFrames <= 0 || incomingFrames <= 0 || references.size() != (outgoingFrames + incomingFrames) * 4)
    return {};
  return {{QStringLiteral("outgoingStart"), m_mix->outgoingStart()},
          {QStringLiteral("incomingCue"), m_mix->incomingCue()},
          {QStringLiteral("duration"), m_mix->duration()},
          {QStringLiteral("render"), pcm16(render.constData(), render.size() / 4)},
          {QStringLiteral("outgoing"),
           QJsonObject{{QStringLiteral("start"), info.value(QStringLiteral("outgoingStart")).toDouble()},
                       {QStringLiteral("data"), pcm16(references.constData(), outgoingFrames)}}},
          {QStringLiteral("incoming"),
           QJsonObject{{QStringLiteral("start"), info.value(QStringLiteral("incomingStart")).toDouble()},
                       {QStringLiteral("data"), pcm16(references.constData() + outgoingFrames * 4, incomingFrames)}}}};
}
