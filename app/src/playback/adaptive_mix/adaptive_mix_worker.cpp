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
#include "adaptive_mix_worker.h"
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QProcess>
#include <utility>

namespace {
// A 30 s overlap at 192 kHz is 46 MB; anything past this is a confused worker.
constexpr qint64 kMaxPcmBytes = 64ll * 1024 * 1024;

QString program() {
  QString path = QDir(QCoreApplication::applicationDirPath()).filePath("orchard-adaptive-mix");
#ifdef Q_OS_WIN
  path += ".exe";
#endif
  return path;
}
} // namespace

bool AdaptiveMixWorker::installed() { return QFile::exists(program()); }

AdaptiveMixWorker::AdaptiveMixWorker(QObject *parent) : QObject(parent) {
  m_timeout.setSingleShot(true);
  m_timeout.setInterval(90000);
  connect(&m_timeout, &QTimer::timeout, this,
          [this] { fail(tr("Adaptive mix preparation timed out.")); });
}
AdaptiveMixWorker::~AdaptiveMixWorker() { cancel(); }

void AdaptiveMixWorker::cancel() {
  m_busy = false;
  m_timeout.stop();
  m_stdout.clear();
  m_header = {};
  m_pcmBytes = -1;
  if (!m_process)
    return;
  disconnect(m_process, nullptr, this, nullptr);
  m_process->kill();
  // Only cancellation waits, with a strict bound; analysis never blocks Qt.
  m_process->waitForFinished(1000);
  m_process->deleteLater();
  m_process = nullptr;
}

void AdaptiveMixWorker::fail(const QString &message) {
  cancel();
  emit failed(message);
}

void AdaptiveMixWorker::start(const QJsonObject &request) {
  m_stdout.clear();
  m_header = {};
  m_pcmBytes = -1;
  m_busy = true;
  if (!m_process) {
    m_process = new QProcess(this);
    connect(m_process, &QProcess::readyReadStandardOutput, this, &AdaptiveMixWorker::receive);
    // Keep diagnostics out of memory even when a driver discovers verbosity.
    m_process->setStandardErrorFile(QProcess::nullDevice());
    connect(m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError) {
      if (m_busy)
        fail(tr("The GPU analysis worker could not run."));
    });
    connect(m_process, &QProcess::finished, this, [this](int, QProcess::ExitStatus) {
      if (m_busy)
        fail(tr("The GPU analysis worker stopped."));
      else
        cancel(); // Idle crash: the next job starts a fresh worker.
    });
    const QString models = qEnvironmentVariable(
        "ORCHARD_MODELS_DIR", QDir(QCoreApplication::applicationDirPath()).filePath("models"));
    m_process->start(program(), {models});
    // Windows reports FailedToStart synchronously; fail() has already nulled the process.
    // Writing to a process that never existed is a bold strategy. It did not pay off.
    if (!m_process)
      return;
  }
  m_process->write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
  m_timeout.start();
}

void AdaptiveMixWorker::receive() {
  m_stdout += m_process->readAllStandardOutput();
  if (!m_busy)
    return;
  if (m_pcmBytes < 0) {
    const auto newline = m_stdout.indexOf('\n');
    if (newline < 0) {
      if (m_stdout.size() > 65536)
        fail(tr("Invalid analysis response."));
      return;
    }
    m_header = QJsonDocument::fromJson(m_stdout.left(newline)).object();
    m_stdout.remove(0, newline + 1);
    if (m_header.contains("error")) {
      // An answer, not a crash: the process stays up with its models loaded.
      m_busy = false;
      m_timeout.stop();
      emit finished(std::exchange(m_header, {}), {});
      return;
    }
    const qint64 bytes = m_header.value("bytes").toInteger(-1);
    if (bytes < 0 || bytes % 8 != 0 || bytes > kMaxPcmBytes) {
      fail(tr("Invalid rendered overlap."));
      return;
    }
    m_pcmBytes = bytes;
    m_stdout.reserve(m_pcmBytes);
  }
  if (m_stdout.size() < m_pcmBytes)
    return;
  if (m_stdout.size() > m_pcmBytes) {
    fail(tr("Invalid analysis response."));
    return;
  }
  m_busy = false;
  m_timeout.stop();
  m_pcmBytes = -1;
  emit finished(std::exchange(m_header, {}), std::exchange(m_stdout, {}));
}
