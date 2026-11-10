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

#include <functional>

class QWindow;

// Reports when AppKit finishes animating a window out of fullscreen. Qt reports the
// state change as the animation starts, and resizing before it ends strands a frozen
// fullscreen window.
class FullscreenWatch final
{
public:
    FullscreenWatch(QWindow *window, std::function<void()> exited);
    ~FullscreenWatch();
    FullscreenWatch(const FullscreenWatch &) = delete;
    FullscreenWatch &operator=(const FullscreenWatch &) = delete;

private:
    void *m_observer{nullptr};
};
