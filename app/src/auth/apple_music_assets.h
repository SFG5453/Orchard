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

#pragma once

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QString>
#include <QStringList>

// Files the Apple Music player page loads for its scan fallback, staged beside the binaries
// by scripts/stage-apple-music-scan.py. The helper serves them as orchard-mix://assets/<name>.
namespace apple_music_assets {

inline constexpr auto kScheme = "orchard-mix";

inline QStringList names() {
  return {QStringLiteral("mixwasm.wasm"), QStringLiteral("ort.wasm.min.mjs"),
          QStringLiteral("ort-wasm-simd-threaded.mjs"), QStringLiteral("ort-wasm-simd-threaded.wasm"),
          QStringLiteral("nirovi/nirovi_int8.onnx"),
          QStringLiteral("vocal-separation/vocals_umxhq_fp32.onnx")};
}

// Path of an allowed asset, or empty.
inline QString path(const QString &name) {
  if (!names().contains(name))
    return {};
  const QDir binaries(QCoreApplication::applicationDirPath());
  if (name.endsWith(QStringLiteral(".onnx")))
    return QDir(qEnvironmentVariable("ORCHARD_MODELS_DIR", binaries.filePath(QStringLiteral("models")))).filePath(name);
  return binaries.filePath(QStringLiteral("applemusic-web/") + name);
}

inline bool available() {
  for (const QString &name : names()) {
    if (!QFile::exists(path(name)))
      return false;
  }
  return true;
}

} // namespace apple_music_assets
