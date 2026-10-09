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
#include "connect_mix.h"

#include <QDebug>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTimer>
#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

// A phone that uploads and never asks (or asks and never uploads) gets its memory back.
constexpr int kJobTtlMs = 180000;
// Four pairs waiting is a busy DJ booth; a fifth is a phone that forgot it already asked.
constexpr int kMaxJobs = 4;

// Only the planner's own inputs cross into the worker; a peer never picks the job kind.
QJsonObject plannerRequest(const QJsonObject &request) {
  QJsonObject clean;
  for (const char *key : {"outgoingDuration", "incomingDuration", "position", "fadeSeconds"}) {
    const double value = request.value(QLatin1String(key)).toDouble(NAN);
    if (std::isfinite(value))
      clean.insert(QLatin1String(key), value);
  }
  clean.insert(QStringLiteral("albumSequential"), request.value(QStringLiteral("albumSequential")).toBool());
  for (const char *key : {"currentTrack", "nextTrack"}) {
    const QJsonObject track = request.value(QLatin1String(key)).toObject();
    if (QJsonDocument(track).toJson(QJsonDocument::Compact).size() <= 4096)
      clean.insert(QLatin1String(key), track);
  }
  return clean;
}

// Matches the Android mixer's own conversion, so a remote render plays bit for bit like a local one.
QByteArray toPcm16(const QByteArray &f32) {
  const qsizetype count = f32.size() / 4;
  QByteArray out(count * 2, Qt::Uninitialized);
  auto *dst = reinterpret_cast<qint16 *>(out.data());
  for (qsizetype i = 0; i < count; ++i) {
    float sample;
    std::memcpy(&sample, f32.constData() + i * 4, 4);
    dst[i] = static_cast<qint16>(std::clamp(sample, -1.0f, 1.0f) * 32767.0f);
  }
  return out;
}

} // namespace

ConnectMixHost::ConnectMixHost(Respond respond, SendStream send, QObject *parent)
    : QObject(parent), m_respond(std::move(respond)), m_send(std::move(send)) {
  const auto reader = [this](bool outgoing) {
    return [this, outgoing](const QJsonObject &, qint64 start, qint64 end,
                            std::function<void(const QByteArray &, int)> done) {
      const auto it = m_jobs.constFind(m_running);
      if (it == m_jobs.constEnd()) {
        done({}, 410);
        return;
      }
      done((outgoing ? it->outgoing : it->incoming).mid(start, end - start + 1), 206);
    };
  };
  m_outgoingProxy.setRangeReader(reader(true));
  m_incomingProxy.setRangeReader(reader(false));
  connect(&m_worker, &AdaptiveMixWorker::finished, this, &ConnectMixHost::finish);
  connect(&m_worker, &AdaptiveMixWorker::failed, this, [this](const QString &message) { abandon(message); });
}

ConnectMixHost::Job &ConnectMixHost::job(const QString &mixId, const QString &sessionId) {
  auto it = m_jobs.find(mixId);
  if (it != m_jobs.end())
    return *it;
  Job fresh;
  fresh.sessionId = sessionId;
  fresh.serial = ++m_serial;
  QTimer::singleShot(kJobTtlMs, this, [this, mixId, serial = fresh.serial] {
    const auto found = m_jobs.constFind(mixId);
    if (found == m_jobs.constEnd() || found->serial != serial || m_running == mixId)
      return;
    if (!found->rpcId.isEmpty())
      m_respond(found->rpcId, false, {}, QStringLiteral("timeout"));
    m_jobs.erase(found);
  });
  return *m_jobs.insert(mixId, fresh);
}

void ConnectMixHost::acceptSource(const QString &sessionId, const QJsonObject &header, const QByteArray &bytes) {
  const QJsonObject meta = header.value(QStringLiteral("meta")).toObject();
  const QString mixId = meta.value(QStringLiteral("mix")).toString().left(64);
  const QString role = meta.value(QStringLiteral("role")).toString();
  if (mixId.isEmpty() || (role != QStringLiteral("outgoing") && role != QStringLiteral("incoming")))
    return;
  if (!m_jobs.contains(mixId) && m_jobs.size() >= kMaxJobs)
    return; // The target times out and mixes for itself.
  Job &entry = job(mixId, sessionId);
  if (entry.sessionId != sessionId)
    return;
  (role == QStringLiteral("outgoing") ? entry.outgoing : entry.incoming) = bytes;
  startNext();
}

void ConnectMixHost::prepare(const QString &id, const QString &sessionId, const QJsonObject &params) {
  const QString mixId = params.value(QStringLiteral("mix")).toString().left(64);
  if (mixId.isEmpty() || (!m_jobs.contains(mixId) && m_jobs.size() >= kMaxJobs)) {
    m_respond(id, false, {}, mixId.isEmpty() ? QStringLiteral("invalid_request") : QStringLiteral("busy"));
    return;
  }
  Job &entry = job(mixId, sessionId);
  if (entry.sessionId != sessionId || !entry.rpcId.isEmpty()) {
    m_respond(id, false, {}, QStringLiteral("invalid_request"));
    return;
  }
  entry.rpcId = id;
  entry.request = plannerRequest(params.value(QStringLiteral("request")).toObject());
  for (const auto &codec : params.value(QStringLiteral("codecs")).toArray()) {
    if (entry.codecs.size() < 8)
      entry.codecs << codec.toString().left(16);
  }
  startNext();
}

