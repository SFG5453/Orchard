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

#include <QHash>
#include <QObject>
#include <QTranslator>

class QQmlEngine;

// Looks up qsTr()/tr() source text in a flat JSON catalog: { "English text": "Translated text" }.
class CatalogTranslator final : public QTranslator {
public:
  bool loadCatalog(const QString &path);
  [[nodiscard]] bool isEmpty() const override { return m_strings.isEmpty(); }
  QString translate(const char *context, const char *sourceText, const char *disambiguation = nullptr,
                    int n = -1) const override;

private:
  QHash<QString, QString> m_strings;
};

// Interface language. "system" follows the OS locale; "en" and "es" force a language.
class UiLanguage final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QString language READ language WRITE setLanguage NOTIFY languageChanged)
  Q_PROPERTY(QString effectiveLanguage READ effectiveLanguage NOTIFY languageChanged)

public:
  explicit UiLanguage(QObject *parent = nullptr);
  void setEngine(QQmlEngine *engine) { m_engine = engine; }

  [[nodiscard]] QString language() const { return m_language; }
  void setLanguage(const QString &language);
  [[nodiscard]] QString effectiveLanguage() const;

signals:
  void languageChanged();

private:
  void apply();

  QQmlEngine *m_engine{nullptr};
  CatalogTranslator m_spanish;
  bool m_installed{false};
  QString m_language{QStringLiteral("system")};
};
