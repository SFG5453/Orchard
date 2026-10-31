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
#include "widevine_host/annex_b.h"
#include "widevine_host/cenc_mp4.h"

#include <QTest>

#include <algorithm>
#include <cstring>

using cenc_mp4::Bytes;

namespace {

void put32(Bytes &out, uint32_t value) {
  for (int shift = 24; shift >= 0; shift -= 8)
    out.push_back(uint8_t(value >> shift));
}

Bytes box(const char *type, const Bytes &body) {
  Bytes out;
  put32(out, uint32_t(8 + body.size()));
  out.insert(out.end(), type, type + 4);
  out.insert(out.end(), body.begin(), body.end());
  return out;
}

Bytes join(std::initializer_list<Bytes> parts) {
  Bytes out;
  for (const Bytes &part : parts)
    out.insert(out.end(), part.begin(), part.end());
  return out;
}

// One moof + mdat holding a single 4-byte sample `fill` at decode time `time`, offset 2.
Bytes pair(uint8_t fill, uint32_t time) {
  Bytes tfhd{0, 0x02, 0, 0};  // default-base-is-moof
  put32(tfhd, 1);
  Bytes tfdt{0, 0, 0, 0};
  put32(tfdt, time);
  Bytes senc{0, 0, 0, 0};
  put32(senc, 1);
  senc.insert(senc.end(), 8, 0xab);
  // Sample duration, size and composition offset, plus a data offset patched below.
  Bytes trun{0, 0, 0x0b, 0x01};
  put32(trun, 1);
  put32(trun, 0);
  put32(trun, 10);
  put32(trun, 4);
  put32(trun, 2);
  Bytes moof = box("moof", box("traf", join({box("tfhd", tfhd), box("tfdt", tfdt), box("senc", senc),
                                             box("trun", trun)})));
  const uint32_t offset = uint32_t(moof.size() + 8);
  for (size_t at = 0; at + 4 <= moof.size(); ++at) {
    if (std::memcmp(&moof[at], "trun", 4) == 0) {
      const size_t field = at + 4 + 8;
      for (int i = 0; i < 4; ++i)
        moof[field + i] = uint8_t(offset >> (24 - 8 * i));
      break;
    }
  }
  return join({moof, box("mdat", Bytes(4, fill))});
}

// An enca sample entry protected with cbcs, constant IV `iv` and key `key`.
Bytes encryptedEntry(uint8_t key, uint8_t iv) {
  Bytes tenc{1, 0, 0, 0, 0, 0x19, 1, 0};
  tenc.insert(tenc.end(), 16, key);
  tenc.push_back(16);
  tenc.insert(tenc.end(), 16, iv);
  Bytes schm{0, 0, 0, 0};
  schm.insert(schm.end(), {'c', 'b', 'c', 's'});
  put32(schm, 0x10000);
  const Bytes sinf = box("sinf", join({box("frma", {'e', 'c', '-', '3'}), box("schm", schm), box("schi", box("tenc", tenc))}));
  return box("enca", join({Bytes(28, 0), sinf}));
}

// An init segment whose track has one sample description per entry.
Bytes initWith(std::initializer_list<Bytes> entries) {
  Bytes stsd{0, 0, 0, 0};
  put32(stsd, uint32_t(entries.size()));
  for (const Bytes &entry : entries)
    stsd.insert(stsd.end(), entry.begin(), entry.end());
  Bytes mdhd(24, 0);
  mdhd[15] = 0x80;  // timescale 128
  const Bytes stbl = box("stbl", box("stsd", stsd));
  return box("moov", box("trak", box("mdia", join({box("mdhd", mdhd), box("minf", stbl)}))));
}

// One moof + mdat whose single 4-byte sample uses sample description `description`.
Bytes describedPair(uint32_t description) {
  Bytes tfhd{0, 0x02, 0, 0x02};
  put32(tfhd, 1);
  put32(tfhd, description);
  Bytes senc{0, 0, 0, 0};
  put32(senc, 1);
  Bytes trun{0, 0, 0x02, 0x01};
  put32(trun, 1);
  put32(trun, 0);
  put32(trun, 4);
  Bytes moof = box("moof", box("traf", join({box("tfhd", tfhd), box("senc", senc), box("trun", trun)})));
  const uint32_t offset = uint32_t(moof.size() + 8);
  const auto at = std::search(moof.begin(), moof.end(), "trun", "trun" + 4) - moof.begin();
  for (int i = 0; i < 4; ++i)
    moof[size_t(at) + 12 + size_t(i)] = uint8_t(offset >> (24 - 8 * i));
  return join({moof, box("mdat", Bytes(4, 0x55))});
}

} // namespace

