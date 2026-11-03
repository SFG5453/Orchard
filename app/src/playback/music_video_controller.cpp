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

#include "music_video_controller.h"

#include "apple_music_picture.h"
#include "apple_music_stream.h"
#include "integrations/apple_music.h"
#include "auth/auth_manager.h"
#include "letterbox.h"
#include "local/local_track.h"
#include "music_video_match.h"
#include "playback_controller.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonArray>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSettings>
#include <QVideoFrame>
#include <QVideoSink>

namespace {

const QString kMaxHeightKey = QStringLiteral("musicVideo/maxHeight");
const QString kUpscalingKey = QStringLiteral("musicVideo/upscaling");
const QString kSharpnessKey = QStringLiteral("musicVideo/sharpness");

bool isYouTubeId(const QString &id) {
  static const QRegularExpression pattern(QStringLiteral("^[\\w-]{11}$"));
  return pattern.match(id).hasMatch();
}

bool isAppleSongId(const QString &id) { return id.startsWith(QStringLiteral("am.songs.")); }

} // namespace

MusicVideoController::MusicVideoController(YouTubeProvider *provider, AuthManager *auth,
                                           PlaybackController *playback, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth), m_playback(playback) {
  connect(m_playback, &PlaybackController::stateChanged, this, &MusicVideoController::syncTrack);
  connect(m_provider, &YouTubeProvider::resultReady, this, &MusicVideoController::receive);
  connect(m_provider, &YouTubeProvider::requestFailed, this, &MusicVideoController::fail);
  connect(m_playback, &PlaybackController::videoSourceLost, this, [this] {
    if (!m_open)
      return;
    clearStream();
    setStatus(QStringLiteral("error"), tr("The video stream stopped, so the song is playing instead."));
  });
  connect(&m_proxy, &AudioStreamProxy::streamFailed, this, [this](int, const QString &message) {
    setStatus(QStringLiteral("error"), message);
  });
  // One frame a second is enough; bars do not move within a video.
  m_frameTimer.setInterval(1000);
  connect(&m_frameTimer, &QTimer::timeout, this, &MusicVideoController::sampleFrame);
  QSettings settings;
  m_maxHeight = settings.value(kMaxHeightKey, m_maxHeight).toInt();
  m_upscaling = settings.value(kUpscalingKey, m_upscaling).toBool();
  m_sharpness = qBound(0.0, settings.value(kSharpnessKey, m_sharpness).toDouble(), 1.0);
  loadPipSettings();
  syncTrack();
}

void MusicVideoController::setAppleMusic(AppleMusic *account) {
  if (m_applePicture || !AppleMusicStreams::available())
    return;
  m_account = account;
  m_applePicture = new AppleMusicPicture(account, m_playback, this);
  m_applePicture->setSink(m_sink);
  connect(m_applePicture, &AppleMusicPicture::started, this, [this] {
    m_height = m_applePicture->height();
    m_heights = m_applePicture->heights();
    setStatus(QStringLiteral("ready"));
  });
  connect(m_applePicture, &AppleMusicPicture::failed, this,
          [this](const QString &message) { setStatus(QStringLiteral("error"), message); });
  syncTrack();
}

QRectF MusicVideoController::contentRect() const {
  return m_contentRect.isNull() ? QRectF(0, 0, 1, 1) : m_contentRect;
}

void MusicVideoController::watchFrames(QObject *sink) {
  m_sink = qobject_cast<QVideoSink *>(sink);
  if (m_applePicture)
    m_applePicture->setSink(m_sink);
  if (m_sink)
    m_frameTimer.start();
  else
    m_frameTimer.stop();
}

// Polls the sink on the GUI thread; its frame signal can fire on the decoder thread.
void MusicVideoController::sampleFrame() {
  if (!m_sink) {
    m_frameTimer.stop();
    return;
  }
  const QVideoFrame frame = m_sink->videoFrame();
  if (!frame.isValid())
    return;
  // Grow only: a dark scene can hide picture, never invent it.
  const QRectF grown = letterbox::accumulate(m_contentRect, letterbox::contentRect(frame.toImage()));
  if (grown == m_contentRect)
    return;
  m_contentRect = grown;
  emit contentRectChanged();
}

void MusicVideoController::resetContentRect() {
  if (m_contentRect.isNull())
    return;
  m_contentRect = {};
  emit contentRectChanged();
}

void MusicVideoController::setMaxHeight(int height) {
  height = qMax(0, height);
  if (height == m_maxHeight)
    return;
  m_maxHeight = height;
  QSettings().setValue(kMaxHeightKey, height);
  emit changed();
  // The soundtrack keeps playing; only the picture is fetched again.
  if (m_open && !m_videoId.isEmpty())
    resolveStream();
}