void ConnectMixHost::dropSession(const QString &sessionId) {
  for (auto it = m_jobs.begin(); it != m_jobs.end();) {
    if (it->sessionId != sessionId) {
      ++it;
      continue;
    }
    if (it.key() == m_running) {
      m_worker.cancel();
      m_outgoingProxy.clear();
      m_incomingProxy.clear();
      m_running.clear();
    }
    it = m_jobs.erase(it);
  }
  startNext();
}

void ConnectMixHost::startNext() {
  if (!m_running.isEmpty())
    return;
  for (auto it = m_jobs.cbegin(); it != m_jobs.cend(); ++it) {
    if (it->rpcId.isEmpty() || it->outgoing.isEmpty() || it->incoming.isEmpty())
      continue;
    m_running = it.key();
    const auto source = [](qsizetype size) {
      return QJsonObject{{QStringLiteral("provider"), QStringLiteral("connect")},
                         {QStringLiteral("contentLength"), double(size)},
                         {QStringLiteral("mimeType"), QStringLiteral("application/octet-stream")}};
    };
    QJsonObject request = it->request;
    request.insert(QStringLiteral("outgoingUrl"), m_outgoingProxy.open(source(it->outgoing.size())).toString());
    request.insert(QStringLiteral("incomingUrl"), m_incomingProxy.open(source(it->incoming.size())).toString());
    // The target splices into its own player, so the render arrives at that player's rate.
    request.insert(QStringLiteral("sourceRate"), true);
    m_worker.start(request);
    return;
  }
}

void ConnectMixHost::finish(const QJsonObject &header, const QByteArray &pcm) {
  const auto it = m_jobs.find(m_running);
  m_outgoingProxy.clear();
  m_incomingProxy.clear();
  m_running.clear();
  if (it == m_jobs.end()) {
    startNext();
    return;
  }
  const QString mixId = it.key();
  const Job done = *it;
  m_jobs.erase(it);
  if (header.contains(QStringLiteral("error"))) {
    // The worker answered; a refusal or analysis error is the plan's result, not a broken host.
    m_respond(done.rpcId, true, QJsonObject{{QStringLiteral("error"), header.value(QStringLiteral("error"))}}, {});
    startNext();
    return;
  }
  const double start = header.value(QStringLiteral("outgoingStart")).toDouble(-1);
  const int rate = header.value(QStringLiteral("rate")).toInt();
  // The target lists the codecs it takes in preference order.
  QString codec = QStringLiteral("pcm_f32");
  for (const QString &wanted : done.codecs) {
    if (wanted == QStringLiteral("pcm_s16") || wanted == QStringLiteral("pcm_f32")) {
      codec = wanted;
      break;
    }
  }
  const bool s16 = codec == QStringLiteral("pcm_s16");
  QString stream;
  if (std::isfinite(start) && start >= 0 && rate > 0 && !pcm.isEmpty()) {
    const QJsonObject meta{{QStringLiteral("codec"), codec},
                           {QStringLiteral("sample_rate"), rate},
                           {QStringLiteral("channels"), 2},
                           {QStringLiteral("timestamp"), start},
                           {QStringLiteral("meta"), QJsonObject{{QStringLiteral("mix"), mixId}}}};
    stream = m_send(done.sessionId, meta, s16 ? toPcm16(pcm) : pcm);
  }
  if (stream.isEmpty()) {
    m_respond(done.rpcId, false, {}, QStringLiteral("mix_failed"));
  } else {
    QJsonObject plan = header;
    plan.remove(QStringLiteral("bytes"));
    plan.insert(QStringLiteral("stream"), stream);
    plan.insert(QStringLiteral("codec"), codec);
    qInfo().noquote() << "AdaptiveMix: remote plan | strategy" << plan.value(QStringLiteral("strategy")).toString()
                      << "| out" << start << "| dur" << plan.value(QStringLiteral("duration")).toDouble() << "| rate"
                      << rate;
    m_respond(done.rpcId, true, plan, {});
  }
  startNext();
}

void ConnectMixHost::abandon(const QString &error) {
  qInfo().noquote() << "AdaptiveMix: remote mix failed |" << error;
  const auto it = m_jobs.find(m_running);
  m_outgoingProxy.clear();
  m_incomingProxy.clear();
  m_running.clear();
  if (it != m_jobs.end()) {
    // The host broke, so the target mixes for itself.
    m_respond(it->rpcId, false, {}, QStringLiteral("mix_failed"));
    m_jobs.erase(it);
  }
  startNext();
}
