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

#include "ui_language.h"

#include <QCoreApplication>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocale>
#include <QQmlEngine>
#include <QSettings>

namespace {
const QString kLanguageKey = QStringLiteral("app/language");
const QString kSpanishCatalog = QStringLiteral(":/qt/qml/Orchard/app/i18n/es.json");
} // namespace

bool CatalogTranslator::loadCatalog(const QString &path) {
  QFile file(path);
  if (!file.open(QIODevice::ReadOnly))
    return false;
  const QJsonObject catalog = QJsonDocument::fromJson(file.readAll()).object();
  m_strings.clear();
  m_strings.reserve(catalog.size());
  for (auto it = catalog.constBegin(); it != catalog.constEnd(); ++it) {
    const QString value = it.value().toString();
    if (!value.isEmpty())
      m_strings.insert(it.key(), value);
  }
  return !m_strings.isEmpty();
}

// Context is ignored: the same English text reads the same everywhere in Orchard.
QString CatalogTranslator::translate(const char *, const char *sourceText, const char *, int) const {
  if (!sourceText)
    return {};
  return m_strings.value(QString::fromUtf8(sourceText));
}

UiLanguage::UiLanguage(QObject *parent) : QObject(parent) {
  const QString saved = QSettings().value(kLanguageKey, QStringLiteral("system")).toString();
  if (saved == QStringLiteral("en") || saved == QStringLiteral("es"))
    m_language = saved;
  m_spanish.loadCatalog(kSpanishCatalog);
  apply();
}

QString UiLanguage::effectiveLanguage() const {
  if (m_language != QStringLiteral("system"))
    return m_language;
  return QLocale::system().language() == QLocale::Spanish ? QStringLiteral("es") : QStringLiteral("en");
}

void UiLanguage::setLanguage(const QString &language) {
  const QString value =
      language == QStringLiteral("en") || language == QStringLiteral("es") ? language : QStringLiteral("system");
  if (m_language == value)
    return;
  m_language = value;
  QSettings().setValue(kLanguageKey, value);
  apply();
  emit languageChanged();
}

void UiLanguage::apply() {
  const bool spanish = effectiveLanguage() == QStringLiteral("es") && !m_spanish.isEmpty();
  if (spanish && !m_installed)
    m_installed = QCoreApplication::installTranslator(&m_spanish);
  else if (!spanish && m_installed)
    m_installed = !QCoreApplication::removeTranslator(&m_spanish);
  QLocale::setDefault(spanish ? QLocale(QLocale::Spanish) : QLocale(QLocale::English));
  if (m_engine)
    m_engine->retranslate();
}
