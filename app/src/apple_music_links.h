/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <QRegularExpression>
#include <QString>
#include <QUrl>

inline bool isAppleMusicBrowseId(const QString &id)
{
    return id.startsWith(QLatin1String("am."));
}

// music.apple.com link for an am.<type>.<id> browse id; empty for library-only ids (i., l., p., r.).
inline QString appleMusicShareUrl(const QString &browseId)
{
    const QString id = browseId.trimmed();
    if (!isAppleMusicBrowseId(id))
        return {};
    const qsizetype separator = id.indexOf(QLatin1Char('.'), 3);
    if (separator <= 3 || separator == id.size() - 1)
        return {};
    const QString type = id.mid(3, separator - 3);
    const QString appleId = id.mid(separator + 1);
    static const QRegularExpression libraryId(QStringLiteral("^[ilpr]\\."));
    if (libraryId.match(appleId).hasMatch())
        return {};

    QString path;
    if (type == QLatin1String("songs"))
        path = QStringLiteral("song");
    else if (type == QLatin1String("albums"))
        path = QStringLiteral("album");
    else if (type == QLatin1String("artists"))
        path = QStringLiteral("artist");
    else if (type == QLatin1String("playlists"))
        path = QStringLiteral("playlist");
    else if (type == QLatin1String("music-videos"))
        path = QStringLiteral("music-video");
    else if (type == QLatin1String("stations"))
        path = QStringLiteral("station");
    else
        return {};
    return QStringLiteral("https://music.apple.com/%1/%2").arg(path, QString::fromLatin1(QUrl::toPercentEncoding(appleId)));
}
