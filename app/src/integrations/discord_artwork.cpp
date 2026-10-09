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

namespace {
constexpr qint64 kMaxSourceBytes = 256LL * 1024 * 1024;
constexpr int kDownloadTimeoutMs = 60 * 1000;
constexpr int kProbeTimeoutMs = 20 * 1000;
// Encoding a 20-30 s loop at compression level 6 takes 10-15 s on a desktop.
constexpr int kEncodeTimeoutMs = 5 * 60 * 1000;
constexpr int kUploadTimeoutMs = 2 * 60 * 1000;
constexpr int kUploadRetryDelayMs = 5000;
constexpr int kDefaultRetryAfterSeconds = 60;
constexpr qsizetype kMaxProcessOutput = 1024 * 1024;
constexpr qsizetype kStderrTail = 4096;
// Only legacy responses have an expiry; keep their cached URLs fresh.
constexpr qint64 kExpiryMarginSeconds = 60 * 60;

const QSet<QString> kImageCodecs{QStringLiteral("webp"), QStringLiteral("png"), QStringLiteral("mjpeg"),
                                 QStringLiteral("jpeg"), QStringLiteral("gif"), QStringLiteral("bmp")};

QString shortHash(const QString &hash) { return hash.left(12); }

QString mebibytes(qint64 bytes) {
  return QString::number(double(bytes) / (1024.0 * 1024.0), 'f', 1) + QStringLiteral(" MiB");
}

void log(const QString &message) { qInfo().noquote() << "Discord artwork:" << message; }
void warn(const QString &message) { qWarning().noquote() << "Discord artwork:" << message; }

bool trustedArtworkUrl(const QUrl &url, const QUrl &service) {
  if (!url.isValid() || !url.userName().isEmpty() || !url.password().isEmpty())
    return false;
  const bool accountHost = url.scheme() == service.scheme() && url.host() == service.host() &&
      url.port() == service.port();
  const bool artworkHost = url.scheme() == QStringLiteral("https") &&
      url.host() == QStringLiteral("artwork.sfg545.dev") && url.port() == -1;
  return accountHost || artworkHost;
}

quint32 u24(const uchar *p) { return quint32(p[0]) | (quint32(p[1]) << 8) | (quint32(p[2]) << 16); }

struct Chunk {
  QByteArray id;
  qsizetype payload{0};
  qsizetype size{0};
};

// Walks RIFF chunks in [start, end). Empty result means malformed.
std::optional<QList<Chunk>> readChunks(const QByteArray &bytes, qsizetype start, qsizetype end) {
  QList<Chunk> chunks;
  qsizetype offset = start;
  while (offset < end) {
    if (offset + 8 > end)
      return std::nullopt;
    const auto *p = reinterpret_cast<const uchar *>(bytes.constData() + offset);
    const qsizetype size = qFromLittleEndian<quint32>(p + 4);
    if (offset + 8 + size > end)
      return std::nullopt;
    chunks.append({bytes.mid(offset, 4), offset + 8, size});
    offset += 8 + size + (size & 1);
  }
  return chunks;
}

std::optional<QSize> bitstreamSize(const QByteArray &bytes, const Chunk &chunk) {
  const auto *p = reinterpret_cast<const uchar *>(bytes.constData() + chunk.payload);
  if (chunk.id == "VP8 ") {
    if (chunk.size < 10 || p[3] != 0x9d || p[4] != 0x01 || p[5] != 0x2a)
      return std::nullopt;
    return QSize((p[6] | (p[7] << 8)) & 0x3fff, (p[8] | (p[9] << 8)) & 0x3fff);
  }
  if (chunk.id == "VP8L") {
    if (chunk.size < 5 || p[0] != 0x2f)
      return std::nullopt;
    const quint32 bits = qFromLittleEndian<quint32>(p + 1);
    return QSize(int(bits & 0x3fff) + 1, int((bits >> 14) & 0x3fff) + 1);
  }
  return std::nullopt;
}

// No console window, and low priority so an encode never competes with the
// audio thread for CPU.
void configureTool(QProcess *process) {
#ifdef Q_OS_WIN
  process->setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) {
    args->flags |= CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS;
  });
