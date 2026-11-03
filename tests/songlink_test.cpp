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

#include "backend_info.h"
#include <QClipboard>
#include <QSignalSpy>
#include <QTest>

class SongLinkTest : public QObject {
  Q_OBJECT

private slots:
  void resolvesVideoId() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("dQw4w9WgXcQ")),
             QStringLiteral(
                 "https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
  }

  void resolvesAlbumBrowseId() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
    QCOMPARE(backend.songlinkAlbumUrl(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
    QCOMPARE(backend.songLinkAlbumUrl(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
    QCOMPARE(backend.songLink(QStringLiteral("OLAK5uy_sample"), QStringLiteral("album")),
             QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
    QCOMPARE(backend.songLink(QStringLiteral("MPREb_internal"), QStringLiteral("album")),
             QStringLiteral("https://music.youtube.com/browse/MPREb_internal"));
  }

  void stripsPlaylistBrowsePrefix() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("VLPL123456789")),
             QStringLiteral("https://music.youtube.com/playlist?list=PL123456789"));
    QCOMPARE(backend.songLink(QStringLiteral("PL123456789")),
             QStringLiteral("https://music.youtube.com/playlist?list=PL123456789"));
  }

  void extractsFromYouTubeUrls() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/watch?v=dQw4w9WgXcQ")),
             QStringLiteral("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/playlist?list=PL12345")),
             QStringLiteral("https://music.youtube.com/playlist?list=PL12345"));
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/playlist?list=OLAK5uy_test")),
             QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_test"));
    QCOMPARE(backend.songLink(QStringLiteral("https://youtu.be/dQw4w9WgXcQ")),
             QStringLiteral("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
    QCOMPARE(backend.songLink(QStringLiteral("https://www.youtube.com/watch?si=share&v=dQw4w9WgXcQ&t=42")),
             QStringLiteral("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
  }

  void convertsExistingSongLink() {
    BackendInfo backend;
    QCOMPARE(
        backend.songLink(QStringLiteral("https://song.link/y/dQw4w9WgXcQ")),
        QStringLiteral("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
    QCOMPARE(
        backend.songLink(QStringLiteral("https://album.link/y/OLAK5uy_sample")),
        QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
  }

  void resolvesArtistChannel() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("UC123456789"), QStringLiteral("artist")),
             QStringLiteral("https://music.youtube.com/channel/UC123456789"));
  }

  void resolvesAppleMusicIds() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("am.songs.1440857781"), QStringLiteral("song")),
             QStringLiteral("https://music.apple.com/song/1440857781"));
    QCOMPARE(backend.songLink(QStringLiteral("am.artists.605800394"), QStringLiteral("artist")),
             QStringLiteral("https://music.apple.com/artist/605800394"));
    QCOMPARE(backend.songLink(QStringLiteral("am.albums.1440857754"), QStringLiteral("album")),
             QStringLiteral("https://music.apple.com/album/1440857754"));
    QCOMPARE(backend.songLink(QStringLiteral("am.playlists.pl.u-abc"), QStringLiteral("playlist")),
             QStringLiteral("https://music.apple.com/playlist/pl.u-abc"));
    QCOMPARE(backend.songLink(QStringLiteral("am.songs.i.abc")), QString());
  }

  void handlesEmptyInput() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QString()), QStringLiteral("https://music.youtube.com"));
    QCOMPARE(backend.songlinkAlbumUrl(QString()), QStringLiteral("https://music.youtube.com"));
  }

  void copiesWithNoticeSignal() {
    BackendInfo backend;
    QSignalSpy spy(&backend, &BackendInfo::noticeRequested);
    backend.copySongLink(QStringLiteral("dQw4w9WgXcQ"));
    QCOMPARE(QGuiApplication::clipboard()->text(), QStringLiteral("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
    QCOMPARE(spy.count(), 1);
    QCOMPARE(spy.takeFirst().at(0).toString(),
             QStringLiteral("Copied song link to clipboard"));

    backend.copySongLink(QStringLiteral("OLAK5uy_sample"), QStringLiteral("album"));
    QCOMPARE(QGuiApplication::clipboard()->text(), QStringLiteral("https://music.youtube.com/playlist?list=OLAK5uy_sample"));
    QCOMPARE(spy.count(), 1);
    QCOMPARE(spy.takeFirst().at(0).toString(),
             QStringLiteral("Copied album link to clipboard"));
  }
};

QTEST_MAIN(SongLinkTest)
#include "songlink_test.moc"
