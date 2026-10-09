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

#include "slop_scan_worker.h"

#include <QAudioBuffer>
#include <QAudioDecoder>
#include <QFile>
#include <QUrl>

SlopScanWorker::SlopScanWorker(QObject *parent) : QObject(parent), m_timeout(this) {
  m_timeout.setSingleShot(true);
  m_timeout.setInterval(60000);
  connect(&m_timeout, &QTimer::timeout, this, &SlopScanWorker::finish);
}

SlopScanWorker::~SlopScanWorker() {
  delete m_detector;
  if (!m_path.isEmpty())
    QFile::remove(m_path);
  for (const auto &job : std::as_const(m_jobs))
    QFile::remove(job.second);
}

void SlopScanWorker::analyze(const QString &trackId, const QString &path) {
  m_jobs.append({trackId, path});
  if (!m_decoder)
    startNext();
}

void SlopScanWorker::startNext() {
  if (m_jobs.isEmpty())
    return;
  const auto job = m_jobs.takeFirst();
  m_trackId = job.first;
  m_path = job.second;
  m_decoder = new QAudioDecoder(this);
  QAudioFormat format;
  format.setSampleRate(48000);
  format.setChannelCount(2);
  format.setSampleFormat(QAudioFormat::Float);
  m_decoder->setAudioFormat(format);
  m_decoder->setSource(QUrl::fromLocalFile(m_path));
  connect(m_decoder, &QAudioDecoder::bufferReady, this, &SlopScanWorker::consume);
  connect(m_decoder, &QAudioDecoder::finished, this, [this] {
    consume();
    finish();
  });
  connect(m_decoder, qOverload<QAudioDecoder::Error>(&QAudioDecoder::error), this,
          &SlopScanWorker::finish);
  m_timeout.start();
  m_decoder->start();
}

void SlopScanWorker::consume() {
  while (m_decoder && m_decoder->bufferAvailable()) {
    const QAudioBuffer buffer = m_decoder->read();
    if (!buffer.isValid() || buffer.format().sampleFormat() != QAudioFormat::Float)
      continue;
    if (!m_detector)
      m_detector = SlopFingerprintDetector::create(
          buffer.format().sampleRate(), buffer.format().channelCount()).release();
    if (!m_detector) {
      finish();
      return;
    }
    m_detector->push(buffer.constData<float>(), static_cast<size_t>(buffer.frameCount()));
    // The model never looks past 300 s, so the rest of a long mix is wasted decoding.
    if (m_detector->isFull()) {
      finish();
      return;
    }
  }
}

void SlopScanWorker::finish() {
  if (!m_decoder)
    return;
  m_timeout.stop();
  m_decoder->disconnect(this);
  m_decoder->stop();
  m_decoder->deleteLater();
  m_decoder = nullptr;
  const auto verdict = m_detector ? m_detector->verdict() : std::nullopt;
  const bool ok = verdict.has_value();
  delete m_detector;
  m_detector = nullptr;
  QFile::remove(m_path);
  m_path.clear();
  emit analyzed(std::exchange(m_trackId, QString()), ok,
                verdict ? verdict->probability : 0.0f, verdict ? verdict->seconds : 0.0f);
  startNext();
}
