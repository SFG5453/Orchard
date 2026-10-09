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

#include "animated_artwork_service.h"

#include <QStringList>

// One song's walk down the mirror list; shared by the service's source files.
struct AnimatedArtworkService::LookupState {
  QString title;
  QString artist;
  QString album;
  QStringList mirrors;
  int currentMirrorIndex{0};
  QString key;
  // Any mirror errored or timed out, so an empty result may be wrong.
  bool mirrorFailed{false};
};
