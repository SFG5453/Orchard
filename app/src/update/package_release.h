/* Copyright (C) 2026 SFG545. SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <QByteArray>
#include <QString>

bool packageReleaseVersion(const QByteArray &envelope, const QString &channel, QString *version);
bool packageVersionIsNewer(const QString &current, const QString &latest);
