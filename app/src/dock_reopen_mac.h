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

// Runs `reopen` when the Dock icon is clicked or the running app is launched again.
// AppKit on its own only restores minimized windows, never hidden ones.
class DockReopen final
{
public:
    explicit DockReopen(std::function<void()> reopen);
    ~DockReopen();
    DockReopen(const DockReopen &) = delete;
    DockReopen &operator=(const DockReopen &) = delete;

private:
    void *m_handler{nullptr};
    void *m_launchObserver{nullptr};
};
