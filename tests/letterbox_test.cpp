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

#include "playback/letterbox.h"
#include <QPainter>
#include <QTest>

namespace {

// A 1920x1080 frame of black with a mid-grey picture inside `content` (pixels).
QImage frame(const QRect &content) {
  QImage image(1920, 1080, QImage::Format_RGB32);
  image.fill(Qt::black);
  QPainter(&image).fillRect(content, QColor(120, 110, 100));
  return image;
}

bool near(double a, double b) { return qAbs(a - b) < 0.02; }

} // namespace

class LetterboxTest : public QObject {
  Q_OBJECT

private slots:
  void findsBakedInLetterbox() {
    // 2.39:1 picture inside a 16:9 frame.
    const QRectF rect = letterbox::contentRect(frame(QRect(0, 138, 1920, 804)));
    QVERIFY(near(rect.top(), 138.0 / 1080));
    QVERIFY(near(rect.height(), 804.0 / 1080));
    QVERIFY(near(rect.width(), 1.0));
  }

  void findsPillarbox() {
    // 4:3 picture inside a 16:9 frame.
    const QRectF rect = letterbox::contentRect(frame(QRect(240, 0, 1440, 1080)));
    QVERIFY(near(rect.left(), 240.0 / 1920));
    QVERIFY(near(rect.width(), 1440.0 / 1920));
    QVERIFY(near(rect.height(), 1.0));
  }

  void ignoresDarkScenes() {
    QVERIFY(letterbox::contentRect(frame(QRect())).isNull());
    // A lamp in a dark room is not a picture boundary.
    QVERIFY(letterbox::contentRect(frame(QRect(800, 450, 300, 200))).isNull());
  }

  void accumulatesAndSnapsToEdges() {
    QRectF rect = letterbox::accumulate({}, QRectF(0.01, 0.13, 0.98, 0.70));
    QCOMPARE(rect.left(), 0.0);
    QCOMPARE(rect.right(), 1.0);
    QVERIFY(near(rect.top(), 0.13));
    rect = letterbox::accumulate(rect, QRectF(0.2, 0.12, 0.5, 0.76));
    QVERIFY(near(rect.top(), 0.12));
    QVERIFY(near(rect.bottom(), 0.88));
    // A dark sample leaves what was seen untouched.
    QCOMPARE(letterbox::accumulate(rect, QRectF()), rect);
  }
};

QTEST_MAIN(LetterboxTest)
#include "letterbox_test.moc"
