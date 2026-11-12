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
#include "apple_history.h"
#include "playback_controller.h"
#include "playback_helpers.h"
#include "local/local_track.h"
#include "native_apple_player.h"

#include <QDebug>

#include <cmath>

namespace {
QString appleSongId(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("appleId")).toString();
  if (!id.isEmpty())
    return id;
  const QString browseId = track.value(QStringLiteral("id")).toString();
  const QString prefix = QStringLiteral("am.songs.");
  return browseId.startsWith(prefix) ? browseId.mid(prefix.size()) : QString();
}

// Badge keys for a song MusicKit plays; the variant is what macOS actually streams.
void applyNativeQuality(QVariantMap &track, NativeApplePlayer::Variant variant) {
  for (const auto key : {"playbackSource", "bitDepth", "sampleRate", "hires", "streamCodec", "bitrate"})
    track.remove(QString::fromLatin1(key));
  track.insert(QStringLiteral("playbackSource"), QStringLiteral("musickit"));
  using Variant = NativeApplePlayer::Variant;
  const QString codec = variant == Variant::Lossless || variant == Variant::HiResLossless ? QStringLiteral("ALAC")
                        : variant == Variant::Lossy                                      ? QStringLiteral("AAC")
                        : variant == Variant::Atmos                                      ? QStringLiteral("Dolby Atmos")
                        : variant == Variant::DolbyAudio                                 ? QStringLiteral("Dolby Audio")
                        : variant == Variant::Spatial ? QStringLiteral("Spatial Audio")
                                                      : QString();
  if (!codec.isEmpty())
    track.insert(QStringLiteral("streamCodec"), codec);
  track.insert(QStringLiteral("hires"), variant == Variant::HiResLossless);
}
} // namespace

void PlaybackController::setAppleMusic(AppleMusic *account, CatalogSource *source) {
  m_appleAccount = account;
  m_catalogSource = source;
  m_bestMix.setAppleMusic(account, source);
  if (!findChild<AppleHistory *>())
    new AppleHistory(this, account);
  // MAX and Atmos read as High until the account can back them.
  connect(account, &AppleMusic::changed, this, &PlaybackController::streamQualityChanged);
  if (!findChild<NativeApplePlayer *>() && NativeApplePlayer::available()) {
    auto *native = new NativeApplePlayer(this);
    connect(native, &NativeApplePlayer::started, this, [this, native] {
      m_loading = false;
      m_resumePosition = 0.0;
      m_resumePaused = false;
      m_restoringPlayback = false;
      m_error.clear();
      m_retried = false;
      applyNativeQuality(m_track, native->variant());
      if (m_persistenceEnabled)
        m_persistTimer.start();
      emit stateChanged();
      updateSystemMedia();
      savePlaybackState();
    });
    connect(native, &NativeApplePlayer::variantChanged, this, [this, native] {
      applyNativeQuality(m_track, native->variant());
      emit stateChanged();
    });
    // Authorization, subscription or catalog trouble still leaves the song playable over Widevine.
    connect(native, &NativeApplePlayer::failed, this, [this](const QString &message) {
      qWarning().noquote() << "AppleMusic: lossless playback unavailable |" << message;
      if (!isAppleTrack(m_track))
        return;
      if (m_appleStreams)
        resolveAppleStream(m_track, false);
      else
        fail(message);
    });
    connect(native, &NativeApplePlayer::changed, this,
            [this, native, wasPlaying = false]() mutable {
      const bool playingNow = native->playing();
      if (playingNow != wasPlaying) {
        wasPlaying = playingNow;
        if (playingNow && m_persistenceEnabled)
          m_persistTimer.start();
        if (!playingNow) {
          m_persistTimer.stop();
          savePlaybackState();
        }
        m_lastPublishedPosition = native->position();
        updateSystemMedia();
      } else if (std::abs(native->position() - m_lastPublishedPosition) >= 1.0) {
        m_lastPublishedPosition = native->position();
        updateSystemMedia();
      }
      emit stateChanged();
    });
    connect(native, &NativeApplePlayer::ended, this, [this] {
      if (m_repeatMode == QStringLiteral("one") && !m_track.isEmpty())
        startTrack(m_track);
      else if (canGoNext())
        next();
      else
        updateSystemMedia();
    }, Qt::QueuedConnection);
  }
  // Each catalog only plays its own songs; local files keep playing across a switch.
  connect(source, &CatalogSource::changed, this, [this] {
    if (remoteActive() || m_track.isEmpty() || local::isLocalTrack(m_track) ||
        isAppleTrack(m_track) == m_catalogSource->apple())
      return;
    stop();
    clearQueue();
    m_history.clear();
    emit historyChanged();
  });
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
  auto *native = findChild<NativeApplePlayer *>();
  const bool max = streamQuality() == QStringLiteral("max");
  const bool appleLossless = max && native && m_appleAccount && m_appleAccount->losslessAvailable();
  // With both signed in the Lossless source setting decides; otherwise MAX uses whichever exists.
  const bool viaMusicKit =
      appleLossless && (!qobuzReachable() || m_appleAccount->losslessSource() == QStringLiteral("apple"));
  QString message;
  // MusicKit needs no Widevine.
  if (!m_appleStreams && !viaMusicKit)
    message = AppleMusicStreams::unavailableReason();
  else if (!m_catalogSource || !m_catalogSource->apple())
    message = tr("Choose Apple as the music source under Settings, General to play Apple Music songs.");
  else if (!m_appleAccount->connected())
    message = tr("Connect Apple Music under Settings, General to play this song.");
  else if (id.isEmpty())
    message = tr("This item is missing a playable track identity.");
  if (!message.isEmpty()) {
    fail(message);
    return;
  }
  // A restored song loads when play is pressed, like the other sources.
  if (m_resumePaused) {
    m_loading = false;
    return;
  }
  const quint64 generation = m_generation;
  if (viaMusicKit) {
    m_appleAccount->withNativeDeveloperToken([this, native, generation, id](const QString &token, const QString &error) {
      if (generation != m_generation)
        return;
      if (token.isEmpty())
        qWarning().noquote() << "AppleMusic: no Orchard developer token for MusicKit |" << error;
      else
        native->setDeveloperToken(token);
      native->start(id, m_resumePosition, false);
    });
  } else if (max && qobuzEligible(m_track)) {
    const QVariantMap track = m_track;
    resolveQobuz(track, [this, generation, track](const QJsonValue &result, const QString &) {
      if (generation != m_generation)
        return;
      QJsonObject stream = qobuzStream(track, result);
      // An Apple song has no YouTube recording to point history or SponsorBlock at.
      stream.remove(QStringLiteral("youtubeVideoId"));
      if (!stream.isEmpty())
        startResolvedStream(stream);
      else
        resolveAppleStream(track, false);
    });
  } else {
    resolveAppleStream(m_track, false);
  }
}

