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

#include "local/local_collage.h"
#include "local/local_library.h"
#include "local/local_lyrics.h"
#include "local/local_metadata.h"
#include "local/local_track.h"

#include <QFile>
#include <QImage>
#include <QJsonDocument>
#include <QProcess>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtEndian>
#include <QtTest>

namespace {

// One second of silence at 8 kHz, 16-bit mono: exactly 128 kbps of nothing.
// The quietest song ever recorded, and still better than the b-side.
QString writeWav(const QString &dir, const QString &name) {
  const QString path = dir + QLatin1Char('/') + name;
  QFile file(path);
  if (!file.open(QIODevice::WriteOnly))
    return {};
  const quint32 dataSize = 16000;
  QByteArray header("RIFF");
  const auto le32 = [](quint32 value) {
    QByteArray bytes(4, 0);
    qToLittleEndian(value, bytes.data());
    return bytes;
  };
  const auto le16 = [](quint16 value) {
    QByteArray bytes(2, 0);
    qToLittleEndian(value, bytes.data());
    return bytes;
  };
  header += le32(36 + dataSize) + "WAVEfmt " + le32(16) + le16(1) + le16(1) + le32(8000) + le32(16000) + le16(2) +
            le16(16) + "data" + le32(dataSize);
  file.write(header);
  file.write(QByteArray(static_cast<int>(dataSize), 0));
  return path;
}

QString writePng(const QString &dir, const QString &name, QColor color) {
  QImage image(64, 64, QImage::Format_RGB32);
  image.fill(color);
  const QString path = dir + QLatin1Char('/') + name;
  image.save(path, "PNG");
  return path;
}

bool toolAvailable(const QString &program) {
  QProcess process;
  process.start(program, {QStringLiteral("-version")});
  return process.waitForFinished(5000) && process.exitCode() == 0;
}

} // namespace

class LocalLibraryTest : public QObject {
  Q_OBJECT

  static void settle(LocalLibrary &library) {
    // busyChanged fires on both edges; wait for the quiet one.
    QTRY_VERIFY_WITH_TIMEOUT(!library.busy(), 30000);
  }

private slots:
  void trackIdsAreStableAndPrefixed() {
    const QString id = local::trackIdForPath(QStringLiteral("/music/a.mp3"));
    QVERIFY(local::isLocalTrackId(id));
    QCOMPARE(id, local::trackIdForPath(QStringLiteral("/music/../music/a.mp3")));
    QVERIFY(id != local::trackIdForPath(QStringLiteral("/music/b.mp3")));
    QCOMPARE(local::formatDuration(187), QStringLiteral("3:07"));
    QCOMPARE(local::formatDuration(3723), QStringLiteral("1:02:03"));
  }

  void fileNamesStandInForMissingTags() {
    auto metadata = local::metadataFromFileName(QStringLiteral("/m/01 - Daft Punk - Digital Love.mp3"));
    QCOMPARE(metadata.artist, QStringLiteral("Daft Punk"));
    QCOMPARE(metadata.title, QStringLiteral("Digital Love"));
    metadata = local::metadataFromFileName(QStringLiteral("/m/just_a_title.flac"));
    QVERIFY(metadata.artist.isEmpty());
    QCOMPARE(metadata.title, QStringLiteral("just a title"));
  }

