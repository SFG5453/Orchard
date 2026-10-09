/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "remote_translation.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

QStringList parseRemoteTranslation(const QByteArray &body, const QString &provider, int expected) {
  const QJsonDocument response = QJsonDocument::fromJson(body);
  QString content;
  if (provider == QStringLiteral("claude")) {
    for (const QJsonValue &block : response.object().value(QStringLiteral("content")).toArray())
      content += block.toObject().value(QStringLiteral("text")).toString();
  } else {
    const QJsonArray choices = response.object().value(QStringLiteral("choices")).toArray();
    if (choices.isEmpty()) return {};
    content = choices.at(0).toObject().value(QStringLiteral("message")).toObject()
                  .value(QStringLiteral("content")).toString();
  }
  // The fence is not invited, but some models arrive wearing one anyway.
  content = content.trimmed();
  if (content.startsWith(QStringLiteral("```"))) {
    const int newline = content.indexOf(QLatin1Char('\n'));
    const int closing = content.lastIndexOf(QStringLiteral("```"));
    if (newline < 0 || closing <= newline) return {};
    content = content.mid(newline + 1, closing - newline - 1).trimmed();
  }
  const QJsonDocument parsed = QJsonDocument::fromJson(content.toUtf8());
  const QJsonArray lines = parsed.isArray() ? parsed.array() : parsed.object().value(QStringLiteral("lines")).toArray();
  if (lines.size() != expected) return {};
  QStringList result;
  for (const QJsonValue &line : lines) {
    if (!line.isString() || line.toString().trimmed().isEmpty()) return {};
    result.append(line.toString().trimmed());
  }
  return result;
}
