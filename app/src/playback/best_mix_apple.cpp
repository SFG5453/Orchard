/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#include "best_mix_controller.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"
#include "playback_helpers.h"
#include <QJsonDocument>
#include <QProcess>
#include <QTimer>
#include <memory>

void BestMixController::setAppleMusic(AppleMusic *account, CatalogSource *source) {
  m_appleAccount = account;
  m_catalogSource = source;
}

QString BestMixController::appleId(const QVariantMap &track) const {
  if (!m_appleAccount || !m_catalogSource || !m_catalogSource->apple() || !m_appleAccount->connected() ||
      !isAppleTrack(track))
    return {};
  const QString id = track.value(QStringLiteral("appleId")).toString();
  if (!id.isEmpty())
    return id;
  const QString browseId = track.value(QStringLiteral("id")).toString();
  const QString prefix = QStringLiteral("am.songs.");
  return browseId.startsWith(prefix) ? browseId.mid(prefix.size()) : QString();
}

void BestMixController::startApple() {
  if (m_applePending.isEmpty())
    return;
  QStringList ids;
  for (const QVariantMap &track : std::as_const(m_applePending))
    ids.append(appleId(track));
  const quint64 generation = ++m_appleGeneration;
  m_appleAccount->audioAnalysis(ids, [this, generation](const QJsonObject &page, const QString &) {
    if (generation == m_appleGeneration && m_busy)
      analyzeApple(page.value(QStringLiteral("items")).toObject());
  });
}

void BestMixController::analyzeApple(const QJsonObject &items) {
  QByteArray payload;
  const QList<QVariantMap> pending = std::exchange(m_applePending, {});
  for (const QVariantMap &track : pending) {
    const QJsonObject analysis = items.value(appleId(track)).toObject();
    if (analysis.isEmpty()) {
      finishApple(track, {});
      continue;
    }
    const QJsonObject request{{"kind", "bestMixApple"}, {"analysis", analysis}, {"duration", seconds(track)}};
    payload += QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n';
    m_appleSongs.append(track);
  }
  if (m_appleSongs.isEmpty()) {
    pump();
    return;
  }
  // One worker answers every song in order; each answer is a little JSON, no audio.
  QProcess *worker = new QProcess(this);
  m_appleWorker = worker;
  worker->setStandardErrorFile(QProcess::nullDevice());
  auto output = std::make_shared<QByteArray>();
  const quint64 generation = m_appleGeneration;
  auto done = [this, generation] {
    if (generation != m_appleGeneration)
      return;
    for (const QVariantMap &track : std::exchange(m_appleSongs, {}))
      finishApple(track, {});
    stopApple();
    pump();
  };
  connect(worker, &QProcess::started, this, [worker, payload] { worker->write(payload); });
  connect(worker, &QProcess::readyReadStandardOutput, this, [this, worker, output, done] {
    *output += worker->readAllStandardOutput();
    int end = 0;
    while ((end = output->indexOf('\n')) >= 0 && !m_appleSongs.isEmpty()) {
      const QJsonObject value = QJsonDocument::fromJson(output->left(end)).object();
      output->remove(0, end + 1);
      finishApple(m_appleSongs.takeFirst(), value.contains("head") && value.contains("tail") ? value : QJsonObject{});
    }
    if (m_appleSongs.isEmpty() || output->size() > 512 * 1024)
      done();
  });
  connect(worker, &QProcess::errorOccurred, this, done);
  connect(worker, &QProcess::finished, this, done);
  worker->start(workerPath(), {});
  QTimer::singleShot(60000, this, [guard = QPointer<QProcess>(worker), done] {
    if (guard)
      done();
  });
}

void BestMixController::finishApple(const QVariantMap &track, const QJsonObject &features) {
  const QString id = track.value(QStringLiteral("id")).toString();
  if (!features.isEmpty() && storeFeatures(id, seconds(track), features))
    m_available.insert(id);
  else if (m_error.isEmpty())
    m_error = tr("Some songs could not be analyzed and will keep their queue position.");
  ++m_completed;
  emit changed();
}

void BestMixController::stopApple() {
  ++m_appleGeneration;
  m_applePending.clear();
  m_appleSongs.clear();
  if (QProcess *worker = m_appleWorker) {
    m_appleWorker = nullptr;
    worker->disconnect(this);
    worker->kill();
    worker->deleteLater();
  }
}
