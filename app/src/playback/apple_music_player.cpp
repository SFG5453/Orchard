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

#include "apple_music_player.h"

#include "integrations/apple_music.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QJsonDocument>
#include <QProcess>
#include <QTimer>

#include <algorithm>
#include <utility>

namespace {
// Events are short; anything longer is not from the page.
constexpr qsizetype kMaxEventBytes = 64 * 1024;
constexpr int kIdleReleaseMs = 5 * 60 * 1000;

QString helperPath() {
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper"));
}
} // namespace

AppleMusicPlayer::AppleMusicPlayer(AppleMusic *account, QObject *parent)
    : QObject(parent), m_account(account), m_fadeTicker(new QTimer(this)), m_idleTimer(new QTimer(this)) {
  // Drives the blend animation; the page reports twice a second.
  m_fadeTicker->setInterval(50);
  connect(m_fadeTicker, &QTimer::timeout, this, &AppleMusicPlayer::changed);
  m_idleTimer->setSingleShot(true);
  m_idleTimer->setInterval(kIdleReleaseMs);
  connect(m_idleTimer, &QTimer::timeout, this, &AppleMusicPlayer::releaseIdle);
  connect(this, &AppleMusicPlayer::changed, this, &AppleMusicPlayer::updateIdleTimer);
  connect(this, &AppleMusicPlayer::playingChanged, this, &AppleMusicPlayer::updateIdleTimer);
}

AppleMusicPlayer::~AppleMusicPlayer() {
  if (!m_process)
    return;
  m_process->disconnect(this);
  m_process->closeWriteChannel();
  if (!m_process->waitForFinished(2000))
    m_process->kill();
}

bool AppleMusicPlayer::available() {
#ifdef Q_OS_LINUX
  return true;
#else
  return false;
#endif
}

void AppleMusicPlayer::start() {
  if (m_process)
    return;
  m_configured = false;
  m_widevine = true;
  m_buffer.clear();
  const quint64 generation = ++m_generation;
  m_process = new QProcess(this);
  if (!qEnvironmentVariableIsSet("ORCHARD_APPLE_MUSIC_DEBUG"))
    m_process->setStandardErrorFile(QProcess::nullDevice());
  else
    m_process->setProcessChannelMode(QProcess::ForwardedErrorChannel);
  connect(m_process, &QProcess::readyReadStandardOutput, this, &AppleMusicPlayer::readEvents);
  connect(m_process, &QProcess::finished, this, &AppleMusicPlayer::processEnded);
  connect(m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
    if (error == QProcess::FailedToStart)
      processEnded();
  });
  // The player never shows a window.
  QProcessEnvironment environment = QProcessEnvironment::systemEnvironment();
  environment.insert(QStringLiteral("QT_QPA_PLATFORM"), QStringLiteral("offscreen"));
  m_process->setProcessEnvironment(environment);
  m_process->start(helperPath(), {QStringLiteral("--apple-music-player")});
  m_account->playbackToken([this, generation](const QString &token, const QString &error) {
    if (generation != m_generation || !m_process)
      return;
    if (token.isEmpty()) {
      shutdown();
      emit failed(error.isEmpty() ? errorText(QStringLiteral("page")) : error);
      return;
    }
    m_configured = true;
    send({{QStringLiteral("type"), QStringLiteral("configure")}, {QStringLiteral("developerToken"), token}});
    send({{QStringLiteral("type"), QStringLiteral("volume")}, {QStringLiteral("value"), m_volume}});
    for (const QByteArray &line : std::exchange(m_pending, {}))
      write(line);
  });
}

void AppleMusicPlayer::send(const QJsonObject &command) {
  const QByteArray line = QJsonDocument(command).toJson(QJsonDocument::Compact) + '\n';
  if (m_configured)
    write(line);
  else
    m_pending.append(line);
}

void AppleMusicPlayer::write(const QByteArray &line) {
  if (m_process)
    m_process->write(line);
}

