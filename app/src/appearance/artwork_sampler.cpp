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

#include "artwork_sampler.h"

#include <QBuffer>
#include <QColorSpace>
#include <QImage>
#include <QImageReader>
#include <QJsonArray>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <utility>

namespace {
using Rgb = std::array<quint8, 3>;
constexpr qsizetype kMaxArtworkBytes = 16 * 1024 * 1024;
constexpr qint64 kMaxArtworkPixels = 40'000'000;
constexpr double kMaxEdgeLightness = 0.34;
constexpr Rgb kFallback{42, 42, 42};

struct Rectangle { int left, top, width, height; };
struct Zone { Rgb average, median, seam; };
struct Hsl { double hue, saturation, lightness; };
struct HueBin { double weight = 0; std::array<double, 3> sums{}; };

quint8 channel(double value)
{
    return static_cast<quint8>(std::clamp(std::round(value), 0.0, 255.0));
}

Rgb mixRgb(Rgb from, Rgb to, double amount)
{
    Rgb result{};
    for (int i = 0; i < 3; ++i)
        result[i] = channel(double(from[i]) + (double(to[i]) - from[i]) * std::clamp(amount, 0.0, 1.0));
    return result;
}

Rgb weightedRgb(std::initializer_list<std::pair<Rgb, double>> entries)
{
    std::array<double, 3> sums{};
    double weight = 0;
    for (const auto &[rgb, contribution] : entries) {
        if (contribution <= 0) continue;
        weight += contribution;
        for (int i = 0; i < 3; ++i) sums[i] += rgb[i] * contribution;
    }
    if (weight == 0) return kFallback;
    return {channel(sums[0] / weight), channel(sums[1] / weight), channel(sums[2] / weight)};
}

Hsl rgbToHsl(Rgb rgb)
{
    const double red = rgb[0] / 255.0;
    const double green = rgb[1] / 255.0;
    const double blue = rgb[2] / 255.0;
    const double maximum = std::max({red, green, blue});
    const double minimum = std::min({red, green, blue});
    const double lightness = (maximum + minimum) / 2.0;
    const double delta = maximum - minimum;
    if (delta == 0) return {0, 0, lightness};
    const double saturation = delta / (1.0 - std::abs(2.0 * lightness - 1.0));
    double hue = 0;
    if (maximum == red) hue = std::fmod((green - blue) / delta + 6.0, 6.0);
    else if (maximum == green) hue = (blue - red) / delta + 2.0;
    else hue = (red - green) / delta + 4.0;
    return {std::fmod(hue * 60.0, 360.0), saturation, lightness};
}

Rgb hslToRgb(double hue, double saturation, double lightness)
{
    const double chroma = (1.0 - std::abs(2.0 * lightness - 1.0)) * saturation;
    const double segment = std::fmod(hue + 360.0, 360.0) / 60.0;
    const double secondary = chroma * (1.0 - std::abs(std::fmod(segment, 2.0) - 1.0));
    std::array<double, 3> values{};
    if (segment < 1) values = {chroma, secondary, 0};
    else if (segment < 2) values = {secondary, chroma, 0};
    else if (segment < 3) values = {0, chroma, secondary};
    else if (segment < 4) values = {0, secondary, chroma};
    else if (segment < 5) values = {secondary, 0, chroma};
    else values = {chroma, 0, secondary};
    const double offset = lightness - chroma / 2.0;
    return {channel((values[0] + offset) * 255.0), channel((values[1] + offset) * 255.0),
            channel((values[2] + offset) * 255.0)};
}

Rgb clampLightness(Rgb rgb)
{
    const Hsl hsl = rgbToHsl(rgb);
    return hsl.lightness <= kMaxEdgeLightness ? rgb
        : hslToRgb(hsl.hue, hsl.saturation, kMaxEdgeLightness);
}

double relativeLuminance(Rgb rgb)
{
    std::array<double, 3> channels{};
    for (int i = 0; i < 3; ++i) {
        const double value = rgb[i] / 255.0;
        channels[i] = value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
    }
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

Rgb readableAccentText(Rgb accent)
{
    constexpr Rgb dark{18, 5, 7};
    const double luminance = relativeLuminance(accent);
    const double whiteContrast = 1.05 / (luminance + 0.05);
    const double darkContrast = (luminance + 0.05) / (relativeLuminance(dark) + 0.05);
    return darkContrast >= whiteContrast ? dark : Rgb{255, 255, 255};
}

std::array<std::pair<const char *, Rectangle>, 12> rectangles(int width, int height)
{
    const double shortest = std::min(width, height);
    const int edge = std::min(int(shortest), int(std::clamp(std::round(shortest * 0.035), 2.0, 28.0)));
    const int strip = std::min(int(shortest), int(std::clamp(std::round(shortest * 0.007), 1.0, 7.0)));
    // The corners survive anti-aliasing; a one-pixel cover cannot afford a two-pixel corner.
    const int corner = std::min(int(shortest), int(std::clamp(std::round(shortest * 0.14), 8.0, 112.0)));
    return {{{"left", {0, 0, edge, height}}, {"right", {width - edge, 0, edge, height}},
             {"top", {0, 0, width, edge}}, {"bottom", {0, height - edge, width, edge}},
             {"leftStrip", {0, 0, strip, height}}, {"rightStrip", {width - strip, 0, strip, height}},
             {"topStrip", {0, 0, width, strip}}, {"bottomStrip", {0, height - strip, width, strip}},
             {"topLeft", {0, 0, corner, corner}}, {"topRight", {width - corner, 0, corner, corner}},
             {"bottomRight", {width - corner, height - corner, corner, corner}},
             {"bottomLeft", {0, height - corner, corner, corner}}}};
}

quint8 median(const std::array<double, 256> &histogram, double totalWeight)
{
    const double midpoint = totalWeight / 2.0;
    double accumulated = 0;
    for (int value = 0; value < 256; ++value) {
        accumulated += histogram[value];
        if (accumulated >= midpoint) return static_cast<quint8>(value);
    }
    return 0;
}

Zone summarizeZone(const QImage &image, Rectangle rectangle)
{
    std::array<std::array<double, 256>, 3> histograms{};
    std::array<double, 3> sums{};
    double totalWeight = 0;
    for (int y = rectangle.top; y < rectangle.top + rectangle.height; ++y) {
        const uchar *row = image.constScanLine(y);
        for (int x = rectangle.left; x < rectangle.left + rectangle.width; ++x) {
            const uchar *pixel = row + x * 4;
            const double alpha = pixel[3] / 255.0;
            if (alpha < 0.04) continue;
            totalWeight += alpha;
            for (int i = 0; i < 3; ++i) {
                sums[i] += pixel[i] * alpha;
                histograms[i][pixel[i]] += alpha;
            }
        }
    }
    if (totalWeight == 0) return {kFallback, kFallback, kFallback};
    const Rgb average = clampLightness({channel(sums[0] / totalWeight), channel(sums[1] / totalWeight),
                                        channel(sums[2] / totalWeight)});
    const Rgb middle = clampLightness({median(histograms[0], totalWeight), median(histograms[1], totalWeight),
                                       median(histograms[2], totalWeight)});
    return {average, middle, clampLightness(mixRgb(average, middle, 0.68))};
}

std::pair<double, bool> supportingHue(const QImage &image, double fallbackHue)
{
    const qint64 pixels = qint64(image.width()) * image.height();
    const int stride = std::max(1, int(std::floor(std::sqrt(double(pixels) / 160'000.0))));
    std::array<HueBin, 4096> bins{};
    for (int y = 0; y < image.height(); y += stride) {
        const uchar *row = image.constScanLine(y);
        for (int x = 0; x < image.width(); x += stride) {
            const uchar *pixel = row + x * 4;
            const double alpha = pixel[3] / 255.0;
            if (alpha < 0.12) continue;
            const int key = ((pixel[0] >> 4) << 8) | ((pixel[1] >> 4) << 4) | (pixel[2] >> 4);
            HueBin &bin = bins[key];
            bin.weight += alpha;
            for (int i = 0; i < 3; ++i) bin.sums[i] += pixel[i] * alpha;
        }
    }

    double bestHue = fallbackHue;
    double bestScore = 0;
    bool found = false;
    for (const HueBin &bin : bins) {
        if (bin.weight == 0) continue;
        const Rgb rgb{channel(bin.sums[0] / bin.weight), channel(bin.sums[1] / bin.weight),
                      channel(bin.sums[2] / bin.weight)};
        const Hsl hsl = rgbToHsl(rgb);
        if (hsl.saturation < 0.34 || hsl.lightness < 0.14 || hsl.lightness > 0.84) continue;
        const double difference = std::abs(hsl.hue - fallbackHue);
        const double distance = std::min(difference, 360.0 - difference);
        const double score = std::sqrt(bin.weight) * hsl.saturation * (0.24 + hsl.lightness)
                             * (distance <= 48.0 ? 1.18 : 0.82);
        if (!found || score > bestScore) {
            bestHue = hsl.hue;
            bestScore = score;
            found = true;
        }
    }
    return {bestHue, found};
}

QJsonArray jsonRgb(Rgb rgb)
{
    return {int(rgb[0]), int(rgb[1]), int(rgb[2])};
}

QJsonObject jsonZone(const Zone &zone)
{
    return {{QStringLiteral("average"), jsonRgb(zone.average)},
            {QStringLiteral("median"), jsonRgb(zone.median)},
            {QStringLiteral("seam"), jsonRgb(zone.seam)}};
}

QJsonObject samplePixels(const QImage &image)
{
    QJsonObject zones;
    for (const auto &[name, rectangle] : rectangles(image.width(), image.height()))
        zones.insert(QLatin1String(name), jsonZone(summarizeZone(image, rectangle)));

    const auto seamOf = [&zones](const char *name) -> Rgb {
        const QJsonArray values = zones.value(QLatin1String(name)).toObject().value(QStringLiteral("seam")).toArray();
        return {quint8(values[0].toInt()), quint8(values[1].toInt()), quint8(values[2].toInt())};
    };
    const Rgb seam = clampLightness(weightedRgb({
        {seamOf("leftStrip"), 1.0}, {seamOf("rightStrip"), 1.0},
        {seamOf("topStrip"), 1.0}, {seamOf("bottomStrip"), 1.0},
        {seamOf("topLeft"), 0.5}, {seamOf("topRight"), 0.5},
        {seamOf("bottomRight"), 0.5}, {seamOf("bottomLeft"), 0.5},
    }));
    const Hsl edge = rgbToHsl(seam);
    const auto [supporting, hasSupporting] = supportingHue(image, edge.hue);
    const double accentHue = edge.saturation >= 0.34 ? edge.hue : supporting;
    const bool monochrome = !hasSupporting && edge.saturation < 0.18;
    const double accentSaturation = monochrome ? 0.08 : std::clamp(std::max(edge.saturation, 0.82), 0.82, 1.0);
    const double accentLightness = monochrome ? 0.72 : edge.lightness > 0.72 ? 0.48 : 0.61;
    const Rgb accent = hslToRgb(accentHue, accentSaturation, accentLightness);

    const QJsonObject palette{
        {QStringLiteral("seam"), jsonRgb(seam)},
        {QStringLiteral("accent"), jsonRgb(accent)},
        {QStringLiteral("accentSoft"), jsonRgb(mixRgb(accent, {255, 255, 255}, 0.18))},
        {QStringLiteral("deep"), jsonRgb(mixRgb(seam, {5, 2, 3}, 0.58))},
        {QStringLiteral("ink"), jsonRgb(mixRgb(seam, {4, 2, 3}, 0.8))},
        {QStringLiteral("surface"), jsonRgb(mixRgb(seam, {11, 5, 7}, 0.7))},
        {QStringLiteral("surfaceRaised"), jsonRgb(mixRgb(seam, {28, 10, 13}, 0.56))},
        {QStringLiteral("onAccent"), jsonRgb(readableAccentText(accent))},
    };
    return {{QStringLiteral("method"), QStringLiteral("image-qt-raw-edge-zones-v1")},
            {QStringLiteral("width"), image.width()}, {QStringLiteral("height"), image.height()},
            {QStringLiteral("zones"), zones}, {QStringLiteral("palette"), palette}};
}
} // namespace

QJsonObject sampleArtwork(const QByteArray &encoded)
{
    if (encoded.isEmpty() || encoded.size() > kMaxArtworkBytes) return {};
    QBuffer buffer;
    buffer.setData(encoded);
    if (!buffer.open(QIODevice::ReadOnly)) return {};
    QImageReader reader(&buffer);
    // Apply EXIF orientation before sampling; then move embedded profiles into sRGB.
    reader.setAutoTransform(true);
    const QSize dimensions = reader.size();
    if (!dimensions.isValid() || qint64(dimensions.width()) * dimensions.height() > kMaxArtworkPixels)
        return {};
    QImage image = reader.read();
    if (image.isNull() || qint64(image.width()) * image.height() > kMaxArtworkPixels) return {};
    if (image.colorSpace().isValid())
        image.convertToColorSpace(QColorSpace(QColorSpace::SRgb), QImage::Format_RGBA8888);
    else
        image = image.convertToFormat(QImage::Format_RGBA8888);
    return image.isNull() ? QJsonObject() : samplePixels(image);
}
