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

#include "playback/music_video_match.h"
#include <QTest>

namespace {

QJsonObject video(const QString &id, const QString &title, const QString &artist, int seconds,
                  const QString &type = QStringLiteral("MUSIC_VIDEO_TYPE_OMV"), bool isExplicit = false) {
  return QJsonObject{{QStringLiteral("id"), id},
                     {QStringLiteral("title"), title},
                     {QStringLiteral("artist"), artist},
                     {QStringLiteral("durationSeconds"), seconds},
                     {QStringLiteral("musicVideoType"), type},
                     {QStringLiteral("explicit"), isExplicit}};
}

QVariantMap song(int seconds = 243) {
  return QVariantMap{{QStringLiteral("id"), QStringLiteral("songAudio01")},
                     {QStringLiteral("type"), QStringLiteral("song")},
                     {QStringLiteral("title"), QStringLiteral("Midnight City")},
                     {QStringLiteral("artist"), QStringLiteral("M83")},
                     {QStringLiteral("durationSeconds"), seconds}};
}

} // namespace

class MusicVideoMatchTest : public QObject {
  Q_OBJECT

private slots:
  void stripsVideoDecorations() {
    QCOMPARE(musicvideo::normalizedTitle(QStringLiteral("Midnight City (Official Video)")),
             QStringLiteral("midnight city"));
    QCOMPARE(musicvideo::normalizedTitle(QStringLiteral("Midnight City [4K Remaster]")),
             QStringLiteral("midnight city"));
    // Version tags that are not video decorations stay part of the title.
    QCOMPARE(musicvideo::normalizedTitle(QStringLiteral("Midnight City (Live)")),
             QStringLiteral("midnight city live"));
  }

  void videoRowsAreTheirOwnVideo() {
    QVariantMap row = song();
    row.insert(QStringLiteral("musicVideoType"), QStringLiteral("MUSIC_VIDEO_TYPE_OMV"));
    QCOMPARE(musicvideo::directVideoId(row), QStringLiteral("songAudio01"));
    QVERIFY(musicvideo::directVideoId(song()).isEmpty());
  }

  void readsVideoSection() {
    const QJsonObject result{{QStringLiteral("sections"), QJsonArray{
        QJsonObject{{QStringLiteral("key"), QStringLiteral("songs")}, {QStringLiteral("items"), QJsonArray{}}},
        QJsonObject{{QStringLiteral("key"), QStringLiteral("videos")},
                    {QStringLiteral("items"), QJsonArray{video(QStringLiteral("a"), QStringLiteral("x"), QStringLiteral("y"), 1)}}}}}};
    QCOMPARE(musicvideo::videoCandidates(result).size(), 1);
  }

  void prefersOfficialSameArtist() {
    const QJsonArray candidates{
        video(QStringLiteral("cover"), QStringLiteral("Midnight City"), QStringLiteral("Someone Else"), 243,
              QStringLiteral("MUSIC_VIDEO_TYPE_UGC")),
        video(QStringLiteral("official"), QStringLiteral("Midnight City (Official Video)"), QStringLiteral("M83"), 245)};
    QCOMPARE(musicvideo::bestVideoId(song(), candidates), QStringLiteral("official"));
  }

  void acceptsIntrosAndOutros() {
    const QJsonArray candidates{
        video(QStringLiteral("intro"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 290)};
    QCOMPARE(musicvideo::bestVideoId(song(), candidates), QStringLiteral("intro"));
    // Without a song runtime there is nothing to compare against.
    QCOMPARE(musicvideo::bestVideoId(song(0), candidates), QStringLiteral("intro"));
  }

  void rejectsAnotherRuntime() {
    const QJsonArray candidates{
        video(QStringLiteral("extended"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 420)};
    QVERIFY(musicvideo::bestVideoId(song(), candidates).isEmpty());
  }

  void prefersCloserRuntime() {
    const QJsonArray candidates{
        video(QStringLiteral("long"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 300),
        video(QStringLiteral("close"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 246)};
    QCOMPARE(musicvideo::bestVideoId(song(), candidates), QStringLiteral("close"));
  }

  void keepsExplicitBoundary() {
    const QJsonArray candidates{
        video(QStringLiteral("dirty"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 243,
              QStringLiteral("MUSIC_VIDEO_TYPE_OMV"), true)};
    QVERIFY(musicvideo::bestVideoId(song(), candidates).isEmpty());
  }

  void ignoresAudioOnlyRows() {
    const QJsonArray candidates{
        video(QStringLiteral("atv"), QStringLiteral("Midnight City"), QStringLiteral("M83"), 243,
              QStringLiteral("MUSIC_VIDEO_TYPE_ATV"))};
    QVERIFY(musicvideo::bestVideoId(song(), candidates).isEmpty());
  }
};

QTEST_MAIN(MusicVideoMatchTest)
#include "music_video_match_test.moc"
