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

// Reports AppKit's occlusion state: true once no part of the window is visible on screen.
class OcclusionWatch final
{
public:
    OcclusionWatch(QWindow *window, std::function<void(bool occluded)> changed);
    ~OcclusionWatch();
    OcclusionWatch(const OcclusionWatch &) = delete;
    OcclusionWatch &operator=(const OcclusionWatch &) = delete;

private:
    void *m_observer{nullptr};
};
