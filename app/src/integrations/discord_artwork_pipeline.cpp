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

void DiscordArtwork::download(std::shared_ptr<Job> job) {
  QNetworkRequest request{QUrl(job->sourceUrl)};
  request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Orchard/3.0"));
  request.setTransferTimeout(kDownloadTimeoutMs);
  QNetworkReply *reply = m_network.get(request);
  job->reply = reply;

  connect(reply, &QNetworkReply::readyRead, this, [this, job, reply] {
    if (!isCurrent(job))
      return;
    const QByteArray chunk = reply->readAll();
    job->sourceBytes += chunk.size();
    if (job->sourceBytes > kMaxSourceBytes) {
      reply->abort();
      return fail(job, QStringLiteral("source exceeds %1").arg(mebibytes(kMaxSourceBytes)), true);
    }
    job->sourceHash.addData(chunk);
    job->sourceFile.write(chunk);
  });
  connect(reply, &QNetworkReply::finished, this, [this, job, reply] {
    reply->deleteLater();
    if (!isCurrent(job))
      return;
    job->sourceFile.close();
    if (reply->error() != QNetworkReply::NoError)
      return fail(job, QStringLiteral("source download failed: %1").arg(reply->errorString()));
    if (job->sourceBytes == 0)
      return fail(job, QStringLiteral("source download was empty"));

    job->sourceSha = QString::fromLatin1(job->sourceHash.result().toHex());
    if (const auto cached = cachedUrl(QStringLiteral("sha256:") + job->sourceSha)) {
      log(QStringLiteral("cache hit %1").arg(shortHash(job->sourceSha)));
      remember(job, *cached, m_hosted.value(QStringLiteral("sha256:") + job->sourceSha).expiresAt);
      return finish(job, *cached);
    }
    probe(job);
  });
}

// Runs a tool with both pipes drained, so neither can fill and stall it.
static void runTool(QObject *context, QPointer<QProcess> &slot, const QString &program, const QStringList &arguments,
                    int timeoutMs, std::function<void(bool started, int exitCode, QByteArray out, QByteArray err)> done) {
  auto *process = new QProcess(context);
  slot = process;
  configureTool(process);
  auto out = std::make_shared<QByteArray>();
  auto err = std::make_shared<QByteArray>();
  QObject::connect(process, &QProcess::readyReadStandardOutput, context, [process, out] {
    const QByteArray data = process->readAllStandardOutput();
    if (out->size() < kMaxProcessOutput)
      out->append(data.left(kMaxProcessOutput - out->size()));
  });
  QObject::connect(process, &QProcess::readyReadStandardError, context, [process, err] {
    err->append(process->readAllStandardError());
    if (err->size() > kStderrTail)
      *err = err->right(kStderrTail);
  });
  QObject::connect(process, &QProcess::errorOccurred, context, [process, done](QProcess::ProcessError error) {
    if (error != QProcess::FailedToStart)
      return;
    process->deleteLater();
    done(false, -1, {}, {});
  });
  QObject::connect(process, &QProcess::finished, context,
                   [process, out, err, done](int exitCode, QProcess::ExitStatus status) {
                     process->deleteLater();
                     out->append(process->readAllStandardOutput());
                     err->append(process->readAllStandardError());
                     done(true, status == QProcess::NormalExit ? exitCode : -1, *out, *err);
                   });
  QTimer::singleShot(timeoutMs, process, [process] {
    if (process->state() != QProcess::NotRunning)
      process->kill();
  });
  process->start(program, arguments, QIODevice::ReadOnly);
}

void DiscordArtwork::probe(std::shared_ptr<Job> job) {
  const QStringList arguments{
      QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-select_streams"), QStringLiteral("v:0"),
      QStringLiteral("-count_packets"), QStringLiteral("-show_entries"),
      QStringLiteral("stream=codec_name,width,height,avg_frame_rate,r_frame_rate,nb_read_packets"),
      QStringLiteral("-of"), QStringLiteral("json"), job->sourceFile.fileName()};
  runTool(this, job->process, ffprobeProgram(), arguments, kProbeTimeoutMs,
          [this, job](bool started, int exitCode, const QByteArray &out, const QByteArray &err) {
            if (!isCurrent(job))
              return;
            if (started && exitCode == 0)
              job->source = parseProbe(out);
            if (!job->source) {
              // Probing only tunes the encode. Without it, FFmpeg still works.
              warn(started ? QStringLiteral("ffprobe failed: %1").arg(lastLines(err))
                           : QStringLiteral("ffprobe not found, encoding without source details"));
            }

            if (job->source && job->source->codec == QStringLiteral("webp")) {
              QFile file(job->sourceFile.fileName());
              const QByteArray bytes = file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
              const auto info = parseWebp(bytes);
              if (info && info->width <= kMaxDimension && info->height <= kMaxDimension &&
                  bytes.size() <= kWorkerMaxBytes && info->frames <= kWorkerMaxFrames) {
                log(QStringLiteral("source is already a suitable WebP, skipping conversion"));
                job->output = bytes;
                return uploadConverted(job);
              }
            }
            // Without a loop length there is nothing to extrapolate a sample to.
            if (job->source && job->source->durationSeconds() > 0.0)
              sample(job, 0);
            else
              encode(job, 0);
          });
}

