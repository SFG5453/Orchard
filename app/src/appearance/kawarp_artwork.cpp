// Adapted from Kawarp. Copyright (c) 2026 Better Lyrics.
// SPDX-License-Identifier: MIT
// See third_party/kawarp/LICENSE (also bundled at :/licenses/kawarp/LICENSE).
#include "kawarp_artwork.h"
#include <QtGui/qrgbafloat.h>
#include <QVector3D>
#include <algorithm>
#include <vector>

namespace {
constexpr int Size = 128;
using Pixels = std::vector<QVector3D>;
QVector3D sample(const Pixels &pixels, float x, float y) {
    x = std::clamp(x, 0.0f, float(Size - 1));
    y = std::clamp(y, 0.0f, float(Size - 1));
    const int ix = int(x), iy = int(y);
    const int nx = std::min(ix + 1, Size - 1), ny = std::min(iy + 1, Size - 1);
    const float fx = x - ix, fy = y - iy;
    return (pixels[iy * Size + ix] * (1 - fx) + pixels[iy * Size + nx] * fx) * (1 - fy)
         + (pixels[ny * Size + ix] * (1 - fx) + pixels[ny * Size + nx] * fx) * fy;
}
}
QImage KawarpArtwork::prepare(const QImage &source) {
    if (source.isNull()) return {};
    const QImage small = source.scaled(Size, Size, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                                .convertToFormat(QImage::Format_RGB32);
    Pixels pixels(Size * Size), scratch(Size * Size);
    const QVector3D tint(0.024f, 0.04f, 0.028f);
    for (int y = 0; y < Size; ++y) {
        const auto *row = reinterpret_cast<const QRgb *>(small.constScanLine(y));
        for (int x = 0; x < Size; ++x) {
            const QVector3D color(qRed(row[x]) / 255.f, qGreen(row[x]) / 255.f, qBlue(row[x]) / 255.f);
            const float luma = QVector3D::dotProduct(color, QVector3D(0.299f, 0.587f, 0.114f));
            const float t = std::clamp(luma / 0.5f, 0.0f, 1.0f);
            const float mask = (1 - t * t * (3 - 2 * t)) * 0.42f;
            pixels[y * Size + x] = color * (1 - mask) + tint * mask;
        }
    }
    // Same four bilinear taps and offset schedule as Kawarp's GPU Kawase pass.
    // Keep float intermediates, and run only on artwork changes, off the GUI thread.
    for (int pass = 0; pass < 5; ++pass) {
        const float offset = pass + 0.5f;
        for (int y = 0; y < Size; ++y)
            for (int x = 0; x < Size; ++x)
                scratch[y * Size + x] = (sample(pixels, x - offset, y - offset)
                    + sample(pixels, x + offset, y - offset)
                    + sample(pixels, x - offset, y + offset)
                    + sample(pixels, x + offset, y + offset)) * 0.25f;
        pixels.swap(scratch);
    }
    // Keep the blur's fractional shades through upload; dark gradients have
    // very few 8-bit values to spare. This texture is still only 128 x 128.
    QImage result(Size, Size, QImage::Format_RGBX16FPx4);
    for (int y = 0; y < Size; ++y) {
        auto *row = reinterpret_cast<QRgbaFloat16 *>(result.scanLine(y));
        for (int x = 0; x < Size; ++x) {
            const auto &c = pixels[y * Size + x];
            row[x] = {qfloat16(c.x()), qfloat16(c.y()), qfloat16(c.z()), qfloat16(1)};
        }
    }
    return result;
}
QImage KawarpArtwork::crossfade(const QImage &previous, const QImage &next, float blend) {
    if (previous.isNull() || previous.size() != next.size()) return next;
    blend = std::clamp(blend, 0.0f, 1.0f);
    if (blend == 0) return previous;
    if (blend == 1) return next;
    const auto from = previous.convertToFormat(QImage::Format_RGBX16FPx4);
    const auto to = next.convertToFormat(QImage::Format_RGBX16FPx4);
    QImage result(next.size(), QImage::Format_RGBX16FPx4);
    for (int y = 0; y < next.height(); ++y) {
        const auto *a = reinterpret_cast<const QRgbaFloat16 *>(from.constScanLine(y));
        const auto *b = reinterpret_cast<const QRgbaFloat16 *>(to.constScanLine(y));
        auto *out = reinterpret_cast<QRgbaFloat16 *>(result.scanLine(y));
        for (int x = 0; x < next.width(); ++x)
            out[x] = {qfloat16(a[x].red() * (1 - blend) + b[x].red() * blend),
                      qfloat16(a[x].green() * (1 - blend) + b[x].green() * blend),
                      qfloat16(a[x].blue() * (1 - blend) + b[x].blue() * blend), qfloat16(1)};
    }
    return result;
}
