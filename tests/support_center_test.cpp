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

#include "support/support_center.h"

#include <QImage>
#include <QRandomGenerator>
#include <QtTest>

namespace {
QVariantMap report(const QString &id, int unread, const QString &latest = QString()) {
  return {{QStringLiteral("id"), id},
          {QStringLiteral("title"), QStringLiteral("Report %1").arg(id)},
          {QStringLiteral("unread"), unread},
          {QStringLiteral("latest"), QVariantMap{{QStringLiteral("title"), latest}}}};
}
} // namespace

class SupportCenterTest final : public QObject {
  Q_OBJECT

private slots:
  void flatScreensStayPng() {
    QImage image(1280, 800, QImage::Format_RGB32);
    image.fill(QColor(20, 24, 22));
    QByteArray mime;
    const QByteArray bytes = SupportCenter::encodeScreenshot(image, &mime);
    QCOMPARE(mime, QByteArray("image/png"));
    QVERIFY(bytes.startsWith("\x89PNG"));
  }

  void noisyScreensFallBackToJpegUnderTheLimit() {
    // Pure noise defeats PNG, like a photo-heavy page on a 4K screen.
    QImage image(3840, 2160, QImage::Format_RGB32);
    auto *random = QRandomGenerator::global();
    for (int y = 0; y < image.height(); ++y) {
      auto *line = reinterpret_cast<quint32 *>(image.scanLine(y));
      for (int x = 0; x < image.width(); ++x)
        line[x] = 0xff000000u | (random->generate() & 0xffffffu);
    }
    QByteArray mime;
    const QByteArray bytes = SupportCenter::encodeScreenshot(image, &mime);
    QCOMPARE(mime, QByteArray("image/jpeg"));
    QVERIFY(!bytes.isEmpty());
    QVERIFY(bytes.size() <= SupportCenter::maxScreenshotBytes);
  }

  void nullImagesEncodeToNothing() {
    QByteArray mime;
    QVERIFY(SupportCenter::encodeScreenshot(QImage(), &mime).isEmpty());
  }

  void previewsHonourAOneSidedSourceSize() {
    QImage image(1400, 900, QImage::Format_RGB32);
    image.fill(Qt::black);
    // Image { sourceSize.width: 352 } asks for 352x0.
    QCOMPARE(SupportImageProvider::fitWithin(image, QSize(352, 0)).size(), QSize(352, 226));
    QCOMPARE(SupportImageProvider::fitWithin(image, QSize(0, 450)).size(), QSize(700, 450));
    QCOMPARE(SupportImageProvider::fitWithin(image, QSize(-1, -1)).size(), image.size());
    QCOMPARE(SupportImageProvider::fitWithin(image, QSize(4000, 0)).size(), image.size());
  }

  void firstLoadSummarizesUnreadTotal() {
    const QVariantList current{report(QStringLiteral("a"), 2, QStringLiteral("sfg replied")), report(QStringLiteral("b"), 1)};
    QCOMPARE(SupportCenter::summarizeUpdates({}, current, true), QStringLiteral("3 new updates on your bug reports"));
    QVERIFY(SupportCenter::summarizeUpdates({}, {report(QStringLiteral("a"), 0)}, true).isEmpty());
  }

  void laterLoadsNameTheChange() {
    const QVariantList before{report(QStringLiteral("a"), 0), report(QStringLiteral("b"), 1)};
    const QVariantList after{report(QStringLiteral("a"), 1, QStringLiteral("Fixed by commit abc1234")), report(QStringLiteral("b"), 1)};
    QCOMPARE(SupportCenter::summarizeUpdates(before, after, false), QStringLiteral("“Report a”: Fixed by commit abc1234"));
    QVERIFY(SupportCenter::summarizeUpdates(after, after, false).isEmpty());
  }

  void severalChangesCollapse() {
    const QVariantList before{report(QStringLiteral("a"), 0), report(QStringLiteral("b"), 0)};
    const QVariantList after{report(QStringLiteral("a"), 1), report(QStringLiteral("b"), 2)};
    QCOMPARE(SupportCenter::summarizeUpdates(before, after, false), QStringLiteral("2 of your bug reports have updates"));
  }
};

QTEST_GUILESS_MAIN(SupportCenterTest)
#include "support_center_test.moc"