class WidevineHostTest final : public QObject {
  Q_OBJECT

private slots:
  void clearsEveryPairInASegment() {
    Bytes segment = join({pair(0x11, 100), pair(0x22, 110)});
    cenc_mp4::Track track;
    track.scheme = cenc_mp4::fourcc("cenc");
    track.perSampleIvSize = 8;
    QList<QPair<int, qint64>> seen;
    const std::string error = cenc_mp4::clearFragment(segment, track, [&](cenc_mp4::Sample &sample) {
      seen.append({sample.data[0], sample.time});
      std::memset(sample.data, 0x99, sample.size);
      return std::string();
    });
    QCOMPARE(QString::fromStdString(error), QString());
    QCOMPARE(seen, (QList<QPair<int, qint64>>{{0x11, 102}, {0x22, 112}}));
    const std::string text(segment.begin(), segment.end());
    QCOMPARE(text.find("senc"), std::string::npos);
    QCOMPARE(std::count(segment.begin(), segment.end(), uint8_t(0x99)), 8);
  }

  void keysEachSampleDescriptionApart() {
    Bytes init = initWith({encryptedEntry(0xa1, 0x01), encryptedEntry(0xa2, 0x02)});
    cenc_mp4::Track track;
    QCOMPARE(QString::fromStdString(cenc_mp4::clearInit(init, track)), QString());
    QCOMPARE(track.descriptions.size(), size_t(2));
    QCOMPARE(track.keyId[0], uint8_t(0xa1));
    const std::string text(init.begin(), init.end());
    QCOMPARE(text.find("enca"), std::string::npos);
    for (const uint32_t description : {1u, 2u}) {
      Bytes fragment = describedPair(description);
      uint8_t key = 0, iv = 0;
      const std::string error = cenc_mp4::clearFragment(fragment, track, [&](cenc_mp4::Sample &sample) {
        key = sample.keyId[0];
        iv = sample.iv[0];
        return std::string();
      });
      QCOMPARE(QString::fromStdString(error), QString());
      QCOMPARE(key, uint8_t(0xa0 + description));
      QCOMPARE(iv, uint8_t(description));
    }
    Bytes unknown = describedPair(3);
    QVERIFY(!cenc_mp4::clearFragment(unknown, track, [](cenc_mp4::Sample &) { return std::string(); }).empty());
  }

  void annexBPrependsParameterSetsToIdr() {
    // avcC: version, profile, compat, level, 4-byte lengths, one SPS {0x67,1}, one PPS {0x68,2}.
    const std::vector<uint8_t> avcC{1, 100, 0, 31, 0xff, 0xe1, 0, 2, 0x67, 1, 1, 0, 2, 0x68, 2};
    Bytes data{0, 0, 0, 2, 0x65, 7, 0, 0, 0, 1, 0x01};
    cenc_mp4::Sample sample{data.data(), uint32_t(data.size()), {}, 16, {{6, 5}}, 0};
    Bytes out;
    std::vector<std::pair<uint32_t, uint32_t>> subsamples;
    QCOMPARE(QString::fromStdString(annex_b::convert(sample, avcC, out, subsamples)), QString());
    const Bytes expected{0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 7, 0, 0, 0, 1, 0x01};
    QCOMPARE(out, expected);
    QCOMPARE(subsamples.front(), (std::pair<uint32_t, uint32_t>{18, 5}));
  }

  void annexBLeavesOtherSamplesAlone() {
    Bytes data{0, 0, 0, 2, 0x41, 7};
    cenc_mp4::Sample sample{data.data(), uint32_t(data.size()), {}, 16, {{5, 1}}, 0};
    Bytes out;
    std::vector<std::pair<uint32_t, uint32_t>> subsamples;
    QCOMPARE(QString::fromStdString(annex_b::convert(sample, {1, 100, 0, 31, 0xff, 0xe0, 0}, out, subsamples)),
             QString());
    QCOMPARE(out, (Bytes{0, 0, 0, 1, 0x41, 7}));
    QCOMPARE(subsamples.front(), (std::pair<uint32_t, uint32_t>{5, 1}));
  }
};

QTEST_GUILESS_MAIN(WidevineHostTest)
#include "widevine_host_test.moc"