  void probeJsonYieldsTagsAndBitrate() {
    const QByteArray json = R"({
      "streams": [{"codec_type": "video", "codec_name": "mjpeg"},
                  {"codec_type": "audio", "codec_name": "flac", "sample_rate": "44100",
                   "channels": 2, "bits_per_raw_sample": "16"}],
      "format": {"duration": "187.5", "bit_rate": "905000",
                 "tags": {"TITLE": "Digital Love", "Artist": "Daft Punk", "album": "Discovery",
                          "LYRICS": "Last night"}}})";
    const auto metadata = local::parseProbe(QJsonDocument::fromJson(json).object(), 0);
    QVERIFY(metadata.probed);
    QCOMPARE(metadata.title, QStringLiteral("Digital Love"));
    QCOMPARE(metadata.artist, QStringLiteral("Daft Punk"));
    QCOMPARE(metadata.album, QStringLiteral("Discovery"));
    QCOMPARE(metadata.lyrics, QStringLiteral("Last night"));
    QCOMPARE(metadata.codec, QStringLiteral("flac"));
    QCOMPARE(metadata.bitrate, 905000);
    QCOMPARE(metadata.bitDepth, 16);
    QCOMPARE(metadata.sampleRate, 44100);
    QCOMPARE(metadata.durationSeconds, 187.5);
  }

  void bitrateFallsBackToFileSize() {
    const QByteArray json = R"({"streams": [{"codec_type": "audio", "codec_name": "mp3"}],
                                "format": {"duration": "100"}})";
    // 1.6 MB over 100 s is 128 kbps.
    const auto metadata = local::parseProbe(QJsonDocument::fromJson(json).object(), 1600000);
    QCOMPARE(metadata.bitrate, 128000);
  }

  void lrcParsesStampsOffsetsAndOrder() {
    const auto result = local::parseLyrics(QStringLiteral(
        "[ar:Someone]\n[offset:500]\n[00:20.00]Second\n[00:01.50][00:10.00]First <00:02.00>word\n"));
    QCOMPARE(result.value("mode").toString(), QStringLiteral("synced"));
    const QVariantList lines = result.value("lines").toList();
    QCOMPARE(lines.size(), 3);
    QCOMPARE(lines[0].toMap().value("text").toString(), QStringLiteral("First word"));
    QCOMPARE(lines[0].toMap().value("startTime").toDouble(), 1.0); // 1.5 s shifted earlier by 0.5 s.
    QCOMPARE(lines[0].toMap().value("endTime").toDouble(), 9.5);
    QCOMPARE(lines[2].toMap().value("startTime").toDouble(), 19.5);
  }

  void plainAndSrtLyricsParse() {
    const auto plain = local::parseLyrics(QStringLiteral("one\n\ntwo\n"));
    QCOMPARE(plain.value("mode").toString(), QStringLiteral("unsynced"));
    QCOMPARE(plain.value("lines").toList().size(), 2);
    const auto srt = local::parseLyrics(QStringLiteral("1\n00:00:01,000 --> 00:00:03,500\nHello\nthere\n\n"));
    QCOMPARE(srt.value("mode").toString(), QStringLiteral("synced"));
    QCOMPARE(srt.value("lines").toList().first().toMap().value("text").toString(), QStringLiteral("Hello there"));
    QCOMPARE(local::parseLyrics(QStringLiteral("  \n")).value("status").toString(), QStringLiteral("unavailable"));
  }

  void collageFillsTheSquare() {
    QImage a(10, 20, QImage::Format_RGB32), b(20, 10, QImage::Format_RGB32);
    a.fill(Qt::red);
    b.fill(Qt::blue);
    QVERIFY(local::buildCollage({}).isNull());
    const QImage single = local::buildCollage({a}, 64);
    QCOMPARE(single.size(), QSize(64, 64));
    QCOMPARE(single.pixelColor(60, 60), QColor(Qt::red));
    const QImage four = local::buildCollage({a, b, b, a, a}, 64);
    QCOMPARE(four.pixelColor(4, 4), QColor(Qt::red));
    QCOMPARE(four.pixelColor(60, 4), QColor(Qt::blue));
    QCOMPARE(four.pixelColor(4, 60), QColor(Qt::blue));
    QCOMPARE(four.pixelColor(60, 60), QColor(Qt::red));
    // Three covers repeat the first one in the last cell.
    const QImage three = local::buildCollage({a, b, b}, 64);
    QCOMPARE(three.pixelColor(60, 60), QColor(Qt::red));
  }

  void importProbesBitrateAndKeepsOrder() {
    if (!toolAvailable(local::ffprobeProgram()))
      QSKIP("ffprobe is not installed");
    QTemporaryDir music, store;
    const QString first = writeWav(music.path(), QStringLiteral("Artist One - First.wav"));
    const QString second = writeWav(music.path(), QStringLiteral("Second.wav"));
    {
      LocalLibrary library(store.path());
      const QString id = library.createPlaylist(QStringLiteral("  Road trip "), {first, second});
      QVERIFY(local::isLocalPlaylistId(id));
      settle(library);
      const QVariantMap detail = [&] { library.openPlaylist(id); return library.detail(); }();
      QCOMPARE(detail.value("title").toString(), QStringLiteral("Road trip"));
      const QVariantList tracks = detail.value("tracks").toList();
      QCOMPARE(tracks.size(), 2);
      const QVariantMap song = tracks[0].toMap();
      QCOMPARE(song.value("artist").toString(), QStringLiteral("Artist One"));
      QCOMPARE(song.value("title").toString(), QStringLiteral("First"));
      QCOMPARE(song.value("source").toString(), QStringLiteral("local"));
      QVERIFY(song.value("localBitrate").toInt() > 120000 && song.value("localBitrate").toInt() < 140000);
      QVERIFY(qAbs(song.value("durationSeconds").toDouble() - 1.0) < 0.1);

      library.moveTrack(id, 0, 1);
      QCOMPARE(library.detail().value("tracks").toList()[1].toMap().value("title").toString(), QStringLiteral("First"));
      library.removeTrack(id, 0);
      QCOMPARE(library.detail().value("tracks").toList().size(), 1);
    }
    // A fresh library over the same folder finds everything again.
    LocalLibrary reopened(store.path());
    QCOMPARE(reopened.playlists().size(), 1);
    QCOMPARE(reopened.songs().size(), 2);
  }

  void collageFollowsCoversUntilTheUserPicksOne() {
    if (!toolAvailable(local::ffprobeProgram()))
      QSKIP("ffprobe is not installed");
    QTemporaryDir music, store, art;
    QStringList files;
    for (int i = 0; i < 4; ++i)
      files << writeWav(music.path(), QStringLiteral("t%1.wav").arg(i));
    LocalLibrary library(store.path());
    const QString id = library.createPlaylist(QStringLiteral("Mix"), QVariantList(files.begin(), files.end()));
    settle(library);
    library.openPlaylist(id);
    QVERIFY(library.detail().value("thumbnail").toString().isEmpty()); // No art yet, no collage.

    const QVariantList tracks = library.detail().value("tracks").toList();
    const QStringList colors{QStringLiteral("#ff0000"), QStringLiteral("#00ff00"), QStringLiteral("#0000ff"),
                             QStringLiteral("#ffff00")};
    for (int i = 0; i < 4; ++i) {
      library.setTrackCover(tracks[i].toMap().value("id").toString(),
                            writePng(art.path(), QStringLiteral("c%1.png").arg(i), QColor(colors[i])));
      settle(library);
    }
    const QString collage = QUrl(library.detail().value("thumbnail").toString()).toLocalFile();
    QVERIFY2(QFile::exists(collage), "collage should exist once four covers do");
    QCOMPARE(QImage(collage).size(), QSize(640, 640));

    library.setPlaylistCover(id, writePng(art.path(), QStringLiteral("mine.png"), QColor(Qt::white)));
    settle(library);
    QVERIFY(!QFile::exists(collage));
    QVERIFY(library.detail().value("hasCustomCover").toBool());
    library.clearPlaylistCover(id);
    QVERIFY(QFile::exists(QUrl(library.detail().value("thumbnail").toString()).toLocalFile()));
  }

  void customLyricsBeatEmbeddedOnes() {
    if (!toolAvailable(local::ffprobeProgram()))
      QSKIP("ffprobe is not installed");
    QTemporaryDir music, store;
    LocalLibrary library(store.path());
    library.importFiles({writeWav(music.path(), QStringLiteral("s.wav"))});
    settle(library);
    const QString id = library.songs().first().toMap().value("id").toString();
    QCOMPARE(library.lyricsFor(id).value("status").toString(), QStringLiteral("unavailable"));
    QFile lrc(music.path() + QStringLiteral("/s.lrc"));
    QVERIFY(lrc.open(QIODevice::WriteOnly));
    lrc.write("[00:01.00]hello\n");
    lrc.close();
    library.setTrackLyrics(id, lrc.fileName());
    QCOMPARE(library.lyricsFor(id).value("mode").toString(), QStringLiteral("synced"));
    library.clearTrackLyrics(id);
    QCOMPARE(library.lyricsFor(id).value("status").toString(), QStringLiteral("unavailable"));
  }
};

QTEST_GUILESS_MAIN(LocalLibraryTest)
#include "local_library_test.moc"
