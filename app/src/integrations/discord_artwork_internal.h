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


#pragma once

#include "discord_artwork.h"

#include <QCryptographicHash>
#include <QDebug>
#include <QFile>
#include <QNetworkReply>
#include <QPointer>
#include <QProcess>
#include <QSet>
#include <QTemporaryDir>
#include <QUrl>

#ifdef Q_OS_WIN
#include <windows.h>
#else
#include <unistd.h>
#endif

#include <memory>
#include <optional>

// Shared by the discord_artwork*.cpp files only.
namespace discord_artwork {
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
// Hosted artwork expires a day after its last use; drop cached URLs early.
constexpr qint64 kExpiryMarginSeconds = 60 * 60;

inline const QSet<QString> kImageCodecs{QStringLiteral("webp"), QStringLiteral("png"), QStringLiteral("mjpeg"),
                                 QStringLiteral("jpeg"), QStringLiteral("gif"), QStringLiteral("bmp")};

inline QString shortHash(const QString &hash) { return hash.left(12); }

inline QString mebibytes(qint64 bytes) {
  return QString::number(double(bytes) / (1024.0 * 1024.0), 'f', 1) + QStringLiteral(" MiB");
}

inline void log(const QString &message) { qInfo().noquote() << "Discord artwork:" << message; }
inline void warn(const QString &message) { qWarning().noquote() << "Discord artwork:" << message; }

inline bool trustedArtworkUrl(const QUrl &url, const QUrl &service) {
  if (!url.isValid() || !url.userName().isEmpty() || !url.password().isEmpty())
    return false;
  const bool accountHost = url.scheme() == service.scheme() && url.host() == service.host() &&
      url.port() == service.port();
  const bool artworkHost = url.scheme() == QStringLiteral("https") &&
      url.host() == QStringLiteral("artwork.sfg545.dev") && url.port() == -1;
  return accountHost || artworkHost;
}

// No console window, and low priority so an encode never competes with the
// audio thread for CPU.
inline void configureTool(QProcess *process) {
#ifdef Q_OS_WIN
  process->setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) {
    args->flags |= CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS;
  });
#else
  process->setChildProcessModifier([] { (void)::nice(10); });
#endif
}

inline QString lastLines(const QByteArray &text) {
  const QStringList lines = QString::fromUtf8(text).trimmed().split(QLatin1Char('\n'));
  return lines.mid(qMax(0, lines.size() - 3)).join(QStringLiteral(" | "));
}
} // namespace discord_artwork

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