void MusicVideoController::setUpscaling(bool enabled) {
  if (enabled == m_upscaling)
    return;
  m_upscaling = enabled;
  QSettings().setValue(kUpscalingKey, enabled);
  emit changed();
}

void MusicVideoController::setSharpness(double sharpness) {
  sharpness = qBound(0.0, sharpness, 1.0);
  if (qFuzzyCompare(sharpness, m_sharpness))
    return;
  m_sharpness = sharpness;
  QSettings().setValue(kSharpnessKey, sharpness);
  emit changed();
}

QString MusicVideoController::qualityNote() const {
#ifdef Q_OS_LINUX
  // Apple licenses taller renditions only to players with HDCP output, which Linux cannot report.
  if (m_apple)
    return tr("Apple limits videos on Linux to 486p.");
#endif
  return {};
}

bool MusicVideoController::available() const {
  return !m_videoId.isEmpty() && m_status != QStringLiteral("checking") &&
         m_status != QStringLiteral("unavailable");
}

void MusicVideoController::show() {
  if (m_open) {
    m_pip = false;
    m_mini = false;
    emit changed();
    return;
  }
  m_open = true;
  if (!m_videoId.isEmpty())
    resolveStream();
  else
    retryLookup();
}

void MusicVideoController::retryLookup() {
  const bool appleSong = isAppleSongId(m_trackId) && m_applePicture;
  if (m_status == QStringLiteral("checking") || local::isLocalTrack(m_track))
    emit changed();
  else if (appleSong && !m_appleLookups.contains(m_trackId))
    appleLookup();
  else if (m_searchRequest == 0 && !m_lookups.contains(m_trackId) &&
           (isYouTubeId(m_trackId) || isAppleSongId(m_trackId)))
    lookup();
  else
    emit changed();
}

void MusicVideoController::playVideo(const QVariantMap &media) {
  const bool sameTrack = media.value(QStringLiteral("id")).toString() == m_trackId;
  m_open = true;
  m_mini = false;
  clearStream();
  setStatus(QStringLiteral("loading"));
  // Starts the row like any catalog click. A new id reaches syncTrack, which resolves
  // the video because the theater is open; the same id restarts without an id change.
  m_playback->playSong(media);
  if (!sameTrack) {
    if (m_trackId != media.value(QStringLiteral("id")).toString())
      setStatus(QStringLiteral("error"), tr("This video could not be started here."));
    return;
  }
  if (!m_videoId.isEmpty())
    resolveStream();
  else
    retryLookup();
}

void MusicVideoController::hide() {
  if (!m_open)
    return;
  m_open = false;
  m_pip = false;
  m_mini = false;
  clearStream();
  m_playback->playAlbumSource();
  if (!m_videoId.isEmpty())
    m_status = QStringLiteral("available");
  emit changed();
}

void MusicVideoController::retry() {
  if (m_open && !m_videoId.isEmpty())
    resolveStream();
}

// stateChanged also fires for position ticks, so only an id change does work.
void MusicVideoController::syncTrack() {
  const QVariantMap track = m_playback->shownTrack();
  const QString id = track.value(QStringLiteral("id")).toString();
  if (id == m_trackId)
    return;
  m_track = track;
  m_trackId = id;
  m_videoId.clear();
  m_searchRequest = 0;
  m_streamRequest = 0;
  clearStream();
  m_appleSong = false;
  m_apple = m_applePicture && id.startsWith(QStringLiteral("am.music-videos."));
  if (m_apple) {
    m_videoId = track.value(QStringLiteral("appleId")).toString();
    if (m_videoId.isEmpty())
      m_videoId = id.section(QLatin1Char('.'), 2);
    if (m_open)
      resolveStream();
    else
      setStatus(QStringLiteral("available"));
    return;
  }
  if (isAppleSongId(id) && !local::isLocalTrack(track)) {
    if (m_applePicture)
      appleLookup();
    else
      lookup();
    return;
  }
  if (id.isEmpty() || local::isLocalTrack(track) || !isYouTubeId(id)) {
    setStatus(QString());
    return;
  }
  lookup();
}

void MusicVideoController::lookup() {
  QString direct = musicvideo::directVideoId(m_track);
  if (direct.isEmpty() && m_lookups.contains(m_trackId))
    direct = m_lookups.value(m_trackId);
  if (!direct.isEmpty() || m_lookups.contains(m_trackId)) {
    m_videoId = direct;
    if (m_videoId.isEmpty())
      setStatus(QStringLiteral("unavailable"));
    else if (m_open)
      resolveStream();
    else
      setStatus(QStringLiteral("available"));
    return;
  }
  setStatus(QStringLiteral("checking"));
  m_searchRequest = m_provider->invoke(QStringLiteral("catalog.search"), QJsonObject{
      {QStringLiteral("guest"), true},
      {QStringLiteral("query"), musicvideo::searchQuery(m_track)},
      {QStringLiteral("filter"), QStringLiteral("videos")}});
}

