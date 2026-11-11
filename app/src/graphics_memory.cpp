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

#include "graphics_memory.h"

#include <QByteArray>

namespace {
void setDefault(const char *name, const char *value) {
  if (!qEnvironmentVariableIsSet(name)) qputenv(name, value);
}
}

void configureGraphicsMemory() {
#ifdef Q_OS_MACOS
  // Qt sizes each image atlas page from the window, so a Retina window gets 2048px pages
  // that hold a few icons and thumbnails each.
  setDefault("QSG_ATLAS_WIDTH", "1024");
  setDefault("QSG_ATLAS_HEIGHT", "1024");
#if defined(Q_PROCESSOR_X86_64)
  // Intel GPUs back every depth-stencil buffer with real memory, and the window and each
  // full-window layer carry one. Apple GPUs keep them on chip.
  setDefault("QSG_NO_DEPTH_BUFFER", "1");
#endif
#endif
}
