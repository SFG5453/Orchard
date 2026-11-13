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

#include <QQuickItem>
#include <QtQml/qqmlregistration.h>

// Clears its rect in the window to transparent so a native layer under the window shows through.
// The blend state does the erasing, so it works whether or not the scene graph has a depth buffer;
// an unblended ShaderEffect only overwrites its background when it does.
class VideoHole : public QQuickItem {
  Q_OBJECT
  QML_ELEMENT
public:
  explicit VideoHole(QQuickItem *parent = nullptr);

protected:
  QSGNode *updatePaintNode(QSGNode *old, UpdatePaintNodeData *) override;
};