void AppleMusicPlayer::load(const QString &appleId, double position, bool play) {
  if (appleId.isEmpty())
    return;
  start();
  send({{QStringLiteral("type"), QStringLiteral("load")}, {QStringLiteral("id"), appleId},
        {QStringLiteral("position"), std::max(0.0, position)}, {QStringLiteral("play"), play}});
  // An explicit load outranks a handoff the page may report on its way here.
  dropPrepared();
  m_id = appleId;
  m_position = std::max(0.0, position);
  m_duration = 0.0;
  m_loading = play;
  const bool wasPlaying = std::exchange(m_playing, false);
  m_reportedAt.restart();
  emit changed();
  if (wasPlaying)
    emit playingChanged();
}

void AppleMusicPlayer::play() {
  if (m_id.isEmpty())
    return;
  if (!m_process) {
    load(m_id, m_position, true);
    return;
  }
  send({{QStringLiteral("type"), QStringLiteral("play")}});
  m_loading = true;
  emit changed();
}

void AppleMusicPlayer::pause() {
  if (!m_process)
    return;
  send({{QStringLiteral("type"), QStringLiteral("pause")}});
}

void AppleMusicPlayer::seek(double seconds) {
  if (m_id.isEmpty())
    return;
  m_position = m_duration > 0.0 ? std::clamp(seconds, 0.0, m_duration) : std::max(0.0, seconds);
  m_reportedAt.restart();
  if (m_process)
    send({{QStringLiteral("type"), QStringLiteral("seek")}, {QStringLiteral("position"), m_position}});
  emit changed();
}

void AppleMusicPlayer::stop() {
  if (m_process)
    send({{QStringLiteral("type"), QStringLiteral("stop")}});
  m_id.clear();
  dropPrepared();
  m_position = 0.0;
  m_duration = 0.0;
  m_loading = false;
  if (std::exchange(m_playing, false))
    emit playingChanged();
  emit changed();
}

void AppleMusicPlayer::setVolume(double gain) {
  m_volume = std::clamp(gain, 0.0, 1.0);
  if (m_process)
    send({{QStringLiteral("type"), QStringLiteral("volume")}, {QStringLiteral("value"), m_volume}});
}

void AppleMusicPlayer::prepare(const QString &appleId, double fadeSeconds) {
  if (appleId.isEmpty())
    fadeSeconds = 0.0;
  // A released helper restarts on play(), and the next song is prepared again after that.
  if ((appleId == m_prepared && fadeSeconds == m_preparedFade && !m_preparedMix) ||
      (!m_process && (appleId.isEmpty() || !m_id.isEmpty())))
    return;
  m_prepared = appleId;
  m_preparedFade = fadeSeconds;
  m_preparedMix = false;
  m_mixStart = -1.0;
  m_mixDuration = 0.0;
  if (!appleId.isEmpty())
    start();
  send({{QStringLiteral("type"), QStringLiteral("prepare")}, {QStringLiteral("id"), appleId},
        {QStringLiteral("fade"), fadeSeconds}});
  updateIdleTimer();
}

void AppleMusicPlayer::mix(const QString &appleId, QJsonObject plan, double fallbackFade) {
  if (appleId.isEmpty() || plan.isEmpty() || (appleId == m_prepared && m_preparedMix) || mixRefused(appleId) ||
      (!m_process && !m_id.isEmpty()))
    return;
  m_prepared = appleId;
  m_preparedFade = fallbackFade;
  m_preparedMix = true;
  m_mixStart = -1.0;
  m_mixDuration = 0.0;
  start();
  plan.insert(QStringLiteral("type"), QStringLiteral("mix"));
  plan.insert(QStringLiteral("id"), appleId);
  plan.insert(QStringLiteral("fade"), fallbackFade);
  send(plan);
}

void AppleMusicPlayer::dropPrepared() {
  m_prepared.clear();
  m_preparedMix = false;
  m_mixStart = -1.0;
  m_mixDuration = 0.0;
  clearFade();
}

void AppleMusicPlayer::shutdown() {
  ++m_generation;
  m_pending.clear();
  m_configured = false;
  if (QProcess *process = std::exchange(m_process, nullptr)) {
    process->disconnect(this);
    connect(process, &QProcess::finished, process, &QObject::deleteLater);
    // The helper quits when stdin closes; a wedged one is killed.
    QTimer::singleShot(3000, process, [process] { process->kill(); });
    process->closeWriteChannel();
  }
  m_id.clear();
  dropPrepared();
  m_loading = false;
  if (std::exchange(m_playing, false))
    emit playingChanged();
}

