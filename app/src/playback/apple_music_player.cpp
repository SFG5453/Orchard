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
#include <QDir>
#include <QJsonDocument>
#include <QProcess>
#include <QTimer>

#include <algorithm>
#include <utility>

namespace {
// Events are short; anything longer is not from the page.
constexpr qsizetype kMaxEventBytes = 64 * 1024;

QString helperPath() {
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-auth-helper"));
}
} // namespace

AppleMusicPlayer::AppleMusicPlayer(AppleMusic *account, QObject *parent)
    : QObject(parent), m_account(account) {}

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
  m_prepared.clear();
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
  if (!m_process || m_id.isEmpty())
    return;
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
  if (!m_process || m_id.isEmpty())
    return;
  m_position = m_duration > 0.0 ? std::clamp(seconds, 0.0, m_duration) : std::max(0.0, seconds);
  m_reportedAt.restart();
  send({{QStringLiteral("type"), QStringLiteral("seek")}, {QStringLiteral("position"), m_position}});
  emit changed();
}

void AppleMusicPlayer::stop() {
  if (m_process)
    send({{QStringLiteral("type"), QStringLiteral("stop")}});
  m_id.clear();
  m_prepared.clear();
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
  if ((appleId == m_prepared && fadeSeconds == m_preparedFade) || (appleId.isEmpty() && !m_process))
    return;
  m_prepared = appleId;
  m_preparedFade = fadeSeconds;
  if (!appleId.isEmpty())
    start();
  send({{QStringLiteral("type"), QStringLiteral("prepare")}, {QStringLiteral("id"), appleId},
        {QStringLiteral("fade"), fadeSeconds}});
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
  m_prepared.clear();
  m_loading = false;
  if (std::exchange(m_playing, false))
    emit playingChanged();
}

double AppleMusicPlayer::position() const {
  double position = m_position;
  if (m_playing && !m_loading && m_reportedAt.isValid())
    position += m_reportedAt.elapsed() / 1000.0;
  return m_duration > 0.0 ? std::min(position, m_duration) : position;
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
  } else if (type == QStringLiteral("advanced")) {
    if (id.isEmpty() || id != m_prepared)
      return;
    m_id = id;
    m_prepared.clear();
    m_position = 0.0;
    m_duration = 0.0;
    m_playing = true;
    m_reportedAt.restart();
    emit advanced(id);
  } else if (type == QStringLiteral("error")) {
    // A failed preload only costs the gapless start.
    if (!id.isEmpty() && id != m_id) {
      if (id == m_prepared)
        m_prepared.clear();
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
  m_prepared.clear();
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
