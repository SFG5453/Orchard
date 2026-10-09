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

#include "letterbox.h"

#include <algorithm>

namespace letterbox {
namespace {

constexpr int kSampleWidth = 192;
// Limited-range video black decodes near 16; compression noise stays under this.
constexpr int kBlackLevel = 32;
// Sides this close to the frame edge are treated as no bar at all.
constexpr double kSnap = 0.03;
// Bars never cover more than this; a smaller lit area means a dark scene.
constexpr double kMinContent = 0.4;

bool lit(int brightPixels, int span) { return brightPixels > span / 50 + 1; }

} // namespace

QRectF contentRect(const QImage &frame) {
  if (frame.isNull() || frame.width() <= 0 || frame.height() <= 0)
    return {};
  const int height = std::max(1, frame.height() * kSampleWidth / frame.width());
  const QImage image = frame.scaled(kSampleWidth, height, Qt::IgnoreAspectRatio, Qt::FastTransformation)
                           .convertToFormat(QImage::Format_Grayscale8);
  const int w = image.width();
  const int h = image.height();

  const auto rowLit = [&](int y) {
    const uchar *line = image.constScanLine(y);
    return lit(int(std::count_if(line, line + w, [](uchar v) { return v > kBlackLevel; })), w);
  };
  int top = 0;
  while (top < h && !rowLit(top))
    ++top;
  if (top == h)
    return {};
  int bottom = h - 1;
  while (bottom > top && !rowLit(bottom))
    --bottom;

  const auto columnLit = [&](int x) {
    int bright = 0;
    for (int y = top; y <= bottom; ++y)
      bright += image.constScanLine(y)[x] > kBlackLevel;
    return lit(bright, bottom - top + 1);
  };
  int left = 0;
  while (left < w && !columnLit(left))
    ++left;
  int right = w - 1;
  while (right > left && !columnLit(right))
    --right;

  const QRectF rect(double(left) / w, double(top) / h, double(right - left + 1) / w,
                    double(bottom - top + 1) / h);
  if (rect.width() < kMinContent || rect.height() < kMinContent)
    return {};
  return rect;
}

QRectF accumulate(const QRectF &current, const QRectF &sample) {
  if (sample.isNull())
    return current;
  QRectF grown = current.isNull() ? sample : current.united(sample);
  if (grown.left() < kSnap)
    grown.setLeft(0);
  if (grown.top() < kSnap)
    grown.setTop(0);
  if (grown.right() > 1 - kSnap)
    grown.setRight(1);
  if (grown.bottom() > 1 - kSnap)
    grown.setBottom(1);
  return grown;
}

} // namespace letterbox