void MusicVideoController::appleLookup() {
  const auto found = m_appleLookups.constFind(m_trackId);
  if (found != m_appleLookups.cend()) {
    useAppleVideo(*found);
    return;
  }
  setStatus(QStringLiteral("checking"));
  const QString trackId = m_trackId;
  QString songId = m_track.value(QStringLiteral("appleId")).toString();
  if (songId.isEmpty())
    songId = trackId;
  m_account->musicVideo(songId, [this, trackId](const QJsonObject &page, const QString &error) {
    const QString videoId = page.value(QStringLiteral("videoId")).toString();
    // Failures stay uncached, so reopening the player asks Apple again.
    if (error.isEmpty())
      m_appleLookups.insert(trackId, videoId);
    if (trackId == m_trackId)
      useAppleVideo(videoId);
  });
}

void MusicVideoController::useAppleVideo(const QString &videoId) {
  if (videoId.isEmpty()) {
    lookup();
    return;
  }
  m_apple = true;
  m_appleSong = true;
  m_videoId = videoId;
  if (m_open)
    resolveStream();
  else
    setStatus(QStringLiteral("available"));
}

void MusicVideoController::resolveStream() {
  clearStream();
  setStatus(QStringLiteral("loading"));
  if (m_apple) {
    m_applePicture->start(m_videoId, m_maxHeight);
    // An Apple music video item already plays its own soundtrack; a song swaps to it.
    if (!m_appleSong)
      return;
    const QString trackId = m_trackId;
    const QString videoId = m_videoId;
    m_playback->resolveAppleVideo(videoId, [this, trackId, videoId](const QJsonObject &stream, const QString &error) {
      if (!m_open || trackId != m_trackId || videoId != m_videoId)
        return;
      if (stream.isEmpty()) {
        clearStream();
        setStatus(QStringLiteral("error"), error);
        return;
      }
      m_playback->playVideoSource(stream);
    });
    return;
  }
  m_streamRequest = m_provider->invoke(QStringLiteral("playback.resolveVideo"), QJsonObject{
      {QStringLiteral("session"), m_auth->sessionObject()},
      {QStringLiteral("streamQuality"), QStringLiteral("high")},
      {QStringLiteral("maxHeight"), m_maxHeight},
      {QStringLiteral("track"), QJsonObject{{QStringLiteral("id"), m_videoId},
                                            {QStringLiteral("type"), QStringLiteral("video")}}}});
}

void MusicVideoController::receive(quint64 requestId, const QJsonValue &result) {
  if (requestId == 0)
    return;
  if (requestId == m_searchRequest) {
    m_searchRequest = 0;
    m_videoId = musicvideo::bestVideoId(m_track, musicvideo::videoCandidates(result.toObject()));
    m_lookups.insert(m_trackId, m_videoId);
    if (m_videoId.isEmpty())
      setStatus(QStringLiteral("unavailable"));
    else if (m_open)
      resolveStream();
    else
      setStatus(QStringLiteral("available"));
  } else if (requestId == m_streamRequest) {
    m_streamRequest = 0;
    if (!m_open)
      return;
    const QJsonObject stream = result.toObject();
    // The proxy keeps YouTube's User-Agent and Origin headers on every range request.
    m_source = m_proxy.open(stream);
    if (m_source.isEmpty()) {
      setStatus(QStringLiteral("error"), tr("The video stream could not be opened."));
      return;
    }
    m_height = stream.value(QStringLiteral("height")).toInt();
    m_heights = stream.value(QStringLiteral("heights")).toArray().toVariantList();
    // The audio engine plays the video's own soundtrack; the picture stays muted and follows it.
    const QJsonObject audio = stream.value(QStringLiteral("audio")).toObject();
    m_playback->playVideoSource(audio.isEmpty() ? stream : audio);
    setStatus(QStringLiteral("ready"));
  }
}

void MusicVideoController::fail(quint64 requestId, const QString &message) {
  if (requestId == 0)
    return;
  if (requestId == m_searchRequest) {
    // Network failures are not cached, so reopening the player tries again.
    m_searchRequest = 0;
    setStatus(QStringLiteral("unavailable"));
  } else if (requestId == m_streamRequest) {
    m_streamRequest = 0;
    setStatus(QStringLiteral("error"), message);
  }
}

void MusicVideoController::setStatus(const QString &status, const QString &error) {
  m_status = status;
  m_error = error;
  emit changed();
}

void MusicVideoController::clearStream() {
  if (m_applePicture)
    m_applePicture->stop();
  resetContentRect();
  m_height = 0;
  m_heights.clear();
  if (m_source.isEmpty())
    return;
  m_source.clear();
  m_proxy.clear();
}
