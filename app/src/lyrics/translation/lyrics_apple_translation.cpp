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

#include "lyrics_translator.h"
#include "lyrics/lyrics_controller.h"
#include <QHash>
#include <QPointer>
#include <QVariantMap>

namespace {
// Locale tags Apple's lyric translations are requested with, keyed by the settings' ISO 639-3 targets.
QString appleLanguage(const QString &target) {
  static const QHash<QString, QString> tags{
      {QStringLiteral("eng"), QStringLiteral("en-US")}, {QStringLiteral("spa"), QStringLiteral("es-MX")},
      {QStringLiteral("hin"), QStringLiteral("hi")},    {QStringLiteral("kor"), QStringLiteral("ko")},
      {QStringLiteral("jpn"), QStringLiteral("ja")},    {QStringLiteral("fra"), QStringLiteral("fr-FR")},
  };
  return tags.value(target);
}
} // namespace

// Starts Apple's own translation for Apple lyrics. A miss runs the selected provider.
bool LyricsTranslator::tryApple() {
  const QString language = appleLanguage(m_targetLanguage);
  if (!m_preferApple || language.isEmpty() || m_lyrics->source() != QStringLiteral("apple"))
    return false;
  QStringList keys;
  for (const QVariant &line : m_lyrics->lines())
    keys.append(line.toMap().value(QStringLiteral("key")).toString());
  if (keys.size() != m_texts.size() || keys.join(QString()).isEmpty())
    return false;
  const int generation = m_generation->load();
  QPointer<LyricsTranslator> guard(this);
  const bool started = m_lyrics->appleTranslation(language, [guard, generation, keys](const QHash<QString, QString> &found) {
    if (!guard || generation != guard->m_generation->load())
      return;
    if (found.isEmpty()) {
      guard->startProvider();
      return;
    }
    for (qsizetype i = 0; i < keys.size(); ++i) {
      const QString translation = found.value(keys.at(i));
      guard->m_lines[i] = translation.simplified().compare(guard->m_texts.at(i).simplified(), Qt::CaseInsensitive) == 0
          ? QString() : translation;
    }
    guard->m_source.clear();
    guard->setStatus(QStringLiteral("ready"), guard->tr("Translated by Apple Music"));
  });
  if (started)
    setStatus(QStringLiteral("translating"), tr("Checking Apple Music for a translation"));
  return started;
}
