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
#include "playback/fmp4_index.h"

#include <QtEndian>
#include <QtTest>

namespace {

QByteArray be32(quint32 value) {
  QByteArray out(4, Qt::Uninitialized);
  qToBigEndian(value, out.data());
  return out;
}

QByteArray be64(quint64 value) {
  QByteArray out(8, Qt::Uninitialized);
  qToBigEndian(value, out.data());
  return out;
}

QByteArray box(const char *type, const QByteArray &payload) {
  return be32(quint32(8 + payload.size())) + QByteArray(type, 4) + payload;
}

// Init segment for one track, followed by a first moof whose tfhd carries `tfhdFlags`.
QByteArray head(quint32 tfhdFlags, int *initLength) {
  const QByteArray tkhd = box("tkhd", be32(0) + be32(0) + be32(0) + be32(7) + QByteArray(76, 0));
  const QByteArray mdhd = box("mdhd", be32(0) + be32(0) + be32(0) + be32(24) + be32(0) + be32(0));
  const QByteArray trak = box("trak", tkhd + box("mdia", mdhd));
  const QByteArray init = box("ftyp", "iso6") + box("moov", box("mvhd", QByteArray(100, 0)) + trak +
                                                    box("mvex", box("trex", QByteArray(24, 0))));
  *initLength = int(init.size());
  const QByteArray traf = box("traf", box("tfhd", be32(tfhdFlags) + be32(7)) +
                                          box("tfdt", be32(0x01000000) + be64(240)));
  return init + box("moof", box("mfhd", be32(0) + be32(1)) + traf);
}

QString playlist(int initLength) {
  return QStringLiteral(
             "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"v-.mp4\",BYTERANGE=\"%1@0\"\n"
             "#EXTINF:2.75000,\n#EXT-X-BYTERANGE:1000@%1\nv-.mp4\n"
             "#EXTINF:2.79167,\n#EXT-X-BYTERANGE:2000\nv-.mp4\n#EXT-X-ENDLIST\n")
      .arg(initLength);
}

} // namespace

class Fmp4IndexTest : public QObject {
  Q_OBJECT
private slots:
  void parsesContiguousByteRanges() {
    const auto layout = fmp4index::parsePlaylist(playlist(900));
    QVERIFY(layout.isValid());
    QCOMPARE(layout.initLength, qint64(900));
    QCOMPARE(layout.segments.size(), 2);
    QCOMPARE(layout.segments[1].offset, qint64(1900));
    QCOMPARE(layout.fileLength(), qint64(3900));
  }

  void rejectsPlaylistsItCannotIndex_data() {
    QTest::addColumn<QString>("text");
    QTest::newRow("gap") << playlist(900).replace("1000@900", "1000@901");
    QTest::newRow("live") << playlist(900).remove("#EXT-X-ENDLIST\n");
    QTest::newRow("two files") << playlist(900).replace("2000\nv-.mp4", "2000\nw-.mp4");
    QTest::newRow("encrypted") << playlist(900).replace("#EXTINF:2.75", "#EXT-X-KEY:METHOD=SAMPLE-AES\n#EXTINF:2.75");
  }

  void rejectsPlaylistsItCannotIndex() {
    QFETCH(QString, text);
    QVERIFY(!fmp4index::parsePlaylist(text).isValid());
  }

  void buildsSidxFromTheFirstFragment() {
    int initLength = 0;
    const QByteArray data = head(0x020000, &initLength);
    const QByteArray sidx = fmp4index::buildSidx(data, fmp4index::parsePlaylist(playlist(initLength)));
    QCOMPARE(sidx.size(), 40 + 2 * 12);
    QCOMPARE(sidx.mid(4, 4), QByteArray("sidx"));
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 12), 7u);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 16), 24u);
    QCOMPARE(qFromBigEndian<quint64>(sidx.constData() + 20), 240ull);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 36), 2u);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 40), 1000u);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 44), 66u);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 52), 2000u);
    QCOMPARE(qFromBigEndian<quint32>(sidx.constData() + 56), 67u);
  }

  void refusesAbsoluteDataOffsets() {
    int initLength = 0;
    const QByteArray data = head(0x000001, &initLength);
    QVERIFY(fmp4index::buildSidx(data, fmp4index::parsePlaylist(playlist(initLength))).isEmpty());
  }

  void refusesAHeadWithoutAFragment() {
    int initLength = 0;
    const QByteArray data = head(0x020000, &initLength);
    QVERIFY(fmp4index::buildSidx(data.left(initLength), fmp4index::parsePlaylist(playlist(initLength))).isEmpty());
  }
};

QTEST_GUILESS_MAIN(Fmp4IndexTest)
#include "fmp4_index_test.moc"
