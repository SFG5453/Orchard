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

#include "playback/apple_music_playlist.h"
#include <QTest>
#include <QtEndian>

using namespace apple_music_playlist;

namespace {

const QUrl kPlaylist(QStringLiteral("https://aod-ssl.itunes.apple.com/itunes-assets/x/P697195462_default.m3u8"));

// The shape of Apple's 28:ctrp256 playlists, shortened to three segments.
QByteArray playlist(const QByteArray &secondRange = "326173@382710") {
  return "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n"
         "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:;base64,AAAAACmOW8YAHbtJTWkWjg==\"\n"
         "#EXT-X-MAP:URI=\"mzaf_A697195462.rmhq.aac.wa.mp4\",BYTERANGE=\"1006@0\"\n"
         "#EXTINF:9.98458,\t\n#EXT-X-BYTERANGE:381704@1006\nmzaf_A697195462.rmhq.aac.wa.mp4\n"
         "#EXTINF:9.98458,\t\n#EXT-X-BYTERANGE:" + secondRange + "\nmzaf_A697195462.rmhq.aac.wa.mp4\n"
         "#EXT-X-ENDLIST\n";
}

const QUrl kVideoMedia(QStringLiteral("https://mvod.itunes.apple.com/itunes-assets/v/P1_audio_gr256.m3u8?accessKey=k"));

// A music video's audio rendition: an init file and one file per fragment.
QByteArray videoAudioPlaylist() {
  return "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n"
         "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://itunes.apple.com/p1/c6\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
         "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAAA0FjR4YzYgICAgICBI88aJmwY=\","
         "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\",KEYFORMATVERSIONS=\"1\"\n"
         "#EXT-X-MAP:URI=\"P1_audio-0.mp4\"\n"
         "#EXTINF:5.99075,\t\n#EXT-X-BITRATE:135\nP1_audio-1.m4s\n"
         "#EXTINF:4.5,\t\nP1_audio-2.m4s\n#EXT-X-ENDLIST\n";
}

QByteArray masterPlaylist() {
  return "#EXTM3U\n#EXT-X-VERSION:7\n"
         "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio-HE-stereo-64\",NAME=\"English\",URI=\"a64.m3u8\"\n"
         "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio-stereo-256\",NAME=\"English\",URI=\"a256.m3u8\"\n"
         "#EXT-X-STREAM-INF:BANDWIDTH=408333,RESOLUTION=480x270,AUDIO=\"audio-HE-stereo-64\","
         "ALLOWED-CPC=\"com.microsoft.playready:SL2000,urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_SOFTWARE\"\nv270.m3u8\n"
         "#EXT-X-STREAM-INF:BANDWIDTH=2688747,RESOLUTION=864x486,AUDIO=\"audio-stereo-256\","
         "ALLOWED-CPC=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_SOFTWARE\"\nv486.m3u8\n"
         "#EXT-X-STREAM-INF:BANDWIDTH=4621667,RESOLUTION=1280x720,AUDIO=\"audio-stereo-256\","
         "ALLOWED-CPC=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_HARDWARE\"\nv720.m3u8\n";
}

} // namespace

