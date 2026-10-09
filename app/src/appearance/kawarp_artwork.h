// Adapted from Kawarp. Copyright (c) 2026 Better Lyrics.
// SPDX-License-Identifier: MIT
// See third_party/kawarp/LICENSE.
#pragma once
#include <QImage>

namespace KawarpArtwork {
QImage prepare(const QImage &source);
QImage crossfade(const QImage &previous, const QImage &next, float blend);
}
