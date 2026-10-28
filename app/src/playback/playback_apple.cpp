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

#include "apple_music_stream.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"
#include "playback_controller.h"
#include "playback_helpers.h"

namespace {
QString appleSongId(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("appleId")).toString();
  if (!id.isEmpty())
    return id;
  const QString browseId = track.value(QStringLiteral("id")).toString();
  const QString prefix = QStringLiteral("am.songs.");
  return browseId.startsWith(prefix) ? browseId.mid(prefix.size()) : QString();
}
} // namespace

void PlaybackController::setAppleMusic(AppleMusic *account, CatalogSource *source) {
  m_appleAccount = account;
  m_catalogSource = source;
  m_bestMix.setAppleMusic(account, source);
  if (m_appleStreams || !AppleMusicStreams::available())
    return;
  m_appleStreams = new AppleMusicStreams(account, this);
  installRangeReaders();
  // The decryption host only runs while Apple is the music source and the account is usable.
  const auto release = [this] {
    const QString status = m_appleAccount->status();
    if (m_catalogSource->apple() && (status == QStringLiteral("connected") || status == QStringLiteral("restoring")))
      return;
    if (isAppleTrack(m_track))
      stop();
    m_appleStreams->clear();
  };
  connect(source, &CatalogSource::changed, this, release);
  connect(account, &AppleMusic::changed, this, release);
}

void PlaybackController::startAppleTrack() {
  const QString id = appleSongId(m_track);
  QString message;
  if (!m_appleStreams)
    message = AppleMusicStreams::unavailableReason();
  else if (!m_catalogSource || !m_catalogSource->apple())
    message = tr("Choose Apple as the music source under Settings, General to play Apple Music songs.");
  else if (!m_appleAccount->connected())
    message = tr("Connect Apple Music under Settings, Integrations to play this song.");
  else if (id.isEmpty())
    message = tr("This item is missing a playable track identity.");
  if (!message.isEmpty()) {
    fail(message);
    return;
  }
  // A restored song loads when play is pressed, like the other sources.
  if (m_resumePaused)
    m_loading = false;
  else
    resolveAppleStream(m_track, false);
}

void PlaybackController::resolveAppleStream(const QVariantMap &track, bool prepared) {
  const QString id = appleSongId(track);
  if (prepared && (id.isEmpty() || !m_catalogSource->apple() || !m_appleAccount->connected()))
    return;
  const quint64 generation = m_generation;
  const quint64 token = prepared ? ++m_preparedResolveToken : 0;
  m_appleStreams->resolve(id, [this, generation, token, track, prepared](const QJsonObject &stream,
                                                                         const QString &error) {
    if (prepared) {
      if (token == m_preparedResolveToken && m_preparedTrack.value("id") == track.value("id") && !stream.isEmpty())
        openPreparedStream(stream);
      return;
    }
    if (generation != m_generation)
      return;
    if (stream.isEmpty())
      fail(error);
    else
      startResolvedStream(stream);
  });
}

QString PlaybackController::appleMixId(const QVariantMap &track) const {
  if (!isAppleTrack(track) || !m_catalogSource || !m_catalogSource->apple() || !m_appleAccount ||
      !m_appleAccount->connected())
    return {};
  return appleSongId(track);
}

bool PlaybackController::appleMixAnalyses(const QVariantMap &current, const QVariantMap &next,
                                          QJsonObject &outgoing, QJsonObject &incoming) {
  const QString outgoingId = appleMixId(current);
  const QString incomingId = appleMixId(next);
  QStringList missing;
  for (const QString &id : {outgoingId, incomingId})
    if (!id.isEmpty() && !m_appleAnalyses.contains(id))
      missing.append(id);
  if (!missing.isEmpty()) {
    if (m_appleAnalysisPending)
      return false;
    m_appleAnalysisPending = true;
    m_appleAccount->audioAnalysis(missing, [this, missing](const QJsonObject &page, const QString &) {
      m_appleAnalysisPending = false;
      // Only the pair being prepared is worth keeping.
      if (m_appleAnalyses.size() > 4)
        m_appleAnalyses.clear();
      const QJsonObject items = page.value(QStringLiteral("items")).toObject();
      // A miss stays empty, so the pair mixes with the models instead of waiting again.
      for (const QString &id : missing)
        m_appleAnalyses.insert(id, items.value(id).toObject());
    });
    return false;
  }
  outgoing = m_appleAnalyses.value(outgoingId);
  incoming = m_appleAnalyses.value(incomingId);
  return true;
}

bool PlaybackController::requestAppleAutoplay(const QString &seed, bool mix) {
  if (!m_appleAccount || !m_catalogSource || !m_catalogSource->apple() || !m_appleAccount->connected()) {
    m_autoplayError = tr("Choose Apple Music as the music source and connect it to continue this queue.");
    return false;
  }
  // The high bit keeps these ids apart from the YouTube provider's request ids.
  static quint64 counter = 0;
  const quint64 request = (quint64{1} << 63) | ++counter;
  m_autoplayRequest = request;
  m_mixRequest = mix;
  m_appleAccount->upNext(seed, [this, request](const QJsonObject &page, const QString &error) {
    if (request != m_autoplayRequest)
      return;
    if (error.isEmpty()) {
      applyAutoplay(page.value(QStringLiteral("items")).toArray());
      return;
    }
    m_autoplayRequest = 0;
    m_mixRequest = false;
    m_waitingForAutoplay = false;
    m_autoplayError = error;
    emit stateChanged();
  });
  return true;
}
