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

#include "translation_packs.h"
#include "translation/lyric_language.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSaveFile>
#include <QStandardPaths>
#include <algorithm>
#include <iterator>
#include <utility>

namespace {
constexpr int kTimeoutMs = 30000;
const QString kMarker = QStringLiteral("pack-revision");

qint64 packBytes(const TranslationPack &pack) {
  qint64 total = 0;
  for (const auto &file : pack.files)
    total += file.size;
  return total;
}
} // namespace

TranslationPacks::TranslationPacks(QNetworkAccessManager *network, QObject *parent)
    : QObject(parent), m_network(network), m_root(rootDirectory()) {}

QString TranslationPacks::rootDirectory() {
  const QString override = qEnvironmentVariable("ORCHARD_TRANSLATION_DIR");
  return override.isEmpty() ? QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) +
                                  QStringLiteral("/translation")
                            : override;
}

const TranslationPack *TranslationPacks::find(const QString &id) {
  for (const TranslationPack &pack : translationPackTable())
    if (id == QLatin1String(pack.id))
      return &pack;
  return nullptr;
}

const TranslationPack *TranslationPacks::resolve(const QString &source, const QString &quality) {
  const TranslationPack *fallback = nullptr;
  for (const TranslationPack &pack : translationPackTable()) {
    if (source != QLatin1String(pack.source))
      continue;
    if (quality == QLatin1String(pack.quality))
      return &pack;
    if (QLatin1String(pack.quality) == QLatin1String("standard"))
      fallback = &pack;
  }
  return fallback;
}

bool TranslationPacks::available(const QString &id) const {
  const TranslationPack *pack = find(id);
  return pack && (*pack->baseUrl || installed(id));
}

QString TranslationPacks::directory(const QString &id) const { return m_root + QLatin1Char('/') + id; }

QString TranslationPacks::stagingDirectory() const { return directory(m_id) + QStringLiteral(".partial"); }

// Hashes were checked when the pack landed; a launch only checks sizes and the revision marker.
bool TranslationPacks::installed(const QString &id) const {
  const TranslationPack *pack = find(id);
  if (!pack)
    return false;
  const QDir dir(directory(id));
  QFile marker(dir.filePath(kMarker));
  if (!marker.open(QIODevice::ReadOnly) || marker.readAll().trimmed() != pack->revision)
    return false;
  for (const auto &file : pack->files)
    if (QFileInfo(dir.filePath(QLatin1String(file.name))).size() != file.size)
      return false;
  return true;
}

void TranslationPacks::ensure(const QString &id) {
  if (installed(id)) {
    emit ready(id);
    return;
  }
  const TranslationPack *pack = find(id);
  if (!pack || !*pack->baseUrl) {
    const QString language = pack ? QString::fromStdString(lyric_language::displayName(pack->source)) : id;
    emit failed(id, tr("%1 can't be translated yet.").arg(language));
    return;
  }
  if (m_id == id)
    return;
  cancel();
  m_id = id;
  m_fileIndex = 0;
  m_doneBytes = 0;
  m_totalBytes = packBytes(*pack);
  QDir(stagingDirectory()).removeRecursively();
  if (!QDir().mkpath(stagingDirectory())) {
    fail(tr("Couldn't create the translation folder."));
    return;
  }
  emit progress(m_id, 0);
  fetchNext();
}

void TranslationPacks::cancel() {
  if (m_id.isEmpty())
    return;
  if (m_reply) {
    m_reply->disconnect(this);
    m_reply->abort();
    m_reply->deleteLater();
  }
  delete m_file;
  m_file = nullptr;
  QDir(stagingDirectory()).removeRecursively();
  m_id.clear();
}

