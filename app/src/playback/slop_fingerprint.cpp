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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "slop_fingerprint.h"
#include "model_paths.h"

#include <QDir>
#include <QFile>
#include <QtEndian>
#include <array>
#include <bit>
#include <cmath>

namespace {
constexpr int kFeatures = 3585;
struct Model {
  std::array<float, kFeatures + 1> values{};
  bool valid = false;
};

const Model &model()
{
  static const Model loaded = [] {
    Model result;
    QFile file(QDir(orchardModelsDirectory()).filePath(QStringLiteral("slop/fakeprint_lr.f32")));
    if (!file.open(QIODevice::ReadOnly)) return result;
    const QByteArray bytes = file.readAll();
    if (bytes.size() != qsizetype(result.values.size() * sizeof(float))) return result;
    for (size_t i = 0; i < result.values.size(); ++i) {
      const quint32 bits = qFromLittleEndian<quint32>(
          reinterpret_cast<const uchar *>(bytes.constData() + i * sizeof(float)));
      result.values[i] = std::bit_cast<float>(bits);
      if (!std::isfinite(result.values[i])) return result;
    }
    result.valid = true;
    return result;
  }();
  return loaded;
}

} // namespace

std::unique_ptr<SlopFingerprintDetector> SlopFingerprintDetector::create(int sampleRate, int channels)
{
  const Model &weights = model();
  if (!weights.valid) return {};
  return create(sampleRate, channels, weights.values);
}
