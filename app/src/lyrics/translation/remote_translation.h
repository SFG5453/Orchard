/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#pragma once

#include <QByteArray>
#include <QString>
#include <QStringList>

// Empty means the API answer could not be mapped one-to-one to the source lines.
QStringList parseRemoteTranslation(const QByteArray &body, const QString &provider, int expected);
