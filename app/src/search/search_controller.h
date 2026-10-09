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

#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QVariantList>

class AuthManager;
class OfflineLibrary;
class YouTubeCatalog;

class SearchController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool loading READ loading NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  Q_PROPERTY(QString query READ query NOTIFY stateChanged)
  Q_PROPERTY(QString filter READ filter NOTIFY stateChanged)
  Q_PROPERTY(QVariantList sections READ sections NOTIFY stateChanged)

public:
  explicit SearchController(YouTubeCatalog *catalog, AuthManager *auth,
                            QObject *parent = nullptr);

  [[nodiscard]] bool loading() const { return m_loading; }
  [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
  [[nodiscard]] QString query() const { return m_query; }
  [[nodiscard]] QString filter() const { return m_filter; }
  [[nodiscard]] QVariantList sections() const { return m_sections; }

  // While the app is offline, search covers downloaded and local songs and playlists only.
  void setOfflineLibrary(OfflineLibrary *library) { m_offline = library; }

  Q_INVOKABLE void search(const QString &query, const QString &filter = QStringLiteral("all"));
  Q_INVOKABLE void retry();
  Q_INVOKABLE void clear();

signals:
  void stateChanged();

private:
  void receiveSearch(quint64 requestId, const QJsonObject &search);
  void receiveFailure(quint64 requestId, const QString &message);
  static QString normalizedFilter(const QString &filter);

  YouTubeCatalog *m_catalog;
  AuthManager *m_auth;
  OfflineLibrary *m_offline{nullptr};
  QVariantList m_sections;
  QString m_errorMessage;
  QString m_query;
  QString m_filter{QStringLiteral("all")};
  quint64 m_requestId{0};
  bool m_loading{false};
};
