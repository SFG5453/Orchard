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

// Desktop track maps survive the Connect wire format both ways.

#include "connect/connect_tracks.h"

#include <QtTest>

class ConnectTracksTest : public QObject {
  Q_OBJECT

private slots:
  void desktopRoundTrip() {
    const QVariantMap desktop{{"id", "dQw4w9WgXcQ"},
                              {"type", "song"},
                              {"title", "Never Gonna Give You Up"},
                              {"artist", "Rick Astley"},
                              {"artists", QStringList{"Rick Astley"}},
                              {"artistBrowseIds", QStringList{"UCuAXFkgsw1L7xaCfnd5JJOw"}},
                              {"album", "Whenever You Need Somebody"},
                              {"albumId", "MPREb_123"},
                              {"thumbnail", "https://lh3.googleusercontent.com/x=w120-h120"},
                              {"duration", "3:33"},
                              {"explicit", false},
                              {"musicVideoType", "MUSIC_VIDEO_TYPE_ATV"},
                              {"isAudioOnly", true}};
    const QJsonObject wire = connect_tracks::toWire(desktop);
    QCOMPARE(wire.value("duration").toDouble(), 213.0);
    QCOMPARE(wire.value("artwork").toString(), desktop.value("thumbnail").toString());
    QCOMPARE(wire.value("artist_id").toString(), QStringLiteral("UCuAXFkgsw1L7xaCfnd5JJOw"));
    const QVariantMap back = connect_tracks::fromWire(wire);
    for (const char *key : {"id", "type", "title", "artist", "album", "albumId", "thumbnail", "duration",
                            "musicVideoType", "isAudioOnly", "artists", "artistBrowseIds"})
      QCOMPARE(back.value(key), desktop.value(key));
    QCOMPARE(back.value("durationSeconds").toDouble(), 213.0);
  }

  void phoneTrackPlaysHere() {
    // What Android sends: canonical fields only, no desktop hints.
    const QJsonObject wire{{"id", "abc123"},
                           {"title", "Song"},
                           {"artist", "Band"},
                           {"artwork", "https://i.ytimg.com/vi/abc123/hqdefault.jpg"},
                           {"duration", 200.0},
                           {"provider", "youtube"}};
    const QVariantMap track = connect_tracks::fromWire(wire);
    // startTrack refuses anything that is not a song, track or video.
    QCOMPARE(track.value("type").toString(), QStringLiteral("track"));
    QCOMPARE(track.value("artists").toStringList(), QStringList{"Band"});
    QCOMPARE(track.value("duration").toString(), QStringLiteral("3:20"));
    QVERIFY(connect_tracks::fromWire(QJsonObject{{"title", "no id"}}).isEmpty());
  }
};

QTEST_GUILESS_MAIN(ConnectTracksTest)
#include "connect_tracks_test.moc"
