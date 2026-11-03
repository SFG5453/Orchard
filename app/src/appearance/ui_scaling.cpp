/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "ui_scaling.h"

#include <QGuiApplication>
#include <QScreen>
#include <QSettings>
#include <qpa/qplatformscreen.h>
#include <private/qhighdpiscaling_p.h>
#include <algorithm>
#include <cmath>

double UiScaling::savedScale() {
    bool ok = false;
    const double value = QSettings().value(QStringLiteral("appearance/uiScale"), 1.0).toDouble(&ok);
    return ok && std::isfinite(value) ? std::clamp(value, minimum, maximum) : 1.0;
}

bool UiScaling::autoEnabled() {
    return QSettings().value(QStringLiteral("appearance/autoUiScale"), true).toBool();
}

void UiScaling::configure() {
    const double scale = savedScale();
    if (scale == 1.0)
        return;
    bool ok = false;
    const double environmentScale = qEnvironmentVariable("QT_SCALE_FACTOR").toDouble(&ok);
    const double base = ok && std::isfinite(environmentScale) && environmentScale > 0
        ? environmentScale : 1.0;
    // Qt combines this application preference with each monitor's native DPI.
    qputenv("QT_SCALE_FACTOR", QByteArray::number(base * scale, 'g', 12));
}

double UiScaling::autoFactor(QSize pixels, double systemRatio) {
    const int shortSide = std::min(pixels.width(), pixels.height());
    if (systemRatio > 1.0 || shortSide <= 1080)
        return 1.0;
    // Lay an unscaled panel out like 1080p, in quarter steps: 1440p gets 125%, 4K 200%.
    return std::clamp(std::round(shortSide / 1080.0 * 4) / 4, 1.0, maximum);
}

void UiScaling::applyAuto() {
    if (!autoEnabled())
        return;
    const auto fit = [](QScreen *screen) {
        if (!screen->handle())
            return;
        const QSize pixels = (QSizeF(screen->geometry().size()) * screen->devicePixelRatio()).toSize();
        const double factor = autoFactor(pixels, screen->handle()->devicePixelRatio());
        if (factor != 1.0)
            QHighDpiScaling::setScreenFactor(screen, factor);
    };
    for (QScreen *screen : QGuiApplication::screens())
        fit(screen);
    QObject::connect(qGuiApp, &QGuiApplication::screenAdded, qGuiApp, fit);
}
