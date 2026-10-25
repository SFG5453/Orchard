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

#include "widevine_client.h"
#include "widevine_path.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QProcess>
#include <QtEndian>

#include <utility>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

using namespace widevine_protocol;

namespace {
// A paused song costs one license request on resume; an idle CDM costs about 80 MiB.
constexpr int kIdleMs = 2 * 60 * 1000;

QString hostPath() {
#ifdef Q_OS_WIN
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-widevine-host.exe"));
#else
  return QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-widevine-host"));
#endif
}
} // namespace

WidevineClient::WidevineClient(QObject *parent) : QObject(parent) {
  m_idle.setSingleShot(true);
  m_idle.setInterval(kIdleMs);
  connect(&m_idle, &QTimer::timeout, this, [this] {
    if (m_pending.isEmpty())
      shutdown();
  });
}

WidevineClient::~WidevineClient() {
  if (!m_process)
    return;
  m_process->disconnect(this);
  m_process->closeWriteChannel();
  if (!m_process->waitForFinished(1000))
    m_process->kill();
}

bool WidevineClient::available() {
#if defined(Q_OS_LINUX) || defined(Q_OS_WIN)
  return QFileInfo(hostPath()).isExecutable() && !findWidevineCdm().isEmpty();
#else
  return false;
#endif
}

bool WidevineClient::start() {
  if (m_process)
    return true;
  const QString cdm = findWidevineCdm();
  if (cdm.isEmpty())
    return false;
  m_buffer.clear();
  m_process = new QProcess(this);
  m_process->setProcessChannelMode(QProcess::ForwardedErrorChannel);
#ifdef Q_OS_WIN
  m_process->setCreateProcessArgumentsModifier(
      [](QProcess::CreateProcessArguments *args) { args->flags |= CREATE_NO_WINDOW; });
#endif
  connect(m_process, &QProcess::readyReadStandardOutput, this, &WidevineClient::readFrames);
  connect(m_process, &QProcess::finished, this, &WidevineClient::processEnded);
  connect(m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError error) {
    if (error == QProcess::FailedToStart)
      processEnded();
  });
  m_process->start(hostPath(), {cdm});
  return true;
}

void WidevineClient::request(Op op, quint32 stream, const QByteArray &body, Reply done) {
  if (!start()) {
    done({}, tr("Apple Music playback needs Widevine. Install Google Chrome or Chromium, then try again."));
    return;
  }
  const quint32 id = m_nextId++;
  m_pending.insert(id, std::move(done));
  m_idle.stop();
  write(op, id, stream, body);
}

void WidevineClient::close(quint32 stream) {
  if (m_process)
    write(Close, 0, stream, {});
}

void WidevineClient::write(Op op, quint32 id, quint32 stream, const QByteArray &body) {
  QByteArray frame(int(kHeaderBytes) + 4, Qt::Uninitialized);
  qToLittleEndian<quint32>(quint32(5 + 4 + body.size()), frame.data());
  qToLittleEndian<quint32>(id, frame.data() + 4);
  frame[8] = char(op);
  qToLittleEndian<quint32>(stream, frame.data() + 9);
  m_process->write(frame + body);
}

void WidevineClient::readFrames() {
  m_buffer.append(m_process->readAllStandardOutput());
  // Parse by offset and trim once: a segment's pictures arrive together, and trimming per
  // frame would shift megabytes on the UI thread for every one.
  qsizetype at = 0;
  while (m_buffer.size() - at >= qsizetype(kHeaderBytes)) {
    const char *frame = m_buffer.constData() + at;
    const quint32 length = qFromLittleEndian<quint32>(frame);
    if (length < 5 || length > kMaxFrameBytes) {
      shutdown();
      return;
    }
    if (m_buffer.size() - at < qsizetype(length) + 4)
      break;
    const quint32 id = qFromLittleEndian<quint32>(frame + 4);
    const auto op = Op(quint8(frame[8]));
    const char *body = frame + kHeaderBytes;
    const qsizetype size = qsizetype(length) - 5;
    at += qsizetype(length) + 4;
    if (op == Picture && size >= 16) {
      emit picture(qFromLittleEndian<quint32>(body), qFromLittleEndian<qint64>(body + 4),
                   qFromLittleEndian<quint16>(body + 12), qFromLittleEndian<quint16>(body + 14),
                   QByteArray(body + 16, size - 16));
      continue;
    }
    const QByteArray payload(body, size);
    if (op == Renewal && payload.size() >= 4) {
      emit renewal(qFromLittleEndian<quint32>(payload.constData()), payload.mid(4));
      continue;
    }
    Reply done = m_pending.take(id);
    if (done)
      done(op == Ok ? payload : QByteArray(), op == Ok ? QString() : QString::fromUtf8(payload));
    if (!m_process)
      return;
  }
  m_buffer.remove(0, at);
  if (m_pending.isEmpty())
    m_idle.start();
}

void WidevineClient::shutdown() {
  m_idle.stop();
  if (QProcess *process = std::exchange(m_process, nullptr)) {
    process->disconnect(this);
    connect(process, &QProcess::finished, process, &QObject::deleteLater);
    process->closeWriteChannel();
    QTimer::singleShot(3000, process, [process] { process->kill(); });
  }
  ++m_generation;
  failPending(tr("Apple Music's decryption stopped."));
}

void WidevineClient::processEnded() {
  if (!m_process)
    return;
  m_process->disconnect(this);
  m_process->deleteLater();
  m_process = nullptr;
  m_idle.stop();
  ++m_generation;
  failPending(tr("Apple Music's decryption stopped unexpectedly."));
}

void WidevineClient::failPending(const QString &error) {
  for (const Reply &done : std::exchange(m_pending, {}))
    done({}, error);
}