void PlaybackController::resolveAppleStream(const QVariantMap &track, bool prepared) {
  const QString id = appleSongId(track);
  // MAX plays Apple songs from MusicKit or a Qobuz match, so an AAC preload would go unused.
  if (prepared && (id.isEmpty() || !m_catalogSource->apple() || !m_appleAccount->connected() ||
                   streamQuality() == QStringLiteral("max")))
    return;
  const quint64 generation = m_generation;
  const quint64 token = prepared ? ++m_preparedResolveToken : 0;
  auto done = [this, generation, token, track, prepared](const QJsonObject &stream, const QString &error) {
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
  };
  m_appleStreams->setStandard(m_streamQuality == QStringLiteral("saver") || m_streamQuality == QStringLiteral("normal"));
  if (m_appleAccount->atmosAvailable() && m_streamQuality == QStringLiteral("atmos"))
    m_appleStreams->resolveAtmos(id, std::move(done));
  else
    m_appleStreams->resolve(id, std::move(done));
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

void PlaybackController::loadAppleQueuePage() {
  if (!m_appleAccount || !m_appleAccount->connected())
    return;
  static quint64 counter = 0;
  const quint64 request = (quint64{1} << 63) | ++counter;
  m_queueRequest = request;
  m_queueError.clear();
  m_appleAccount->playlistMore(m_queueContinuation, m_playlistLoaded,
                               [this, request](const QJsonObject &page, const QString &error) {
    if (request != m_queueRequest)
      return;
    m_queueRequest = 0;
    if (page.isEmpty()) {
      m_queueError = error.isEmpty() ? tr("Apple Music could not load the rest of this playlist.") : error;
      emit queueChanged();
      return;
    }
    const auto tracks = page.value(QStringLiteral("tracks")).toArray().toVariantList();
    m_queueContinuation = page.value(QStringLiteral("continuation")).toString();
    m_playlistLoaded += tracks.size();
    appendPlaylistTracks(m_playlistId, tracks);
    retryQueueLoading();
    ensureAutoplay();
  });
  emit queueChanged();
}