#else
  process->setChildProcessModifier([] { (void)::nice(10); });
#endif
}

QString lastLines(const QByteArray &text) {
  const QStringList lines = QString::fromUtf8(text).trimmed().split(QLatin1Char('\n'));
  return lines.mid(qMax(0, lines.size() - 3)).join(QStringLiteral(" | "));
}
} // namespace

struct DiscordArtwork::Job {
  QString sourceUrl;
  QList<std::function<void(const QString &)>> callbacks;
  // Shared so a killed FFmpeg can keep the directory alive until it exits.
  std::shared_ptr<QTemporaryDir> dir;
  QFile sourceFile;
  QCryptographicHash sourceHash{QCryptographicHash::Sha256};
  qint64 sourceBytes{0};
  QString sourceSha;
  std::optional<SourceInfo> source;
  QByteArray output;
  QString outputSha;
  QPointer<QNetworkReply> reply;
  QPointer<QProcess> process;
};

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

std::optional<DiscordArtwork::WebpInfo> DiscordArtwork::parseWebp(const QByteArray &bytes) {
  if (bytes.size() < 20 || !bytes.startsWith("RIFF") || bytes.mid(8, 4) != "WEBP")
    return std::nullopt;
  const auto *data = reinterpret_cast<const uchar *>(bytes.constData());
  const qsizetype riffEnd = qsizetype(qFromLittleEndian<quint32>(data + 4)) + 8;
  if (riffEnd > bytes.size() || riffEnd < bytes.size() - 1)
    return std::nullopt;
  const auto chunks = readChunks(bytes, 12, riffEnd);
  if (!chunks || chunks->isEmpty())
    return std::nullopt;

  const Chunk &first = chunks->first();
  if (first.id == "VP8 " || first.id == "VP8L") {
    const auto size = bitstreamSize(bytes, first);
    if (!size)
      return std::nullopt;
    return WebpInfo{size->width(), size->height(), false, 1, 0};
  }
  if (first.id != "VP8X" || first.size < 10)
    return std::nullopt;

  const uchar *header = data + first.payload;
  WebpInfo info{int(u24(header + 4)) + 1, int(u24(header + 7)) + 1, (header[0] & 0x02) != 0, 0, 0};
  if (!info.animated) {
    for (const Chunk &chunk : *chunks) {
      if (const auto size = bitstreamSize(bytes, chunk)) {
        if (size->width() != info.width || size->height() != info.height)
          return std::nullopt;
        info.frames = 1;
        return info;
      }
    }
    return std::nullopt;
  }

  bool hasAnim = false;
  for (const Chunk &chunk : *chunks) {
    hasAnim = hasAnim || chunk.id == "ANIM";
    if (chunk.id != "ANMF")
      continue;
    if (chunk.size < 16)
      return std::nullopt;
    const uchar *frame = data + chunk.payload;
    const int x = int(u24(frame)) * 2;
    const int y = int(u24(frame + 3)) * 2;
    const int width = int(u24(frame + 6)) + 1;
    const int height = int(u24(frame + 9)) + 1;
    if (x + width > info.width || y + height > info.height)
      return std::nullopt;
    const auto inner = readChunks(bytes, chunk.payload + 16, chunk.payload + chunk.size);
    if (!inner)
      return std::nullopt;
    std::optional<QSize> size;
    for (const Chunk &sub : *inner) {
      if ((size = bitstreamSize(bytes, sub)))
        break;
    }
    if (!size || size->width() != width || size->height() != height)
      return std::nullopt;
    info.frames += 1;
    info.durationMs += u24(frame + 12);
  }
  if (!hasAnim || info.frames == 0)
    return std::nullopt;
  return info;
}

