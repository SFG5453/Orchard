/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <QObject>
#include <QString>
#include <QTimer>

// Apple Music songs played by macOS through MusicKit, which is how Apple serves
// lossless. Audio goes straight to the system output, so Orchard's audio engine,
// crossfades and volume never see it. Only exists on macOS inside an app bundle.
class NativeApplePlayer final : public QObject {
  Q_OBJECT

public:
  enum class Variant { Unknown, Lossy, Lossless, HiResLossless, Atmos, DolbyAudio, Spatial };

  explicit NativeApplePlayer(QObject *parent = nullptr);
  ~NativeApplePlayer() override;

  static bool available();
  // Owns playback from start() until stop(), loading included.
  bool active() const { return m_active; }
  bool loaded() const { return m_loaded; }
  bool playing() const { return m_status == Playing; }
  double position() const;
  double duration() const { return m_duration; }
  Variant variant() const { return m_variant; }

  // Orchard's origin-free developer token; MusicKit's own is used until one is set.
  void setDeveloperToken(const QString &token);
  // Replaces whatever plays; started() or failed() follows unless stop() comes first.
  void start(const QString &songId, double position, bool paused);
  void resume();
  void pause();
  void seek(double seconds);
  void stop();

signals:
  void started();
  void failed(const QString &message);
  // Status or position moved. Fires on every poll while playing.
  void changed();
  // What macOS streams became known or changed, e.g. lossless to Atmos.
  void variantChanged();
  void ended();

private:
  enum Status { Stopped, Playing, Paused, Interrupted };
  void finishLoad(quint64 request, bool ok, const QString &error);
  void poll();

  QTimer m_poll;
  quint64 m_request{0};
  bool m_active{false};
  bool m_loaded{false};
  bool m_played{false};
  int m_status{Stopped};
  double m_startPosition{0.0};
  double m_position{0.0};
  double m_duration{0.0};
  Variant m_variant{Variant::Unknown};
};

// The owner's native player while it owns playback, else null.
NativeApplePlayer *activeNativeApple(const QObject *owner);
// Cancels native playback, including a load still in flight.
void stopNativeApple(const QObject *owner);
