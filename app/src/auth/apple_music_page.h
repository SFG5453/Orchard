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

#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>

// Shared by the WebEngine and WebView2 Apple Music sign-in helpers.
namespace apple_music_page {

// The developer token's origin claim names this origin; Android serves the same page from it.
inline constexpr auto kOrigin = "https://appassets.androidplatform.net";
inline constexpr auto kUrl = "https://appassets.androidplatform.net/assets/applemusic/signin.html";
inline constexpr auto kPlayerUrl = "https://appassets.androidplatform.net/assets/applemusic/player.html";
inline constexpr auto kProfileName = "AppleMusic";

inline QString jsString(const QString &value) {
  return QString::fromUtf8(QJsonDocument(QJsonArray{value}).toJson(QJsonDocument::Compact)).mid(1).chopped(1);
}

inline QString html(const QString &resource = QStringLiteral(":/auth/applemusic-signin.html")) {
  QFile page(resource);
  return page.open(QIODevice::ReadOnly) ? QString::fromUtf8(page.readAll()) : QString();
}

// The parent writes {developerToken} as one line on stdin.
inline QString readDeveloperToken() {
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return {};
  return QJsonDocument::fromJson(input.readLine(32 * 1024)).object().value(QStringLiteral("developerToken")).toString();
}

// {token} or {error} for the page's final message; empty for anything else.
inline QJsonObject result(const QString &raw) {
  const QJsonObject message = QJsonDocument::fromJson(raw.toUtf8()).object();
  const QString type = message.value(QStringLiteral("type")).toString();
  const QString token = message.value(QStringLiteral("token")).toString();
  if (type == QStringLiteral("authorized") && !token.isEmpty() && token.size() <= 16384)
    return {{QStringLiteral("token"), token}};
  if (type == QStringLiteral("error"))
    return {{QStringLiteral("error"), message.value(QStringLiteral("message")).toString()}};
  return {};
}

} // namespace apple_music_page
