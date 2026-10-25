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

#include "backend_info.h"
#include "youtube_links.h"
#include "orchard_core.h"
#include <QClipboard>
#include <QDesktopServices>
#include <QFile>
#include <QGuiApplication>
#include <QHash>
#include <QUrl>

BackendInfo::BackendInfo(QObject *parent)
    : QObject(parent)
{
}

bool BackendInfo::ready() const
{
    return orchard_core_abi_version() == 1;
}

quint32 BackendInfo::abiVersion() const
{
    return orchard_core_abi_version();
}

double BackendInfo::smartCrossfadeMaxSeconds() const
{
    return orchard_smart_crossfade_max_seconds();
}

bool BackendInfo::systemMediaLinked() const
{
    return orchard_system_media_api_version() == 2;
}

QString BackendInfo::licenseText(const QString &key) const
{
    // The legal team insisted we make these readable, so here we are.
    // Yes, we actually checked that every single license file exists.
    static const QHash<QString, QString> licensePaths = {
        {QStringLiteral("orchard"), QStringLiteral(":/licenses/orchard.txt")},
        {QStringLiteral("lucide"), QStringLiteral(":/licenses/lucide.txt")},
        {QStringLiteral("quickjs"), QStringLiteral(":/licenses/quickjs.txt")},
        {QStringLiteral("qtkeychain"), QStringLiteral(":/licenses/qtkeychain.txt")},
        {QStringLiteral("kawarp"), QStringLiteral(":/licenses/kawarp.txt")},
        {QStringLiteral("oxc"), QStringLiteral(":/licenses/oxc.txt")},
        {QStringLiteral("youtubejs"), QStringLiteral(":/licenses/youtubejs.txt")},
        {QStringLiteral("inter"), QStringLiteral(":/licenses/inter.txt")},
        {QStringLiteral("libdatachannel"), QStringLiteral(":/licenses/libdatachannel.txt")},
        {QStringLiteral("libjuice"), QStringLiteral(":/licenses/libjuice.txt")},
        {QStringLiteral("usrsctp"), QStringLiteral(":/licenses/usrsctp.txt")},
        {QStringLiteral("plog"), QStringLiteral(":/licenses/plog.txt")},
        {QStringLiteral("mbedtls"), QStringLiteral(":/licenses/mbedtls.txt")},
        {QStringLiteral("nlohmann-json"), QStringLiteral(":/licenses/nlohmann-json.txt")},
        {QStringLiteral("fidelityfx-fsr"), QStringLiteral(":/licenses/fidelityfx-fsr.txt")},
    };

    QString path = licensePaths.value(key);
    if (path.isEmpty()) {
        if (key.startsWith(QStringLiteral(":/")) || key.startsWith(QStringLiteral("qrc:/"))) {
            path = key.startsWith(QStringLiteral("qrc:")) ? key.mid(3) : key;
        } else {
            return QStringLiteral("License text not found for identifier: %1").arg(key);
        }
    }

    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        return QStringLiteral("Could not load license text from resource: %1").arg(path);
    }
    QString text = QString::fromUtf8(file.readAll());
    // The adaptive mix engine is under PolyForm Shield; show it with the AGPL.
    if (key == QLatin1String("orchard")) {
        QFile engine(QStringLiteral(":/licenses/orchard-engine.txt"));
        if (engine.open(QIODevice::ReadOnly | QIODevice::Text))
            text += QStringLiteral("\n\n---\n\n") + QString::fromUtf8(engine.readAll());
    }
    return text;
}

QString BackendInfo::songlinkAlbumUrl(const QString &audioPlaylistId) const
{
    return youtubeShareUrl(audioPlaylistId, true);
}

QString BackendInfo::songLinkAlbumUrl(const QString &audioPlaylistId) const
{
    return songlinkAlbumUrl(audioPlaylistId);
}

QString BackendInfo::songLink(const QString &idOrUrl, const QString &kind) const
{
    const bool isAlbumOrPlaylist =
        kind.compare(QLatin1String("album"), Qt::CaseInsensitive) == 0 ||
        kind.compare(QLatin1String("playlist"), Qt::CaseInsensitive) == 0;
    return youtubeShareUrl(idOrUrl, isAlbumOrPlaylist);
}

void BackendInfo::copyToClipboard(const QString &text, const QString &notice)
{
    // Stealing text and putting it into the OS clipboard buffer.
    // Don't worry, the clipboard won't press charges.
    if (auto *clipboard = QGuiApplication::clipboard()) {
        clipboard->setText(text);
    }

    if (!notice.trimmed().isEmpty()) {
        emit noticeRequested(notice.trimmed());
    }
}

void BackendInfo::copySongLink(const QString &idOrUrl, const QString &label)
{
    const QString link = songLink(idOrUrl, label);
    const QString notice = label.trimmed().isEmpty()
        ? tr("Copied song link to clipboard")
        : tr("Copied %1 link to clipboard").arg(label.trimmed());
    copyToClipboard(link, notice);
}

void BackendInfo::openSongLink(const QString &idOrUrl, const QString &kind) const
{
    // Unleashing a browser window into the wild web.
    QDesktopServices::openUrl(QUrl(songLink(idOrUrl, kind)));
}

