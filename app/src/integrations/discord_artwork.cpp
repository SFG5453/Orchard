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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "discord_artwork.h"
#include "discord_artwork_internal.h"

#include "account/orchard_account.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QProcess>
#include <QSaveFile>
#include <QSize>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTimeZone>
#include <QTimer>
#include <QUrl>
#include <QUrlQuery>
#include <QtEndian>

#ifdef Q_OS_WIN
#include <windows.h>
#else
#include <unistd.h>
#endif

#include <utility>

using namespace discord_artwork;

DiscordArtwork::DiscordArtwork(OrchardAccount *account, QObject *parent)
    : QObject(parent), m_account(account),
      m_hostedPath(QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation))
                       .filePath(QStringLiteral("discord-artwork-b2.json"))) {
  loadHosted();
}

DiscordArtwork::~DiscordArtwork() { cancel(); }

QString DiscordArtwork::ffmpegProgram() {
  const QString configured = qEnvironmentVariable("ORCHARD_FFMPEG");
  return configured.isEmpty() ? QStringLiteral("ffmpeg") : configured;
}

QString DiscordArtwork::ffprobeProgram() {
  // A bundled ffmpeg.exe ships its ffprobe next to it.
  const QString configured = qEnvironmentVariable("ORCHARD_FFMPEG");
  if (!configured.isEmpty()) {
    const QFileInfo ffmpeg(configured);
    const QString sibling = ffmpeg.dir().filePath(
        ffmpeg.suffix().isEmpty() ? QStringLiteral("ffprobe") : QStringLiteral("ffprobe.") + ffmpeg.suffix());
    if (QFileInfo::exists(sibling))
      return sibling;
  }
  return QStringLiteral("ffprobe");
}

void DiscordArtwork::prepare(const QString &sourceUrl, std::function<void(const QString &)> done) {
  const QString source = sourceUrl.trimmed();
  const auto reply = [this, &done](const QString &url) {
    // Always async, so callers never re-enter their own presence update.
    QMetaObject::invokeMethod(this, [done = std::move(done), url] { done(url); }, Qt::QueuedConnection);
  };

  if (m_job && m_job->sourceUrl == source) {
    m_job->callbacks.append(std::move(done));
    return;
  }
  cancel();

  if (source.isEmpty() || m_rejected.contains(source))
    return reply(QString());
  if (const auto cached = cachedUrl(source)) {
    log(QStringLiteral("cache hit %1").arg(*cached));
    return reply(*cached);
  }
  if (m_rateLimitedUntil.isValid() && QDateTime::currentDateTimeUtc() < m_rateLimitedUntil) {
    log(QStringLiteral("worker rate limited, skipping until %1").arg(m_rateLimitedUntil.toLocalTime().toString(Qt::ISODate)));
    return reply(QString());
  }
  if (!m_account || !m_account->isSignedIn()) {
    // Once per run: every track change would otherwise repeat it.
    if (!std::exchange(m_loggedSignedOut, true))
      log(QStringLiteral("skipped, not signed in to an Orchard account"));
    return reply(QString());
  }

  auto job = std::make_shared<Job>();
  job->sourceUrl = source;
  job->callbacks.append(std::move(done));
  m_job = job;
  checkIndex(job);
}

void DiscordArtwork::cancel() {
  const std::shared_ptr<Job> job = std::exchange(m_job, nullptr);
  if (!job)
    return;
  if (job->reply)
    job->reply->abort();
  if (QProcess *process = job->process) {
    process->disconnect(this);
    // The temp directory stays until the killed process lets go of its files.
    connect(process, &QProcess::finished, process, [process, dir = job->dir] { process->deleteLater(); });
    process->kill();
  }
}

bool DiscordArtwork::isCurrent(const std::shared_ptr<Job> &job) const { return job && m_job == job; }

std::optional<QString> DiscordArtwork::cachedUrl(const QString &key) const {
  const auto it = m_hosted.constFind(key);
  if (it == m_hosted.constEnd() || !it->expiresAt.isValid() ||
      it->expiresAt < QDateTime::currentDateTimeUtc().addSecs(kExpiryMarginSeconds))
    return std::nullopt;
  return it->url;
}

void DiscordArtwork::remember(const std::shared_ptr<Job> &job, const QString &url, const QDateTime &expiresAt) {
  m_hosted.insert(job->sourceUrl, {url, expiresAt});
  if (!job->sourceSha.isEmpty())
    m_hosted.insert(QStringLiteral("sha256:") + job->sourceSha, {url, expiresAt});
  saveHosted();
}

