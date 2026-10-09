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

#include "appearance/artwork_sampler.h"

#include <QBuffer>
#include <QColorSpace>
#include <QImage>
#include <QImageReader>
#include <QJsonArray>
#include <QtTest>

namespace {
QByteArray png(const QImage &image)
{
    QByteArray bytes;
    QBuffer buffer(&bytes);
    if (!buffer.open(QIODevice::WriteOnly) || !image.save(&buffer, "PNG")) return {};
    return bytes;
}

QJsonArray seam(const QJsonObject &sample, const char *zone)
{
    return sample.value(QStringLiteral("zones")).toObject()
        .value(QLatin1String(zone)).toObject().value(QStringLiteral("seam")).toArray();
}
} // namespace

class ArtworkSamplerTest final : public QObject {
    Q_OBJECT
private slots:
    void uniformCover()
    {
        QImage image(32, 32, QImage::Format_RGBA8888);
        image.fill(QColor(143, 0, 2));
        const QJsonObject sample = sampleArtwork(png(image));
        QCOMPARE(sample.value(QStringLiteral("width")).toInt(), 32);
        QCOMPARE(sample.value(QStringLiteral("method")).toString(), QStringLiteral("image-qt-raw-edge-zones-v1"));
        QCOMPARE(sample.value(QStringLiteral("palette")).toObject().value(QStringLiteral("seam")).toArray(),
                 (QJsonArray{143, 0, 2}));
        QCOMPARE(seam(sample, "topStrip"), (QJsonArray{143, 0, 2}));
        QCOMPARE(sample.value(QStringLiteral("palette")).toObject().value(QStringLiteral("onAccent")).toArray(),
                 (QJsonArray{18, 5, 7}));
    }

    void twoToneEdges()
    {
        QImage image(32, 32, QImage::Format_RGBA8888);
        for (int y = 0; y < image.height(); ++y)
            for (int x = 0; x < image.width(); ++x)
                image.setPixelColor(x, y, y < 16 ? QColor(22, 64, 138) : QColor(142, 18, 36));
        const QJsonObject sample = sampleArtwork(png(image));
        QCOMPARE(seam(sample, "topStrip"), (QJsonArray{22, 64, 138}));
        QCOMPARE(seam(sample, "bottomStrip"), (QJsonArray{142, 18, 36}));
    }

    void brightEdgesAndAlpha()
    {
        QImage white(24, 24, QImage::Format_RGBA8888);
        white.fill(Qt::white);
        const QJsonObject sample = sampleArtwork(png(white));
        const QJsonObject palette = sample.value(QStringLiteral("palette")).toObject();
        for (const QJsonValue value : palette.value(QStringLiteral("seam")).toArray())
            QVERIFY(value.toInt() <= 87);
        QVERIFY(palette.value(QStringLiteral("surface")).toArray()[0].toInt() < 50);

        QImage transparent(16, 16, QImage::Format_RGBA8888);
        transparent.fill(Qt::transparent);
        QCOMPARE(sampleArtwork(png(transparent)).value(QStringLiteral("palette")).toObject()
                     .value(QStringLiteral("seam")).toArray(), (QJsonArray{42, 42, 42}));
    }

    void invalidAndTinyImages()
    {
        QVERIFY(sampleArtwork(QByteArrayLiteral("not an image")).isEmpty());
        QVERIFY(sampleArtwork(QByteArray(16 * 1024 * 1024 + 1, 'x')).isEmpty());
        QImage tiny(1, 1, QImage::Format_RGBA8888);
        tiny.fill(QColor(8, 74, 129));
        QCOMPARE(sampleArtwork(png(tiny)).value(QStringLiteral("width")).toInt(), 1);
    }

    void appliesExifOrientation()
    {
        QImage image(16, 32, QImage::Format_RGB32);
        image.fill(QColor(20, 40, 60));
        QByteArray jpeg;
        QBuffer buffer(&jpeg);
        QVERIFY(buffer.open(QIODevice::WriteOnly));
        QVERIFY(image.save(&buffer, "JPEG"));
        // EXIF orientation 6: the cover asks for a quarter turn before its edge audition.
        jpeg.insert(2, QByteArray::fromHex(
            "ffe1002245786966000049492a0008000000010012010300010000000600000000000000"));
        const QJsonObject sample = sampleArtwork(jpeg);
        QCOMPARE(sample.value(QStringLiteral("width")).toInt(), 32);
        QCOMPARE(sample.value(QStringLiteral("height")).toInt(), 16);
    }

    void convertsEmbeddedColorProfile()
    {
        QImage image(16, 16, QImage::Format_RGBA8888);
        image.fill(QColor(32, 96, 32));
        image.setColorSpace(QColorSpace(QColorSpace::DisplayP3));
        const QByteArray bytes = png(image);
        QBuffer buffer;
        buffer.setData(bytes);
        QVERIFY(buffer.open(QIODevice::ReadOnly));
        QImageReader reader(&buffer);
        QImage decoded = reader.read();
        QVERIFY(decoded.colorSpace().isValid());
        decoded.convertToColorSpace(QColorSpace(QColorSpace::SRgb), QImage::Format_RGBA8888);
        const QColor expected = decoded.pixelColor(0, 0);
        QCOMPARE(sampleArtwork(bytes).value(QStringLiteral("palette")).toObject()
                     .value(QStringLiteral("seam")).toArray(),
                 (QJsonArray{expected.red(), expected.green(), expected.blue()}));
    }
};

QTEST_GUILESS_MAIN(ArtworkSamplerTest)
#include "artwork_sampler_test.moc"
