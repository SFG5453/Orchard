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

#include "playback/autoplay_select.h"
#include "playback/playback_helpers.h"
#include <QTest>

namespace {

QVariantMap track(const QString &id, const QString &title, const QString &artist, int seconds = 200,
                  const QString &type = QString(), bool generated = false) {
  return QVariantMap{{"id", id},
                     {"title", title},
                     {"artist", artist},
                     {"durationSeconds", seconds},
                     {"musicVideoType", type},
                     {"isAudioOnly", type == QLatin1String("MUSIC_VIDEO_TYPE_ATV")},
                     {"autoplayGenerated", generated}};
}

QStringList ids(const QList<QVariantMap> &tracks) {
  QStringList out;
  for (const auto &t : tracks)
    out << t.value("id").toString();
  return out;
}

} // namespace

class AutoplaySelectTest : public QObject {
  Q_OBJECT
private slots:
  void alternateCutsOfQueuedSongAreDropped() {
    const auto picked = autoplay::select({track("a", "Same Song", "Artist")},
                                         {track("b", "Same Song (Official Video)", "Artist", 205)}, 20);
    QVERIFY(picked.isEmpty());
  }

  void collaboratorSuffixMatches() {
    QVERIFY(autoplay::isSameSong(track("a", "Song", "Artist"), track("b", "Song (feat. Guest)", "Artist ft. Guest")));
  }

  void candidatesCollapseToAlbumCut() {
    const auto picked = autoplay::select({}, {track("v", "Song", "Artist", 200, "MUSIC_VIDEO_TYPE_OMV"),
                                              track("x", "Other", "Artist"),
                                              track("a", "Song", "Artist", 201, "MUSIC_VIDEO_TYPE_ATV")}, 20);
    QCOMPARE(ids(picked), (QStringList{"a", "x"}));
  }

  void longVersionsAndOtherArtistsRemain() {
    QVERIFY(!autoplay::isSameSong(track("a", "Song", "Artist", 200), track("b", "Song", "Artist", 400)));
    QVERIFY(!autoplay::isSameSong(track("a", "Song", "Artist"), track("b", "Song", "Someone Else")));
  }

  void compoundDescriptorsMatch() {
    QVERIFY(autoplay::isSameSong(track("a", "I Must Apologise", "PinkPantheress", 109, "MUSIC_VIDEO_TYPE_ATV"),
                                 track("b", "I must apologise (Visualiser Video)", "PinkPantheress", 109,
                                       "MUSIC_VIDEO_TYPE_OMV")));
    QVERIFY(autoplay::isSameSong(track("a", "Song", "Artist"), track("b", "Song - Official Lyric Video", "Artist")));
  }

  void musicVideoLengthIgnoredAgainstAudio() {
    QVERIFY(autoplay::isSameSong(track("a", "Shirt", "SZA", 182, "MUSIC_VIDEO_TYPE_ATV"),
                                 track("b", "Shirt", "SZA", 294, "MUSIC_VIDEO_TYPE_OMV")));
    QVERIFY(!autoplay::isSameSong(track("a", "Song", "Artist", 200, "MUSIC_VIDEO_TYPE_ATV"),
                                  track("b", "Song", "Artist", 400, "MUSIC_VIDEO_TYPE_ATV")));
  }

  void generatedMixCollapsesVariants() {
    QVERIFY(autoplay::isGeneratedMix("RDTMAK5uy_abc"));
    QVERIFY(autoplay::isGeneratedMix("VLRDCLAK5uy_abc"));
    QVERIFY(!autoplay::isGeneratedMix("PLabc"));
    const QVariantList rows{track("v", "AAA", "FLO", 363, "MUSIC_VIDEO_TYPE_OMV"), track("x", "Other", "FLO"),
                            track("a", "AAA", "FLO", 205, "MUSIC_VIDEO_TYPE_ATV"),
                            track("y", "Other (Official Video)", "FLO", 210, "MUSIC_VIDEO_TYPE_OMV")};
    QStringList pinned;
    for (const auto &t : autoplay::collapseMix(rows, {}, 1))
      pinned << t.toMap().value("id").toString();
    QCOMPARE(pinned, (QStringList{"v", "x"}));
    QStringList free;
    for (const auto &t : autoplay::collapseMix(rows, {}, 0))
      free << t.toMap().value("id").toString();
    QCOMPARE(free, (QStringList{"a", "x"}));
  }

  void limitIsHonored() {
    const auto picked = autoplay::select({}, {track("a", "A", "X"), track("b", "B", "X"), track("c", "C", "X")}, 2);
    QCOMPARE(ids(picked), (QStringList{"a", "b"}));
  }

  void bestMixSnapshotSurvivesAppendsOnly() {
    const QVariantList snapshot{track("a", "A", "X"), track("b", "B", "X")};
    QVariantList appended = snapshot;
    appended.append(track("r", "R", "X"));
    QVERIFY(queueExtendsSnapshot(appended, snapshot));
    QVERIFY(!queueExtendsSnapshot({track("z", "Z", "X"), track("b", "B", "X")}, snapshot));
    QVERIFY(!queueExtendsSnapshot({track("a", "A", "X")}, snapshot));
  }

  void generatedDuplicatesOnly() {
    const auto dupes = autoplay::duplicateGeneratedIndices(
        {track("u1", "Same Song", "Artist"), track("u2", "Same Song", "Artist"),
         track("r1", "Same Song (feat. G)", "Artist", 200, "", true), track("r2", "Next", "Artist", 200, "", true),
         track("r3", "Next (Official Audio)", "Artist", 200, "", true)});
    QCOMPARE(dupes, (QList<int>{2, 4}));
  }
};

QTEST_MAIN(AutoplaySelectTest)
#include "autoplay_select_test.moc"
