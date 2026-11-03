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

#include <cstdint>

// The display state the CDM asks about before it releases keys that require HDCP.
// Windows reads it from the OS; elsewhere nothing is known, so nothing is reported.
namespace output_protection {

// Asks the OS for `mask` (cdm::OutputProtectionMethods) on every display and waits,
// bounded, for the request to settle.
void enable(uint32_t mask);
// cdm::OutputLinkTypes of the active displays and the protection all of them have.
void query(uint32_t &links, uint32_t &protection);

} // namespace output_protection