static QString describeRung(const DiscordArtwork::Rung &rung) {
  return QStringLiteral("%1px q%2 <=%3 fps").arg(rung.dimension).arg(rung.quality).arg(rung.maxFps);
}

void DiscordArtwork::sample(std::shared_ptr<Job> job, int rung) {
  const int last = int(std::size(kLadder)) - 1;
  const QString output = job->dir->filePath(QStringLiteral("sample-%1.webp").arg(rung));
  const SourceInfo source = *job->source;
  runTool(this, job->process, ffmpegProgram(),
          encodeArguments(job->sourceFile.fileName(), output, source, kLadder[rung], kSampleSeconds),
          kEncodeTimeoutMs,
          [this, job, rung, last, output, source](bool started, int exitCode, const QByteArray &, const QByteArray &err) {
            if (!isCurrent(job))
              return;
            if (!started)
              return fail(job, QStringLiteral("FFmpeg not found (%1)").arg(ffmpegProgram()));
            if (exitCode != 0)
              return fail(job, QStringLiteral("FFmpeg exited with code %1: %2").arg(exitCode).arg(lastLines(err)));
            QFile file(output);
            const QByteArray bytes = file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
            file.close();
            QFile::remove(output);
            const auto info = parseWebp(bytes);
            if (!info || info->durationMs <= 0) {
              // A sample too short to measure: let the full encodes decide.
              return encode(job, rung);
            }
            const qint64 predicted = qint64(bytes.size() * (source.durationSeconds() * 1000.0 / info->durationMs));
            const bool fits = predicted <= qint64(kWorkerMaxBytes * kSampleBudget);
            log(QStringLiteral("%1 predicts %2").arg(describeRung(kLadder[rung]), mebibytes(predicted)));
            if (fits || rung == last)
              return encode(job, rung);
            sample(job, rung + 1);
          });
}

// Ten seconds of FFmpeg at full effort so your friends can watch an album
// cover wiggle at 120px. Priorities.
void DiscordArtwork::encode(std::shared_ptr<Job> job, int rung) {
  const QString output = job->dir->filePath(QStringLiteral("artwork-%1.webp").arg(rung));
  const SourceInfo source = job->source.value_or(SourceInfo{});
  log(QStringLiteral("converting source to WebP at %1").arg(describeRung(kLadder[rung])));
  runTool(this, job->process, ffmpegProgram(), encodeArguments(job->sourceFile.fileName(), output, source, kLadder[rung]),
          kEncodeTimeoutMs,
          [this, job, rung, output, source](bool started, int exitCode, const QByteArray &, const QByteArray &err) {
            if (!isCurrent(job))
              return;
            if (!started)
              return fail(job, QStringLiteral("FFmpeg not found (%1)").arg(ffmpegProgram()));
            if (exitCode != 0)
              return fail(job, QStringLiteral("FFmpeg exited with code %1: %2").arg(exitCode).arg(lastLines(err)));

            QFile file(output);
            const QByteArray bytes = file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
            file.close();
            QFile::remove(output);
            if (bytes.isEmpty())
              return fail(job, QStringLiteral("FFmpeg produced no output"));
            if (bytes.size() > kWorkerMaxBytes) {
              if (rung + 1 < int(std::size(kLadder))) {
                log(QStringLiteral("%1 exceeds the %2 upload cap, stepping down")
                        .arg(mebibytes(bytes.size()), mebibytes(kWorkerMaxBytes)));
                return encode(job, rung + 1);
              }
              return fail(job, QStringLiteral("converted artwork is %1, over the %2 upload cap")
                                   .arg(mebibytes(bytes.size()), mebibytes(kWorkerMaxBytes)),
                          true);
            }

            const auto info = parseWebp(bytes);
            if (!info)
              return fail(job, QStringLiteral("FFmpeg output is not a valid WebP"), true);
            const bool expectAnimation = !kImageCodecs.contains(source.codec);
            if (info->width > kMaxDimension || info->height > kMaxDimension)
              return fail(job, QStringLiteral("converted artwork is %1x%2").arg(info->width).arg(info->height), true);
            if (expectAnimation && (!info->animated || info->frames < 2))
              return fail(job, QStringLiteral("conversion lost the animation"), true);
            if (info->frames > kWorkerMaxFrames)
              return fail(job, QStringLiteral("loop has %1 frames, over the worker's %2")
                                   .arg(info->frames).arg(kWorkerMaxFrames), true);
            if (info->animated &&
                (info->durationMs <= 0 || info->frames * 1000.0 / info->durationMs > kWorkerMaxFps))
              return fail(job, QStringLiteral("converted frame timing is invalid"), true);

            log(QStringLiteral("converted %1x%2, %3 frames over %4 s, %5")
                    .arg(info->width).arg(info->height).arg(info->frames)
                    .arg(QString::number(info->durationMs / 1000.0, 'f', 1), mebibytes(bytes.size())));
            job->output = bytes;
            uploadConverted(job);
          });
}