// Persisted so a restart skips the download and encode for artwork the worker still hosts.
// Your CPU fan thanks you. It was never told why it had to spin in the first place.
void DiscordArtwork::loadHosted() {
  QFile file(m_hostedPath);
  if (!file.open(QIODevice::ReadOnly))
    return;
  const QJsonObject entries = QJsonDocument::fromJson(file.readAll()).object();
  const QDateTime cutoff = QDateTime::currentDateTimeUtc().addSecs(kExpiryMarginSeconds);
  for (auto it = entries.constBegin(); it != entries.constEnd(); ++it) {
    const QJsonObject entry = it->toObject();
    const QString url = entry.value(QStringLiteral("url")).toString();
    const QJsonValue expiry = entry.value(QStringLiteral("expires_at"));
    const QDateTime expiresAt = expiry.isNull() ? QDateTime()
        : QDateTime::fromSecsSinceEpoch(expiry.toInteger(), QTimeZone::UTC);
    // Entries without an expiry predate artwork expiry and may point at deleted files.
    if (!url.isEmpty() && expiresAt.isValid() && expiresAt > cutoff)
      m_hosted.insert(it.key(), {url, expiresAt});
  }
}

void DiscordArtwork::saveHosted() const {
  const QDateTime now = QDateTime::currentDateTimeUtc();
  QJsonObject entries;
  for (auto it = m_hosted.constBegin(); it != m_hosted.constEnd(); ++it) {
    if (it->expiresAt.isValid() && it->expiresAt > now)
      entries.insert(it.key(), QJsonObject{{QStringLiteral("url"), it->url},
                                           {QStringLiteral("expires_at"), it->expiresAt.toSecsSinceEpoch()}});
  }
  QDir().mkpath(QFileInfo(m_hostedPath).absolutePath());
  QSaveFile file(m_hostedPath);
  if (!file.open(QIODevice::WriteOnly))
    return warn(QStringLiteral("could not save the hosted artwork cache: %1").arg(file.errorString()));
  file.write(QJsonDocument(entries).toJson(QJsonDocument::Compact));
  if (!file.commit())
    warn(QStringLiteral("could not save the hosted artwork cache: %1").arg(file.errorString()));
}

void DiscordArtwork::finish(std::shared_ptr<Job> job, const QString &url) {
  if (!isCurrent(job))
    return;
  m_job.reset();
  const auto callbacks = std::exchange(job->callbacks, {});
  for (const auto &callback : callbacks)
    callback(url);
}

void DiscordArtwork::fail(std::shared_ptr<Job> job, const QString &reason, bool permanent) {
  if (!isCurrent(job))
    return;
  warn(reason);
  if (permanent)
    m_rejected.insert(job->sourceUrl);
  finish(job, QString());
}

void DiscordArtwork::startJob(std::shared_ptr<Job> job) {
  job->dir = std::make_shared<QTemporaryDir>(QDir::temp().filePath(QStringLiteral("orchard-discord-artwork-XXXXXX")));
  if (!job->dir->isValid())
    return fail(job, QStringLiteral("could not create a temporary directory: %1").arg(job->dir->errorString()));
  job->sourceFile.setFileName(job->dir->filePath(QStringLiteral("source")));
  if (!job->sourceFile.open(QIODevice::WriteOnly))
    return fail(job, QStringLiteral("could not write the source: %1").arg(job->sourceFile.errorString()));
  download(job);
}

void DiscordArtwork::checkIndex(std::shared_ptr<Job> job) {
  // Ask the shared index before downloading a source or waking the encoder.
  m_account->withAccessToken([this, job](const QString &token) {
    if (!isCurrent(job))
      return;
    if (token.isEmpty())
      return startJob(job);
    const QString sourceSha = QString::fromLatin1(
        QCryptographicHash::hash(job->sourceUrl.toUtf8(), QCryptographicHash::Sha256).toHex());
    QUrl indexUrl = m_account->serviceUrl().resolved(QUrl(QStringLiteral("/artwork/index")));
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("source_sha256"), sourceSha);
    indexUrl.setQuery(query);
    QNetworkRequest request(indexUrl);
    request.setRawHeader("Authorization", "Bearer " + token.toLatin1());
    request.setTransferTimeout(kDownloadTimeoutMs);
    QNetworkReply *reply = m_network.get(request);
    job->reply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, job, reply, sourceSha] {
      reply->deleteLater();
      if (!isCurrent(job))
        return;
      const QJsonArray files = QJsonDocument::fromJson(reply->readAll()).object().value(QStringLiteral("files")).toArray();
      const QUrl service = m_account->serviceUrl();
      for (const QJsonValue &value : files) {
        const QJsonObject entry = value.toObject();
        if (entry.value(QStringLiteral("source_sha256")).toString() != sourceSha)
          continue;
        const QUrl hosted(entry.value(QStringLiteral("url")).toString());
        const QJsonValue expiry = entry.value(QStringLiteral("expires_at"));
        const QDateTime expiresAt = expiry.isNull() ? QDateTime()
            : QDateTime::fromSecsSinceEpoch(expiry.toInteger(), QTimeZone::UTC);
        if (trustedArtworkUrl(hosted, service) &&
            (!expiresAt.isValid() || expiresAt > QDateTime::currentDateTimeUtc().addSecs(kExpiryMarginSeconds))) {
          log(QStringLiteral("shared index hit %1").arg(hosted.toString()));
          remember(job, hosted.toString(), expiresAt);
          return finish(job, hosted.toString());
        }
      }
      startJob(job);
    });
  });
}
