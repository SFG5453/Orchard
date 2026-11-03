/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#pragma once

#include <QSize>

namespace UiScaling {
constexpr double minimum = 0.75;
constexpr double maximum = 2.5;
double savedScale();
bool autoEnabled();
// Call after setting the application identity, before constructing QApplication.
void configure();
// Extra factor for a screen of [pixels] that the system scales by [systemRatio]; 1 when the system already scales it.
double autoFactor(QSize pixels, double systemRatio);
// Call after constructing QApplication, before creating windows.
void applyAuto();
}