void DiscordArtwork::uploadConverted(std::shared_ptr<Job> job) {
  job->outputSha = QString::fromLatin1(QCryptographicHash::hash(job->output, QCryptographicHash::Sha256).toHex());
  // POST also links this source to existing bytes. HEAD alone would leave the
  // next device with no breadcrumb, and the encoder would tell us that joke twice.
  m_account->withAccessToken([this, job](const QString &token) {
    if (!isCurrent(job))
      return;
    if (token.isEmpty())
      return fail(job, QStringLiteral("no Orchard session for the upload"));
    upload(job, token, 0);
  });
}

void DiscordArtwork::upload(std::shared_ptr<Job> job, const QString &token, int attempt) {
  QNetworkRequest request(m_account->serviceUrl().resolved(QUrl(QStringLiteral("/artwork"))));
  request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("image/webp"));
  request.setRawHeader("Authorization", "Bearer " + token.toLatin1());
  request.setRawHeader("x-orchard-source-sha256", QCryptographicHash::hash(
      job->sourceUrl.toUtf8(), QCryptographicHash::Sha256).toHex());
  request.setTransferTimeout(kUploadTimeoutMs);
  QNetworkReply *reply = m_network.post(request, job->output);
  job->reply = reply;

  connect(reply, &QNetworkReply::finished, this, [this, job, reply, attempt] {
    reply->deleteLater();
    if (!isCurrent(job))
      return;
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QJsonObject body = QJsonDocument::fromJson(reply->readAll()).object();
    const QString description = body.value(QStringLiteral("error_description"))
                                    .toString(body.value(QStringLiteral("error")).toString(QStringLiteral("HTTP %1").arg(status)));

    if (status == 200 || status == 201) {
      const QUrl hosted(body.value(QStringLiteral("url")).toString());
      const QUrl service = m_account->serviceUrl();
      const QJsonValue expiry = body.value(QStringLiteral("expires_at"));
      const QDateTime expiresAt = expiry.isNull() ? QDateTime()
          : QDateTime::fromSecsSinceEpoch(expiry.toInteger(), QTimeZone::UTC);
      // Discord receives this URL directly, so keep it on Orchard's known hosts.
      if (!trustedArtworkUrl(hosted, service) ||
          (expiresAt.isValid() && expiresAt <= QDateTime::currentDateTimeUtc()))
        return fail(job, QStringLiteral("worker returned a malformed upload response"));
      log(QStringLiteral("uploaded %1").arg(shortHash(job->outputSha)));
      remember(job, hosted.toString(), expiresAt);
      return finish(job, hosted.toString());
    }
    if (status == 429) {
      bool ok = false;
      const int seconds = reply->rawHeader("Retry-After").toInt(&ok);
      m_rateLimitedUntil = QDateTime::currentDateTimeUtc().addSecs(ok && seconds > 0 ? seconds : kDefaultRetryAfterSeconds);
      return fail(job, QStringLiteral("worker rate limited for %1 s").arg(ok ? seconds : kDefaultRetryAfterSeconds));
    }
    if (status == 401)
      return fail(job, QStringLiteral("worker rejected the Orchard session"));
    if (status == 413 || status == 415 || status == 422)
      return fail(job, QStringLiteral("worker rejected the artwork: %1").arg(description), true);

    // Network errors, timeouts and 5xx get one delayed retry.
    const bool transient = status == 0 || status >= 500;
    if (transient && attempt == 0) {
      log(QStringLiteral("upload failed (%1), retrying once")
              .arg(status ? QStringLiteral("HTTP %1").arg(status) : reply->errorString()));
      QTimer::singleShot(kUploadRetryDelayMs, this, [this, job] {
        if (!isCurrent(job))
          return;
        m_account->withAccessToken([this, job](const QString &token) {
          if (!isCurrent(job))
            return;
          if (token.isEmpty())
            return fail(job, QStringLiteral("no Orchard session for the upload"));
          upload(job, token, 1);
        });
      });
      return;
    }
    fail(job, QStringLiteral("upload failed: %1").arg(status ? description : reply->errorString()));
  });
}
