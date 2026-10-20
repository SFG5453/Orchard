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

#include "cenc_mp4.h"

#include <cstring>

namespace cenc_mp4 {
namespace {

struct Box {
  uint32_t type;
  size_t start;
  size_t body;
  size_t end;
};

uint32_t be32(const Bytes &d, size_t at) {
  return uint32_t(d[at]) << 24 | uint32_t(d[at + 1]) << 16 | uint32_t(d[at + 2]) << 8 | d[at + 3];
}

uint16_t be16(const Bytes &d, size_t at) { return uint16_t(d[at] << 8 | d[at + 1]); }

std::vector<Box> children(const Bytes &d, size_t from, size_t to) {
  std::vector<Box> boxes;
  while (from + 8 <= to) {
    uint64_t size = be32(d, from);
    size_t header = 8;
    if (size == 1) {
      if (from + 16 > to)
        break;
      size = uint64_t(be32(d, from + 8)) << 32 | be32(d, from + 12);
      header = 16;
    } else if (size == 0) {
      size = to - from;
    }
    if (size < header || size > to - from)
      break;
    boxes.push_back({be32(d, from + 4), from, from + header, from + size_t(size)});
    from += size_t(size);
  }
  return boxes;
}

const Box *find(const std::vector<Box> &boxes, uint32_t type) {
  for (const Box &box : boxes)
    if (box.type == type)
      return &box;
  return nullptr;
}

void rename(Bytes &d, const Box &box, uint32_t type) {
  d[box.start + 4] = uint8_t(type >> 24);
  d[box.start + 5] = uint8_t(type >> 16);
  d[box.start + 6] = uint8_t(type >> 8);
  d[box.start + 7] = uint8_t(type);
}

constexpr uint32_t kFree = fourcc("free");

std::string readTenc(const Bytes &d, const Box &tenc, Track &track) {
  if (tenc.end - tenc.body < 24)
    return "tenc is truncated";
  const uint8_t version = d[tenc.body];
  if (version > 0) {
    track.cryptBlocks = d[tenc.body + 5] >> 4;
    track.skipBlocks = d[tenc.body + 5] & 15;
  }
  const bool isProtected = d[tenc.body + 6];
  track.perSampleIvSize = d[tenc.body + 7];
  std::memcpy(track.keyId, &d[tenc.body + 8], 16);
  if (isProtected && track.perSampleIvSize == 0) {
    if (tenc.end - tenc.body < 25)
      return "tenc has no constant IV";
    track.constantIvSize = d[tenc.body + 24];
    if (track.constantIvSize > 16 || tenc.body + 25 + track.constantIvSize > tenc.end)
      return "tenc constant IV is malformed";
    std::memcpy(track.constantIv, &d[tenc.body + 25], track.constantIvSize);
  }
  if (track.perSampleIvSize != 0 && track.perSampleIvSize != 8 && track.perSampleIvSize != 16)
    return "unsupported IV size";
  return {};
}

// Sample entry fields before child boxes: 8 bytes of SampleEntry plus the audio or
// visual fields, whose length depends on the QuickTime sound description version.
size_t entryChildren(const Bytes &d, const Box &entry) {
  if (entry.type == fourcc("encv"))
    return entry.body + 78;
  if (entry.body + 10 > entry.end)
    return entry.end;
  const uint16_t version = be16(d, entry.body + 8);
  return entry.body + (version == 1 ? 44 : version == 2 ? 64 : 28);
}

} // namespace

std::string clearInit(Bytes &init, Track &track) {
  const std::vector<Box> top = children(init, 0, init.size());
  const Box *moov = find(top, fourcc("moov"));
  if (!moov)
    return "init segment has no moov";
  const std::vector<Box> moovKids = children(init, moov->body, moov->end);
  for (const Box &box : moovKids)
    if (box.type == fourcc("pssh"))
      rename(init, box, kFree);
  const Box *trak = find(moovKids, fourcc("trak"));
  if (!trak)
    return "init segment has no track";
  const Box *stsd = nullptr;
  std::vector<Box> level = children(init, trak->body, trak->end);
  for (uint32_t type : {fourcc("mdia"), fourcc("minf"), fourcc("stbl"), fourcc("stsd")}) {
    const Box *box = find(level, type);
    if (!box)
      return "init segment has no sample description";
    if (type == fourcc("stsd")) {
      stsd = box;
      break;
    }
    level = children(init, box->body, box->end);
  }
  if (stsd->end - stsd->body < 8)
    return "stsd is truncated";
  const std::vector<Box> entries = children(init, stsd->body + 8, stsd->end);
  if (entries.empty())
    return "stsd has no sample entry";
  const Box &entry = entries.front();
  if (entry.type != fourcc("enca") && entry.type != fourcc("encv"))
    return "track is not encrypted";
  const std::vector<Box> entryKids = children(init, entryChildren(init, entry), entry.end);
  const Box *sinf = find(entryKids, fourcc("sinf"));
  if (!sinf)
    return "sample entry has no sinf";
  const std::vector<Box> sinfKids = children(init, sinf->body, sinf->end);
  const Box *frma = find(sinfKids, fourcc("frma"));
  const Box *schm = find(sinfKids, fourcc("schm"));
  const Box *schi = find(sinfKids, fourcc("schi"));
  if (!frma || frma->end - frma->body < 4 || !schm || schm->end - schm->body < 8 || !schi)
    return "sinf is incomplete";
  track.scheme = be32(init, schm->body + 4);
  if (track.scheme != fourcc("cenc") && track.scheme != fourcc("cbcs"))
    return "unsupported protection scheme";
  const std::vector<Box> schiKids = children(init, schi->body, schi->end);
  const Box *tenc = find(schiKids, fourcc("tenc"));
  if (!tenc)
    return "schi has no tenc";
  if (std::string error = readTenc(init, *tenc, track); !error.empty())
    return error;
  rename(init, entry, be32(init, frma->body));
  rename(init, *sinf, kFree);
  return {};
}

std::string clearFragment(Bytes &fragment, const Track &track,
                          const std::function<std::string(Sample &)> &decrypt) {
  const std::vector<Box> top = children(fragment, 0, fragment.size());
  const Box *moof = find(top, fourcc("moof"));
  if (!moof)
    return "fragment has no moof";
  const std::vector<Box> moofKids = children(fragment, moof->body, moof->end);
  for (const Box &box : moofKids)
    if (box.type == fourcc("pssh"))
      rename(fragment, box, kFree);
  const Box *traf = find(moofKids, fourcc("traf"));
  if (!traf)
    return "fragment has no traf";
  const std::vector<Box> trafKids = children(fragment, traf->body, traf->end);
  const Box *tfhd = find(trafKids, fourcc("tfhd"));
  const Box *senc = find(trafKids, fourcc("senc"));
  if (!tfhd || tfhd->end - tfhd->body < 8 || !senc || senc->end - senc->body < 8)
    return "fragment has no sample encryption";
  const uint32_t tfFlags = be32(fragment, tfhd->body) & 0xffffff;
  // An absolute base offset points into the whole file, which this fragment cannot see.
  if (tfFlags & 1)
    return "fragment uses an absolute base offset";
  size_t at = tfhd->body + 8;
  at += (tfFlags & 2 ? 4 : 0) + (tfFlags & 8 ? 4 : 0);
  uint32_t defaultSize = 0;
  if (tfFlags & 0x10) {
    if (at + 4 > tfhd->end)
      return "tfhd is truncated";
    defaultSize = be32(fragment, at);
  }

  const uint32_t sencFlags = be32(fragment, senc->body) & 0xffffff;
  const uint32_t sencCount = be32(fragment, senc->body + 4);
  size_t info = senc->body + 8;
  uint32_t sampleIndex = 0;
  size_t next = moof->start;
  for (const Box &trun : trafKids) {
    if (trun.type != fourcc("trun"))
      continue;
    if (trun.end - trun.body < 8)
      return "trun is truncated";
    const uint32_t flags = be32(fragment, trun.body) & 0xffffff;
    const uint32_t count = be32(fragment, trun.body + 4);
    size_t field = trun.body + 8;
    if (flags & 1) {
      next = moof->start + size_t(int32_t(be32(fragment, field)));
      field += 4;
    }
    if (flags & 4)
      field += 4;
    const size_t perSample = (flags & 0x100 ? 4 : 0) + (flags & 0x200 ? 4 : 0) + (flags & 0x400 ? 4 : 0) +
                             (flags & 0x800 ? 4 : 0);
    if (field + perSample * count > trun.end)
      return "trun is truncated";
    for (uint32_t i = 0; i < count; ++i, ++sampleIndex) {
      if (flags & 0x100)
        field += 4;
      uint32_t size = defaultSize;
      if (flags & 0x200) {
        size = be32(fragment, field);
        field += 4;
      }
      field += (flags & 0x400 ? 4 : 0) + (flags & 0x800 ? 4 : 0);
      if (sampleIndex >= sencCount)
        return "senc describes fewer samples than trun";
      if (next + size > fragment.size())
        return "sample runs past the fragment";
      Sample sample{&fragment[next], size, {}, 16, {}};
      if (track.perSampleIvSize) {
        if (info + track.perSampleIvSize > senc->end)
          return "senc is truncated";
        std::memcpy(sample.iv, &fragment[info], track.perSampleIvSize);
        info += track.perSampleIvSize;
      } else {
        std::memcpy(sample.iv, track.constantIv, track.constantIvSize);
      }
      if (sencFlags & 2) {
        if (info + 2 > senc->end)
          return "senc is truncated";
        const uint16_t entries = be16(fragment, info);
        info += 2;
        if (info + size_t(entries) * 6 > senc->end)
          return "senc is truncated";
        for (uint16_t k = 0; k < entries; ++k, info += 6)
          sample.subsamples.emplace_back(be16(fragment, info), be32(fragment, info + 2));
      }
      if (std::string error = decrypt(sample); !error.empty())
        return error;
      next += size;
    }
  }
  for (const Box &box : trafKids)
    if (box.type == fourcc("senc") || box.type == fourcc("saiz") || box.type == fourcc("saio"))
      rename(fragment, box, kFree);
  return {};
}

} // namespace cenc_mp4
