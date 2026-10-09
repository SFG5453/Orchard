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

#include "slop_fingerprint.h"

#include <QList>
#include <QObject>
#include <QPair>
#include <QTimer>

class QAudioDecoder;

// Decodes downloaded songs and scores them. Lives on its own thread so whole-song
// analysis never stalls the UI; one song at a time keeps it to a single core.
class SlopScanWorker final : public QObject {
  Q_OBJECT
public:
  explicit SlopScanWorker(QObject *parent = nullptr);
  ~SlopScanWorker() override;
public slots:
  // Deletes `path` once analysed.
  void analyze(const QString &trackId, const QString &path);
signals:
  // ok is false when the file could not be decoded or held under 10 s of audio.
  void analyzed(const QString &trackId, bool ok, float probability, float seconds);

private:
  void startNext();
  void consume();
  void finish();
  QList<QPair<QString, QString>> m_jobs;
  QString m_trackId;
  QString m_path;
  QAudioDecoder *m_decoder{nullptr};
  SlopFingerprintDetector *m_detector{nullptr};
  QTimer m_timeout;
};
