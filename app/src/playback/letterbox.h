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

#pragma once

#include <QImage>
#include <QRectF>

namespace letterbox {

// Picture area of a frame inside black bars baked into the video, in 0..1 frame
// coordinates. Null when the frame is too dark to tell bars from the scene.
QRectF contentRect(const QImage &frame);

// Grows `current` to cover `sample`, snapping near-edge sides to the frame edge.
QRectF accumulate(const QRectF &current, const QRectF &sample);

} // namespace letterbox
