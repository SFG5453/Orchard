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

#include "appearance/animated_artwork_service.h"
#include "appearance/animated_artwork_store.h"
#include <QDateTime>
#include <QTemporaryDir>
#include <QTest>

class AnimatedArtworkTest : public QObject {
  Q_OBJECT

private slots:
  // Sequels are not the original. Ask any movie studio.
  void rejectsSequelAlbum() {
    QVERIFY(!AnimatedArtworkService::editionlessMatches(
        QStringLiteral("Placeholder Tapes"),
        QStringLiteral("Placeholder Tapes 2")));
    QVERIFY(!AnimatedArtworkService::editionlessMatches(
        QStringLiteral("Placeholder Tapes 2"),
        QStringLiteral("Placeholder Tapes")));
  }

  void acceptsEditionVariants() {
    const QString base = QStringLiteral("Placeholder Tapes 2");
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        base, QStringLiteral("Placeholder Tapes 2 (Deluxe Edition)")));
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        base, QStringLiteral("Placeholder Tapes 2 [Remastered]")));
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        base, QStringLiteral("Placeholder Tapes 2 - Single")));
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        base, QStringLiteral("placeholder tapes 2!")));
  }

  void keepsMeaningfulDashTitles() {
    QVERIFY(!AnimatedArtworkService::editionlessMatches(
        QStringLiteral("Lorem - Ipsum"), QStringLiteral("Lorem")));
  }

  void stripsFeaturesFromTitles() {
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        QStringLiteral("Test Song (feat. Example Artist)"),
        QStringLiteral("Test Song")));
    QVERIFY(!AnimatedArtworkService::editionlessMatches(
        QStringLiteral("Test Song Reprise"), QStringLiteral("Test Song")));
  }

  // All-bracket titles would strip to nothing and match everything else that did too.
  void bracketOnlyTitlesStillCompare() {
    QVERIFY(AnimatedArtworkService::editionlessMatches(
        QStringLiteral("(Untitled)"), QStringLiteral("(Untitled)")));
    QVERIFY(!AnimatedArtworkService::editionlessMatches(
        QStringLiteral("(Untitled)"), QStringLiteral("[Intro]")));
  }

  void findsMasterManifest() {
    QCOMPARE(AnimatedArtworkService::masterManifestUrl(QStringLiteral(
                 "https://m/HLSVideo221/v4/22/P1473552299_Anull_video_gr290_sdr_1080x1080-.mp4")),
             QStringLiteral("https://m/HLSVideo221/v4/22/P1473552299_default.m3u8"));
    QVERIFY(AnimatedArtworkService::masterManifestUrl(QStringLiteral("https://x/a.mp4")).isEmpty());
  }

  // HEVC and tiny variants are skipped; the cheapest 486 px H.264 wins.
  void picksSmallVariant() {
    const QString manifest = QStringLiteral(
        "#EXTM3U\n"
        "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=313574,CODECS=\"hvc1.2\",RESOLUTION=486x486\nhevc.m3u8\n"
        "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=261826,CODECS=\"avc1.64001f\",RESOLUTION=360x360\ntiny.m3u8\n"
        "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1129044,CODECS=\"avc1.64001f\",RESOLUTION=486x486\nmid.m3u8\n"
        "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=773848,CODECS=\"avc1.64001f\",RESOLUTION=486x486\nlow.m3u8\n"
        "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9868857,CODECS=\"avc1.640020\",RESOLUTION=1080x1080\nbig.m3u8\n");
    QCOMPARE(AnimatedArtworkService::smallVariantMp4(manifest, QStringLiteral("https://m/d/P1_default.m3u8")),
             QStringLiteral("https://m/d/low-.mp4"));
    QVERIFY(AnimatedArtworkService::smallVariantMp4(QStringLiteral("#EXTM3U\n"), QStringLiteral("https://m/x.m3u8")).isEmpty());
  }

  // Found URLs and "none" answers both survive a reopen.
  void storeRoundTrips() {
    QTemporaryDir dir;
    const QString path = dir.filePath(QStringLiteral("art.sqlite"));
    {
      AnimatedArtworkStore store(path);
      store.store(QStringLiteral("a"), QStringLiteral("https://x/a.mp4"), QStringLiteral("boidu"));
      store.store(QStringLiteral("b"), QString(), QString());
    }
    AnimatedArtworkStore store(path);
    QCOMPARE(store.lookup(QStringLiteral("a")), std::optional<QString>(QStringLiteral("https://x/a.mp4")));
    QCOMPARE(store.lookup(QStringLiteral("b")), std::optional<QString>(QString()));
    QVERIFY(!store.lookup(QStringLiteral("c")));
  }

  // "None" goes stale before "found" does; art shows up after release.
  void storeExpiresMissingSooner() {
    QTemporaryDir dir;
    AnimatedArtworkStore store(dir.filePath(QStringLiteral("art.sqlite")));
    const qint64 tenDaysAgo = QDateTime::currentSecsSinceEpoch() - 10LL * 24 * 60 * 60;
    store.store(QStringLiteral("found"), QStringLiteral("https://x/f.mp4"), QStringLiteral("m8tec"), tenDaysAgo);
    store.store(QStringLiteral("none"), QString(), QString(), tenDaysAgo);
    QVERIFY(store.lookup(QStringLiteral("found")));
    QVERIFY(!store.lookup(QStringLiteral("none")));
  }

  void storeForgetsDeadUrls() {
    QTemporaryDir dir;
    AnimatedArtworkStore store(dir.filePath(QStringLiteral("art.sqlite")));
    store.store(QStringLiteral("a"), QStringLiteral("https://x/dead.mp4"), QStringLiteral("boidu"));
    store.store(QStringLiteral("b"), QStringLiteral("https://x/dead.mp4"), QStringLiteral("boidu"));
    QCOMPARE(store.forgetUrl(QStringLiteral("https://x/dead.mp4")), 2);
    QVERIFY(!store.lookup(QStringLiteral("a")));
  }
};

QTEST_GUILESS_MAIN(AnimatedArtworkTest)
#include "animated_artwork_test.moc"
