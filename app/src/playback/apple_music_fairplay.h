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
#pragma once

#include <QMetaObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>

#include <memory>

class AppleMusic;
class PlaybackController;
class QQuickItem;
class QQuickWindow;

// An Apple Music video's picture on macOS, played muted by AVFoundation with FairPlay
// keys and timed by the audio engine, which plays the video's soundtrack. FairPlay
// gets Apple's 1080p and 4K renditions that software Widevine is refused.
//
// Protected frames never reach Qt: the picture is a Core Animation layer under the
// window's Qt view, showing through where the surface item punches a transparent hole.
class AppleMusicFairPlay final : public QObject {
  Q_OBJECT

public:
  AppleMusicFairPlay(AppleMusic *account, PlaybackController *playback, QObject *parent = nullptr);
  ~AppleMusicFairPlay() override;

  // `maxHeight` 0 lets AVFoundation pick any rendition.
  void start(const QString &appleId, int maxHeight);
  void stop();
  // The item whose scene rect the picture fills, or null to hide it.
  void setSurface(QQuickItem *item);
  // Heights the video offers, tallest first, and the one on screen.
  QVariantList heights() const { return m_heights; }
  int height() const { return m_height; }

signals:
  void started();
  void failed(const QString &message);
  // The rendition on screen changed.
  void heightChanged();

private:
  struct Native;

  void requestPlayback(quint64 session, const QString &token);
  void loadCertificate(quint64 session, const QString &token, const QUrl &master, const QUrl &certificate,
                       const QUrl &license);
  void play(quint64 session, const QString &token, const QUrl &master, const QByteArray &certificate,
            const QUrl &license);
  // Posts an SPC from the key session's queue; `done` gets the CKC or an empty array.
  void license(quint64 session, const QByteArray &spc, const QString &keyUri,
               std::function<void(const QByteArray &)> done);
  void sync();
  void place();
  void watchWindow(QQuickWindow *window);
  void fail(quint64 session, const QString &error, const QString &message = {});

  AppleMusic *m_account;
  PlaybackController *m_playback;
  QNetworkAccessManager m_network;
  QTimer m_syncTimer;
  QPointer<QQuickItem> m_surface;
  QPointer<QQuickWindow> m_window;
  QMetaObject::Connection m_frameConnection;
  std::unique_ptr<Native> m_native;
  QString m_appleId;
  QString m_token;
  QUrl m_licenseUrl;
  int m_maxHeight{0};
  QVariantList m_heights;
  int m_height{0};
  bool m_ready{false};
  // Bumped by start() and stop(); late replies of an older session are dropped.
  quint64 m_session{0};
};
