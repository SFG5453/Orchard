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
#include <QByteArray>
#include <QJsonObject>
#include <QObject>
#include <QTimer>

class QProcess;

// One orchard-adaptive-mix process: a JSON request line in, a header line plus raw PCM out.
// Models load once per process, so the process outlives jobs until cancelled or crashed.
class AdaptiveMixWorker final : public QObject {
  Q_OBJECT
public:
  explicit AdaptiveMixWorker(QObject *parent = nullptr);
  ~AdaptiveMixWorker() override;
  // The worker binary ships next to the app; Connect advertises mixing only with it present.
  static bool installed();
  bool busy() const { return m_busy; }
  // One job at a time; the caller cancels a running one first.
  void start(const QJsonObject &request);
  // Kills the process; the next start spawns a fresh one.
  void cancel();
signals:
  // The worker's answer. With "error" it refused or could not analyse the pair and `pcm` is
  // empty; otherwise `pcm` holds exactly header["bytes"] bytes of interleaved stereo f32.
  void finished(const QJsonObject &header, const QByteArray &pcm);
  // The process broke: it would not start, crashed, timed out or spoke nonsense.
  void failed(const QString &message);
private:
  void fail(const QString &message);
  void receive();
  QProcess *m_process{nullptr};
  QByteArray m_stdout;
  QJsonObject m_header;
  // Byte count of the PCM body still being piped after the header; -1 awaits a header.
  qint64 m_pcmBytes{-1};
  QTimer m_timeout;
  bool m_busy{false};
};