// Nothing is playing or blending, and no song waits to follow a song from another source.
bool AppleMusicPlayer::idle() const {
  return m_process && !m_playing && !m_loading && m_fadeId.isEmpty() && (!m_id.isEmpty() || m_prepared.isEmpty());
}

void AppleMusicPlayer::updateIdleTimer() {
  if (!idle())
    m_idleTimer->stop();
  else if (!m_idleTimer->isActive())
    m_idleTimer->start();
}

// The helper holds a browser's worth of memory, so a paused song keeps only its place.
void AppleMusicPlayer::releaseIdle() {
  if (!idle())
    return;
  const QString id = m_id;
  const double position = m_position;
  const double duration = m_duration;
  shutdown();
  m_id = id;
  m_position = position;
  m_duration = duration;
  emit changed();
}

double AppleMusicPlayer::position() const {
  double position = m_position;
  if (m_playing && !m_loading && m_reportedAt.isValid())
    position += m_reportedAt.elapsed() / 1000.0;
  return m_duration > 0.0 ? std::min(position, m_duration) : position;
}

double AppleMusicPlayer::fadeElapsed() const {
  return m_fadeBanked + (m_playing ? m_fadeClock.elapsed() / 1000.0 : 0.0);
}

double AppleMusicPlayer::fadePosition() const {
  if (m_fadeId.isEmpty())
    return 0.0;
  const double position = m_fadeAt + fadeElapsed();
  return m_fadeDuration > 0.0 ? std::min(position, m_fadeDuration) : position;
}

double AppleMusicPlayer::fadeProgress() const {
  if (m_fadeId.isEmpty())
    return 0.0;
  return std::clamp(fadeElapsed() / m_fadeSeconds, 0.0, 1.0);
}

void AppleMusicPlayer::clearFade() {
  m_fadeTicker->stop();
  m_fadeId.clear();
  m_fadeSeconds = 0.0;
  m_fadeFrom = 0.0;
  m_fadeAt = 0.0;
  m_fadeBanked = 0.0;
  m_fadeDuration = 0.0;
}

void AppleMusicPlayer::readEvents() {
  if (!m_process)
    return;
  m_buffer.append(m_process->readAllStandardOutput());
  for (qsizetype end = m_buffer.indexOf('\n'); end >= 0; end = m_buffer.indexOf('\n')) {
    const QJsonDocument event = QJsonDocument::fromJson(m_buffer.left(end));
    m_buffer.remove(0, end + 1);
    // Chromium's own output can share the pipe; only objects with a type are events.
    if (event.isObject() && event.object().value(QStringLiteral("type")).isString())
      receive(event.object());
    if (!m_process)
      return;
  }
  if (m_buffer.size() > kMaxEventBytes)
    m_buffer.clear();
}