class ApplePlaylistTest final : public QObject {
  Q_OBJECT

private slots:
  void readsRangesKeyAndDuration() {
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, playlist(), parsed), QString());
    QCOMPARE(parsed.media, QUrl(QStringLiteral("https://aod-ssl.itunes.apple.com/itunes-assets/x/mzaf_A697195462.rmhq.aac.wa.mp4")));
    QCOMPARE(parsed.ranges.size(), 3);
    QCOMPARE(parsed.ranges.at(0), (QPair<qint64, qint64>{0, 1006}));
    QCOMPARE(parsed.ranges.at(2), (QPair<qint64, qint64>{382710, 326173}));
    QCOMPARE(parsed.keyUri, QStringLiteral("data:;base64,AAAAACmOW8YAHbtJTWkWjg=="));
    QVERIFY(qAbs(parsed.duration - 19.96916) < 1e-6);
    QCOMPARE(parsed.durations.size(), 2);
  }

  void servesTheIndexAfterTheInitSegment() {
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, playlist(), parsed), QString());
    const qint64 index = parsed.indexBytes();
    QCOMPARE(index, qint64(56));
    QCOMPARE(parsed.served(0), (QPair<qint64, qint64>{0, 1006 + index}));
    QCOMPARE(parsed.served(1), (QPair<qint64, qint64>{1006 + index, 381704}));
    QCOMPARE(parsed.servedSize(), 382710 + 326173 + index);
    QCOMPARE(parsed.indexAt(1006), 0);
    QCOMPARE(parsed.indexAt(1006 + index), 1);
    QCOMPARE(parsed.indexAt(parsed.servedSize() - 1), 2);
    QCOMPARE(parsed.indexAt(parsed.servedSize()), -1);
  }

  void indexReferencesEveryFragment() {
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, playlist(), parsed), QString());
    // Minimal tkhd (version 0, track 1) and mdhd (version 0, 44100 Hz).
    QByteArray init(64, '\0');
    init.replace(4, 4, "tkhd");
    init[23] = 1;
    init.replace(36, 4, "mdhd");
    init.replace(52, 4, QByteArray::fromHex("0000ac44"));
    const QByteArray sidx = parsed.segmentIndex(init);
    QCOMPARE(sidx.size(), 56);
    QCOMPARE(sidx.mid(4, 4), QByteArray("sidx"));
    QCOMPARE(sidx.mid(12, 4).toHex(), QByteArray("00000001"));
    QCOMPARE(sidx.mid(16, 4).toHex(), QByteArray("0000ac44"));
    QCOMPARE(sidx.mid(30, 2).toHex(), QByteArray("0002"));
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 32), quint32(381704));
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 44), quint32(326173));
    // 9.98458 s and 19.96916 s at 44100 Hz, rounded at each boundary.
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 36), quint32(440320));
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 48), quint32(440320));
    QVERIFY(parsed.segmentIndex(QByteArray(64, '\0')).isEmpty());
  }

  void rangesWithoutOffsetFollowThePreviousOne() {
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, playlist("326173"), parsed), QString());
    QCOMPARE(parsed.ranges.at(2).first, qint64(382710));
  }

  void rejectsGaps() {
    Playlist parsed;
    QVERIFY(!parse(kPlaylist, playlist("326173@400000"), parsed).isEmpty());
  }

  void buildsWidevinePsshFromKeyId() {
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, playlist(), parsed), QString());
    const QByteArray keyId = QByteArray::fromBase64("AAAAACmOW8YAHbtJTWkWjg==");
    QCOMPARE(parsed.pssh.size(), 50);
    QCOMPARE(parsed.pssh.mid(4, 4), QByteArray("pssh"));
    QCOMPARE(parsed.pssh.mid(12, 16).toHex(), QByteArray("edef8ba979d64acea3c827dcd51d21ed"));
    QCOMPARE(parsed.pssh.mid(32), QByteArray("\x12\x10", 2) + keyId);
  }

  void readsOneFilePerFragment() {
    Playlist parsed;
    QCOMPARE(parse(kVideoMedia, videoAudioPlaylist(), parsed), QString());
    QCOMPARE(parsed.files.size(), 3);
    QCOMPARE(parsed.files.at(2), QUrl(QStringLiteral("https://mvod.itunes.apple.com/itunes-assets/v/P1_audio-2.m4s")));
    QCOMPARE(parsed.durations.size(), 2);
    QVERIFY(parsed.keyUri.startsWith(QStringLiteral("data:text/plain;base64,")));
    QVERIFY(!parsed.pssh.isEmpty());
    QCOMPARE(parsed.rangeHeader(1), QByteArray());
    QCOMPARE(parsed.url(1), parsed.files.at(1));
    parsed.setFileSizes({900, 4000, 3000});
    QCOMPARE(parsed.ranges.at(2), (QPair<qint64, qint64>{4900, 3000}));
    QCOMPARE(parsed.servedSize(), 7900 + parsed.indexBytes());
  }

  void picksSoftwareVideoAndBestAudio() {
    Master master;
    QCOMPARE(parseMaster(QUrl(QStringLiteral("https://x.example/m/master.m3u8")), masterPlaylist(), master), QString());
    QCOMPARE(master.variants.size(), 3);
    QCOMPARE(pickVariant(master, 0)->height, 486);
    QCOMPARE(pickVariant(master, 300)->height, 270);
    // Nothing fits: the smallest software rendition still plays.
    QCOMPARE(pickVariant(master, 100)->height, 270);
    QCOMPARE(pickVariant(master, 0)->url, QUrl(QStringLiteral("https://x.example/m/v486.m3u8")));
    QCOMPARE(pickVariant(master, 0, true)->height, 720);
    QCOMPARE(pickVariant(master, 600, true)->height, 486);
    QCOMPARE(pickAudio(master), QUrl(QStringLiteral("https://x.example/m/a256.m3u8")));
  }

  void ignoresFairPlayKeys() {
    QByteArray text = playlist();
    text.replace("#EXT-X-KEY:", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://itunes.apple.com/P000000000/s1/e1\","
                                "KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXT-X-KEY:");
    Playlist parsed;
    QCOMPARE(parse(kPlaylist, text, parsed), QString());
    QVERIFY(parsed.keyUri.startsWith(QStringLiteral("data:")));
  }
};

QTEST_GUILESS_MAIN(ApplePlaylistTest)
#include "apple_music_playlist_test.moc"
