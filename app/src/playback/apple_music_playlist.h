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

#pragma once

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QPair>
#include <QString>
#include <QUrl>

// Apple Music's audio HLS playlists: one fragmented MP4 addressed by byte ranges,
// protected by one Widevine key named in a data: URI.
namespace apple_music_playlist {

// Apple's files carry no segment index, and Qt's FFmpeg backend reads every fragment
// header to build one before it plays a seekable file. The served file therefore has a
// `sidx` spliced in after the init segment: it shifts the fragments, which address their
// samples relative to their own moof, and lets playback start after one fragment.
struct Playlist {
  QUrl media;
  // Music videos keep each segment in its own file, first the init segment; the
  // ranges then lay those files end to end once setFileSizes() knows their sizes.
  QList<QUrl> files;
  // The EXT-X-KEY URI, which the license request repeats.
  QString keyUri;
  QByteArray pssh;
  // Start and size of each range of Apple's file in order; the first is the init
  // segment. They tile the file without gaps.
  QList<QPair<qint64, qint64>> ranges;
  // EXTINF of each media segment, so one fewer than ranges.
  QList<double> durations;
  double duration{0.0};

  qint64 indexBytes() const { return 32 + 12 * qint64(durations.size()); }
  // The served file: init plus index first, then each fragment shifted by the index.
  qint64 servedSize() const;
  QPair<qint64, qint64> served(int index) const;
  // The served range holding byte `offset`, or -1 past the end.
  int indexAt(qint64 offset) const;
  // The sidx for the track in `init`; empty when its tkhd or mdhd cannot be read.
  QByteArray segmentIndex(const QByteArray &init) const;
  void setFileSizes(const QList<qint64> &sizes);
  // Where range `index` is fetched from, and the Range header it needs (empty for a whole file).
  QUrl url(int index) const { return files.isEmpty() ? media : files.at(index); }
  QByteArray rangeHeader(int index) const;
};

// One rendition of a music video's master playlist.
struct Variant {
  QUrl url;
  int height{0};
  qint64 bandwidth{0};
  QString audioGroup;
  // Software Widevine may play it; Apple keeps larger renditions for hardware CDMs.
  bool software{false};
};

struct Master {
  QList<Variant> variants;
  // Audio rendition playlists by GROUP-ID.
  QHash<QString, QUrl> audio;
};

QString parseMaster(const QUrl &playlistUrl, const QByteArray &text, Master &master);
// The tallest variant no taller than `maxHeight` (0 for any), or the shortest one. Only
// software variants unless `hardware`.
const Variant *pickVariant(const Master &master, int maxHeight, bool hardware = false);
// The best stereo audio rendition, preferring 256 kbps AAC.
QUrl pickAudio(const Master &master);

// Returns an error, or an empty string with `playlist` filled.
QString parse(const QUrl &playlistUrl, const QByteArray &text, Playlist &playlist);

// A version 0 Widevine pssh box naming one key id.
QByteArray widevinePssh(const QByteArray &keyId);

} // namespace apple_music_playlist
