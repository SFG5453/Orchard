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

#include "widevine_host/widevine_protocol.h"

#include <QByteArray>
#include <QHash>
#include <QObject>
#include <QTimer>
#include <functional>

class QProcess;

// Talks to orchard-widevine-host, which holds the CDM. The host starts on the first
// request and ends after a quiet spell; its sessions end with it, so callers compare
// generation() with the one they licensed under and license again when it moved.
class WidevineClient final : public QObject {
  Q_OBJECT

public:
  using Reply = std::function<void(const QByteArray &data, const QString &error)>;

  explicit WidevineClient(QObject *parent = nullptr);
  ~WidevineClient() override;

  // The host binary sits next to Orchard and a browser's CDM is installed.
  static bool available();

  quint64 generation() const { return m_generation; }
  void request(widevine_protocol::Op op, quint32 stream, const QByteArray &body, Reply done);
  void close(quint32 stream);
  void shutdown();

signals:
  // A session's license needs renewing; post `message` and answer with License.
  void renewal(quint32 stream, const QByteArray &message);
  // A decoded video frame, I420 without padding, before its VideoFragment request answers.
  void picture(quint32 stream, qint64 timeUs, int width, int height, const QByteArray &pixels);

private:
  bool start();
  void write(widevine_protocol::Op op, quint32 id, quint32 stream, const QByteArray &body);
  void readFrames();
  void processEnded();
  void failPending(const QString &error);

  QProcess *m_process{nullptr};
  QByteArray m_buffer;
  quint32 m_nextId{1};
  QHash<quint32, Reply> m_pending;
  quint64 m_generation{0};
  QTimer m_idle;
};
