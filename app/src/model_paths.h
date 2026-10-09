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
    return qEnvironmentVariable("ORCHARD_MODELS_DIR",
                                QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("models")));
}