std::optional<DiscordArtwork::SourceInfo> DiscordArtwork::parseProbe(const QByteArray &json) {
  const QJsonArray streams = QJsonDocument::fromJson(json).object().value(QStringLiteral("streams")).toArray();
  if (streams.isEmpty())
    return std::nullopt;
  const QJsonObject stream = streams.first().toObject();
  const auto rate = [](const QString &text) {
    const QStringList parts = text.split(QLatin1Char('/'));
    const double numerator = parts.value(0).toDouble();
    const double denominator = parts.size() > 1 ? parts.value(1).toDouble() : 1.0;
    return denominator > 0.0 ? numerator / denominator : 0.0;
  };
  SourceInfo info;
  info.codec = stream.value(QStringLiteral("codec_name")).toString();
  info.width = stream.value(QStringLiteral("width")).toInt();
  info.height = stream.value(QStringLiteral("height")).toInt();
  info.fps = rate(stream.value(QStringLiteral("avg_frame_rate")).toString());
  if (info.fps <= 0.0)
    info.fps = rate(stream.value(QStringLiteral("r_frame_rate")).toString());
  info.frames = stream.value(QStringLiteral("nb_read_packets")).toString().toLongLong();
  return info;
}

QStringList DiscordArtwork::encodeArguments(const QString &input, const QString &output,
                                            const SourceInfo &source, const Rung &rung, int sampleSeconds) {
  QStringList filters;
  if (source.fps > rung.maxFps + 0.5)
    filters << QStringLiteral("fps=%1").arg(rung.maxFps);
  // Fit inside the box without upscaling. BGRA hands libwebp RGB, so the
  // BT.709 source is converted once instead of being read back as BT.601.
  filters << QStringLiteral("scale='min(%1,iw)':'min(%1,ih)':force_original_aspect_ratio=decrease"
                            ":flags=lanczos+accurate_rnd+full_chroma_int")
                 .arg(rung.dimension)
          << QStringLiteral("format=bgra");
  QStringList arguments{QStringLiteral("-nostdin"), QStringLiteral("-hide_banner"), QStringLiteral("-loglevel"),
                        QStringLiteral("error"), QStringLiteral("-y")};
  if (sampleSeconds > 0)
    arguments << QStringLiteral("-t") << QString::number(sampleSeconds);
  return arguments + QStringList{
      QStringLiteral("-i"), input,
      QStringLiteral("-map"), QStringLiteral("0:v:0"), QStringLiteral("-an"), QStringLiteral("-sn"), QStringLiteral("-dn"),
      QStringLiteral("-vf"), filters.join(QLatin1Char(',')),
      // Keep source timestamps: libwebp_anim turns them into frame durations.
      QStringLiteral("-fps_mode"), QStringLiteral("passthrough"),
      QStringLiteral("-c:v"), QStringLiteral("libwebp_anim"),
      QStringLiteral("-lossless"), QStringLiteral("0"),
      QStringLiteral("-quality"), QString::number(rung.quality),
      QStringLiteral("-compression_level"), QStringLiteral("6"),
      QStringLiteral("-preset"), QStringLiteral("picture"),
      QStringLiteral("-loop"), QStringLiteral("0"),
      QStringLiteral("-f"), QStringLiteral("webp"), output,
  };
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
  if (it == m_hosted.constEnd() ||
      (it->expiresAt.isValid() && it->expiresAt < QDateTime::currentDateTimeUtc().addSecs(kExpiryMarginSeconds)))
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
    if (!url.isEmpty() && (!expiresAt.isValid() || expiresAt > cutoff))
      m_hosted.insert(it.key(), {url, expiresAt});
  }
}

void DiscordArtwork::saveHosted() const {
  const QDateTime now = QDateTime::currentDateTimeUtc();
  QJsonObject entries;
  for (auto it = m_hosted.constBegin(); it != m_hosted.constEnd(); ++it) {
    if (!it->expiresAt.isValid() || it->expiresAt > now)
      entries.insert(it.key(), QJsonObject{{QStringLiteral("url"), it->url},
                                           {QStringLiteral("expires_at"), it->expiresAt.isValid()
                                               ? QJsonValue(it->expiresAt.toSecsSinceEpoch()) : QJsonValue::Null}});
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
