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

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QStandardPaths>

// Call with internal stage names, flags and numeric error codes only. Never
// pass cookie values, page JSON, URLs, account names or provider error bodies.
inline void authDiagnostic(const QString &stage) {
  const QString directory = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
  if (!QDir().mkpath(directory)) return;
  QFile file(QDir(directory).filePath(QStringLiteral("auth-diagnostics.log")));
  if (!file.open(QIODevice::WriteOnly | QIODevice::Append)) return;
  const QByteArray line = QStringLiteral("%1 pid=%2 %3\n")
      .arg(QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs))
      .arg(QCoreApplication::applicationPid()).arg(stage).toUtf8();
  // Leave breadcrumbs before calling native code. Crashes are terrible diarists.
  file.write(line);
  file.flush();
}
