/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "native_apple_player.h"

#include <QPointer>
#include <QtGlobal>

#include <memory>

#ifdef Q_OS_MACOS
#include "musickit/musickit_bridge.h"
#endif

namespace {
// MusicKit reports nothing when a song ends, so the poll watches for it.
constexpr int kPollMs = 200;
// A paused player this close to the end has run out of song.
constexpr double kEndSlackSeconds = 0.5;

#ifdef Q_OS_MACOS
struct PendingLoad {
  QPointer<NativeApplePlayer> player;
  quint64 request;
  void (NativeApplePlayer::*finish)(quint64, bool, const QString &);
};
#endif
} // namespace

NativeApplePlayer::NativeApplePlayer(QObject *parent) : QObject(parent) {
  m_poll.setInterval(kPollMs);
  connect(&m_poll, &QTimer::timeout, this, &NativeApplePlayer::poll);
}

NativeApplePlayer::~NativeApplePlayer() { stop(); }

bool NativeApplePlayer::available() {
#ifdef Q_OS_MACOS
  return orchard_musickit_available() != 0;
#else
  return false;
#endif
}

double NativeApplePlayer::position() const { return m_loaded || m_played ? m_position : m_startPosition; }

void NativeApplePlayer::start(const QString &songId, double position, bool paused) {
  stop();
  m_active = true;
  m_startPosition = qMax(0.0, position);
  const quint64 request = ++m_request;
#ifdef Q_OS_MACOS
  auto *pending = new PendingLoad{this, request, &NativeApplePlayer::finishLoad};
  orchard_musickit_play(songId.toUtf8().constData(), m_startPosition, paused ? 1 : 0, pending,
                        [](void *context, int32_t ok, const char *error) {
                          std::unique_ptr<PendingLoad> load(static_cast<PendingLoad *>(context));
                          if (load->player)
                            (load->player.data()->*load->finish)(load->request, ok != 0,
                                                                QString::fromUtf8(error ? error : ""));
                        });
#else
  Q_UNUSED(songId)
  Q_UNUSED(paused)
  finishLoad(request, false, tr("Apple Music lossless plays only on macOS."));
#endif
}

void NativeApplePlayer::finishLoad(quint64 request, bool ok, const QString &error) {
  // A superseded load reports an empty error; the newer request speaks for itself.
  if (request != m_request || (!ok && error.isEmpty()))
    return;
  if (!ok) {
    m_active = false;
    emit failed(error);
    return;
  }
  m_loaded = true;
  m_position = m_startPosition;
  m_poll.start();
  poll();
  emit started();
}

void NativeApplePlayer::resume() {
  if (!m_loaded)
    return;
#ifdef Q_OS_MACOS
  orchard_musickit_resume();
#endif
}

void NativeApplePlayer::pause() {
  if (!m_loaded)
    return;
#ifdef Q_OS_MACOS
  orchard_musickit_pause();
#endif
  poll();
}

void NativeApplePlayer::seek(double seconds) {
  if (!m_loaded)
    return;
  m_position = m_duration > 0.0 ? qBound(0.0, seconds, m_duration) : qMax(0.0, seconds);
#ifdef Q_OS_MACOS
  orchard_musickit_seek(m_position);
#endif
  emit changed();
}

void NativeApplePlayer::stop() {
  ++m_request;
  m_poll.stop();
#ifdef Q_OS_MACOS
  if (m_active)
    orchard_musickit_stop();
#endif
  m_active = false;
  m_loaded = false;
  m_played = false;
  m_status = Stopped;
  m_startPosition = m_position = m_duration = 0.0;
  m_variant = Variant::Unknown;
}

void NativeApplePlayer::poll() {
#ifdef Q_OS_MACOS
  if (!m_loaded)
    return;
  const int status = orchard_musickit_status();
  const bool hasEntry = orchard_musickit_has_entry() != 0;
  const auto variant = static_cast<Variant>(qBound(0, int(orchard_musickit_variant()), int(Variant::Spatial)));
  m_position = orchard_musickit_position();
  m_duration = orchard_musickit_duration();
  const bool moved = status != m_status;
  m_status = status;
  if (variant != m_variant) {
    m_variant = variant;
    emit variantChanged();
  }
  if (status == Playing)
    m_played = true;
  const bool atEnd = m_duration > 0.0 && m_position >= m_duration - kEndSlackSeconds;
  if (m_played && status != Playing && (status == Stopped || !hasEntry || atEnd)) {
    m_loaded = false;
    m_poll.stop();
    m_position = m_duration;
    emit changed();
    emit ended();
    return;
  }
  if (moved || status == Playing)
    emit changed();
#endif
}

NativeApplePlayer *activeNativeApple(const QObject *owner) {
  auto *player = owner->findChild<NativeApplePlayer *>(QString(), Qt::FindDirectChildrenOnly);
  return player && player->active() ? player : nullptr;
}

void stopNativeApple(const QObject *owner) {
  if (auto *player = activeNativeApple(owner))
    player->stop();
}
