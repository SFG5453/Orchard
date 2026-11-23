/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <QCoreApplication>
#include <QDir>
#include <QString>

// The worker and Qt app read the same staged model directory.
inline QString orchardModelsDirectory()
{
    if (qEnvironmentVariableIsSet("ORCHARD_MODELS_DIR"))
        return qEnvironmentVariable("ORCHARD_MODELS_DIR");
    const QDir directory(QCoreApplication::applicationDirPath());
#ifdef Q_OS_MACOS
    // App bundles keep data in Contents/Resources; codesign seals Contents/MacOS as code.
    const QString bundled = QDir::cleanPath(directory.filePath(QStringLiteral("../Resources/models")));
    if (QDir(bundled).exists())
        return bundled;
#endif
    return directory.filePath(QStringLiteral("models"));
}
