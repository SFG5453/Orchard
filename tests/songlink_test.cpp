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
#include <QSignalSpy>
#include <QTest>

class SongLinkTest : public QObject {
  Q_OBJECT

private slots:
  // Testing song.link generation to prove our link transformer is not drinking
  // decaf.
  void resolvesVideoId() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral("dQw4w9WgXcQ")),
             QStringLiteral(
                 "https://song.link/y/dQw4w9WgXcQ")); // GET RICKROLLED1!1!
  }

  void resolvesAlbumBrowseId() {
    BackendInfo backend;
    // OLAK5uy_ IDs are true album playlists that album.link accepts.
    QCOMPARE(backend.songLink(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://album.link/y/OLAK5uy_sample"));
    QCOMPARE(backend.songlinkAlbumUrl(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://album.link/y/OLAK5uy_sample"));
    QCOMPARE(backend.songLinkAlbumUrl(QStringLiteral("OLAK5uy_sample")),
             QStringLiteral("https://album.link/y/OLAK5uy_sample"));
    QCOMPARE(backend.songLink(QStringLiteral("OLAK5uy_sample"), QStringLiteral("album")),
             QStringLiteral("https://album.link/y/OLAK5uy_sample"));
  }

  void stripsPlaylistBrowsePrefix() {
    BackendInfo backend;
    // YouTube's VL prefix is like an appendix: completely useless to external
    // link resolvers. Playlists route to album.link so users get playlist landing pages.
    QCOMPARE(backend.songLink(QStringLiteral("VLPL123456789")),
             QStringLiteral("https://album.link/y/PL123456789"));
    QCOMPARE(backend.songLink(QStringLiteral("PL123456789")),
             QStringLiteral("https://album.link/y/PL123456789"));
  }

  void extractsFromYouTubeUrls() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/watch?v=dQw4w9WgXcQ")),
             QStringLiteral("https://song.link/y/dQw4w9WgXcQ"));
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/playlist?list=PL12345")),
             QStringLiteral("https://album.link/y/PL12345"));
    QCOMPARE(backend.songLink(QStringLiteral(
                 "https://music.youtube.com/playlist?list=OLAK5uy_test")),
             QStringLiteral("https://album.link/y/OLAK5uy_test"));
    QCOMPARE(backend.songLink(QStringLiteral("https://youtu.be/dQw4w9WgXcQ")),
             QStringLiteral("https://song.link/y/dQw4w9WgXcQ"));
  }

  void preservesExistingSongLink() {
    BackendInfo backend;
    QCOMPARE(
        backend.songLink(QStringLiteral("https://song.link/y/dQw4w9WgXcQ")),
        QStringLiteral("https://song.link/y/dQw4w9WgXcQ"));
    QCOMPARE(
        backend.songLink(QStringLiteral("https://album.link/y/OLAK5uy_sample")),
        QStringLiteral("https://album.link/y/OLAK5uy_sample"));
  }

  void handlesEmptyInput() {
    BackendInfo backend;
    QCOMPARE(backend.songLink(QString()), QStringLiteral("https://song.link"));
    QCOMPARE(backend.songlinkAlbumUrl(QString()), QStringLiteral("https://album.link"));
  }

  void copiesWithNoticeSignal() {
    BackendInfo backend;
    QSignalSpy spy(&backend, &BackendInfo::noticeRequested);
    backend.copySongLink(QStringLiteral("dQw4w9WgXcQ"));
    QCOMPARE(spy.count(), 1);
    QCOMPARE(spy.takeFirst().at(0).toString(),
             QStringLiteral("Copied song.link to clipboard"));

    backend.copySongLink(QStringLiteral("OLAK5uy_sample"), QStringLiteral("album"));
    QCOMPARE(spy.count(), 1);
    QCOMPARE(spy.takeFirst().at(0).toString(),
             QStringLiteral("Copied album link to clipboard"));
  }
};

QTEST_MAIN(SongLinkTest)
#include "songlink_test.moc"