void TranslationPacks::fetchNext() {
  const TranslationPack *pack = find(m_id);
  const TranslationPackFile &file = pack->files[m_fileIndex];
  m_hash.reset();
  m_file = new QSaveFile(QDir(stagingDirectory()).filePath(QLatin1String(file.name)), this);
  if (!m_file->open(QIODevice::WriteOnly)) {
    fail(tr("Couldn't save the translation model."));
    return;
  }
  QNetworkRequest request(QUrl(QString::fromLatin1(pack->baseUrl) + QLatin1String(file.name)));
  request.setTransferTimeout(kTimeoutMs);
  request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
  m_reply = m_network->get(request);
  connect(m_reply, &QNetworkReply::readyRead, this, &TranslationPacks::consume);
  connect(m_reply, &QNetworkReply::finished, this, &TranslationPacks::finishFile);
}

bool TranslationPacks::consume() {
  if (!m_reply || !m_file)
    return false;
  const QByteArray bytes = m_reply->readAll();
  m_hash.addData(bytes);
  const qint64 limit = find(m_id)->files[m_fileIndex].size;
  if (m_file->pos() + bytes.size() > limit || m_file->write(bytes) != bytes.size()) {
    fail(tr("The translation model download was corrupted."));
    return false;
  }
  emit progress(m_id, double(m_doneBytes + m_file->pos()) / double(std::max<qint64>(1, m_totalBytes)));
  return true;
}

void TranslationPacks::finishFile() {
  if (!m_reply || m_id.isEmpty())
    return;
  if (m_reply->error() != QNetworkReply::NoError) {
    fail(tr("Couldn't download the translation model. Check your connection."));
    return;
  }
  if (!consume())
    return;
  m_reply->deleteLater();
  m_reply = nullptr;
  const TranslationPack *pack = find(m_id);
  const TranslationPackFile &file = pack->files[m_fileIndex];
  if (m_file->pos() != file.size || m_hash.result().toHex() != QByteArray(file.sha256) || !m_file->commit()) {
    fail(tr("The translation model download was corrupted."));
    return;
  }
  delete m_file;
  m_file = nullptr;
  m_doneBytes += file.size;
  if (++m_fileIndex < int(std::size(pack->files))) {
    fetchNext();
    return;
  }

  // The marker goes in last, so only a fully verified folder ever counts as installed.
  QSaveFile marker(QDir(stagingDirectory()).filePath(kMarker));
  const bool marked = marker.open(QIODevice::WriteOnly) && marker.write(pack->revision) >= 0 && marker.commit();
  const QString finalDir = directory(m_id);
  QDir(finalDir).removeRecursively();
  if (!marked || !QDir().rename(stagingDirectory(), finalDir)) {
    fail(tr("Couldn't save the translation model."));
    return;
  }
  const QString id = std::exchange(m_id, QString());
  emit changed();
  emit ready(id);
}

void TranslationPacks::fail(const QString &message) {
  const QString id = m_id;
  cancel();
  emit failed(id, message);
}

void TranslationPacks::remove(const QString &id) {
  if (m_id == id)
    cancel();
  if (find(id))
    QDir(directory(id)).removeRecursively();
  emit changed();
}

QVariantList TranslationPacks::list() const {
  QVariantList rows;
  for (const TranslationPack &pack : translationPackTable()) {
    const QString id = QLatin1String(pack.id);
    rows.append(QVariantMap{
        {QStringLiteral("code"), id},
        {QStringLiteral("name"), QString::fromStdString(lyric_language::displayName(pack.source))},
        {QStringLiteral("quality"), QLatin1String(pack.quality)},
        {QStringLiteral("sizeBytes"), packBytes(pack)},
        {QStringLiteral("installed"), installed(id)},
        {QStringLiteral("available"), available(id)},
        {QStringLiteral("credit"), QString::fromUtf8(pack.credit)},
    });
  }
  return rows;
}

qint64 TranslationPacks::installedBytes() const {
  qint64 total = 0;
  for (const TranslationPack &pack : translationPackTable())
    if (installed(QLatin1String(pack.id)))
      total += packBytes(pack);
  return total;
}