void AppleMusicPlayer::receive(const QJsonObject &event) {
  const QString type = event.value(QStringLiteral("type")).toString();
  const QString id = event.value(QStringLiteral("id")).toString();
  if (type == QStringLiteral("host")) {
    m_widevine = event.value(QStringLiteral("widevine")).toBool();
  } else if (type == QStringLiteral("ready")) {
    if (!event.value(QStringLiteral("authorized")).toBool())
      emit failed(errorText(QStringLiteral("unauthorized")));
  } else if (type == QStringLiteral("state")) {
    if (id.isEmpty() || id != m_id)
      return;
    const bool playing = event.value(QStringLiteral("playing")).toBool();
    m_loading = event.value(QStringLiteral("loading")).toBool();
    m_position = std::max(0.0, event.value(QStringLiteral("position")).toDouble());
    m_duration = std::max(0.0, event.value(QStringLiteral("duration")).toDouble());
    m_reportedAt.restart();
    // A paused blend holds its place in the page, so its progress holds here too.
    if (!m_fadeId.isEmpty() && playing != m_playing) {
      if (!playing)
        m_fadeBanked += m_fadeClock.elapsed() / 1000.0;
      else
        m_fadeClock.restart();
    }
    const bool playingChanged = std::exchange(m_playing, playing) != playing;
    emit changed();
    if (playingChanged)
      emit this->playingChanged();
  } else if (type == QStringLiteral("ended")) {
    if (id != m_id)
      return;
    m_playing = false;
    m_loading = false;
    m_position = m_duration;
    emit playingChanged();
    emit ended();
  } else if (type == QStringLiteral("fading") || type == QStringLiteral("mixing")) {
    if (id.isEmpty() || id != m_prepared)
      return;
    m_fadeId = id;
    m_fadeFrom = event.value(QStringLiteral("from")).toDouble(position());
    m_fadeAt = std::max(0.0, event.value(QStringLiteral("at")).toDouble());
    m_fadeSeconds = std::max(0.05, event.value(QStringLiteral("seconds")).toDouble());
    m_fadeDuration = std::max(0.0, event.value(QStringLiteral("duration")).toDouble());
    m_fadeBanked = 0.0;
    m_fadeClock.restart();
    m_fadeTicker->start();
    emit fadeStarted();
  } else if (type == QStringLiteral("log")) {
    qInfo().noquote() << "AppleMusic: page |" << event.value(QStringLiteral("text")).toString();
  } else if (type == QStringLiteral("mixArmed")) {
    if (id == m_prepared && m_preparedMix) {
      m_mixStart = event.value(QStringLiteral("outgoingStart")).toDouble(-1.0);
      m_mixDuration = std::max(0.0, event.value(QStringLiteral("duration")).toDouble());
      emit changed();
      emit mixSettled({});
    }
  } else if (type == QStringLiteral("mixFailed")) {
    // The page fell back to the crossfade or gapless start on its own.
    if (id == m_prepared && m_preparedMix) {
      m_preparedMix = false;
      m_mixStart = -1.0;
      m_mixDuration = 0.0;
      m_refusedMix = m_id + QLatin1Char('\n') + id;
      const QString reason = event.value(QStringLiteral("reason")).toString();
      qInfo().noquote() << "AdaptiveMix: Apple Music mix not placed |" << reason;
      emit mixSettled(reason.isEmpty() ? tr("the songs could not be lined up") : reason);
    }
  } else if (type == QStringLiteral("advanced")) {
    if (id.isEmpty() || id != m_prepared)
      return;
    m_id = id;
    dropPrepared();
    // A mix hands over past the incoming song's start.
    m_position = std::max(0.0, event.value(QStringLiteral("position")).toDouble());
    m_duration = 0.0;
    m_playing = true;
    m_reportedAt.restart();
    emit advanced(id);
  } else if (type == QStringLiteral("error")) {
    // A failed preload only costs the gapless start.
    if (!id.isEmpty() && id != m_id) {
      if (id == m_prepared)
        dropPrepared();
      return;
    }
    m_loading = false;
    if (std::exchange(m_playing, false))
      emit playingChanged();
    emit failed(errorText(event.value(QStringLiteral("code")).toString()));
  }
}

void AppleMusicPlayer::processEnded() {
  if (!m_process)
    return;
  const bool wasActive = !m_id.isEmpty();
  m_process->disconnect(this);
  m_process->deleteLater();
  m_process = nullptr;
  ++m_generation;
  m_pending.clear();
  m_configured = false;
  m_id.clear();
  dropPrepared();
  m_loading = false;
  if (std::exchange(m_playing, false))
    emit playingChanged();
  if (wasActive)
    emit failed(errorText(QStringLiteral("crashed")));
}

QString AppleMusicPlayer::errorText(const QString &code) const {
  if (code == QStringLiteral("unauthorized"))
    return tr("Apple Music is signed out. Reconnect it under Settings, Integrations.");
  if (code == QStringLiteral("subscription"))
    return tr("Playing Apple Music songs needs an active Apple Music subscription.");
  if (code == QStringLiteral("unavailable"))
    return tr("This song is not available on Apple Music in your region.");
  if (code == QStringLiteral("drm"))
    return m_widevine ? tr("Apple Music could not unlock this song's protected audio.")
                      : tr("Apple Music playback needs Widevine. Install Google Chrome or Chromium with Widevine, then try again.");
  if (code == QStringLiteral("network"))
    return tr("Could not reach Apple Music. Check your connection and try again.");
  if (code == QStringLiteral("crashed"))
    return tr("Apple Music's player stopped unexpectedly.");
  if (code == QStringLiteral("page"))
    return tr("Apple Music's player could not start.");
  return tr("Apple Music could not play this song.");
}
