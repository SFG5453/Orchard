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

#include <QList>
#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

// In-app documentation. Pages are plain Markdown files with a small YAML
// header (title, summary, group, icon, keywords, platforms, order), so the same
// files serve the docs popup and any model that reads the repository or bundle.
class DocsLibrary final : public QObject {
  Q_OBJECT
  // [{id, title, summary, group, icon, keywords}] in reading order.
  Q_PROPERTY(QVariantList pages READ pages CONSTANT)

public:
  // root is a directory or Qt resource path holding one <id>.md file per page.
  explicit DocsLibrary(const QString &root = QStringLiteral(":/docs"),
                       QObject *parent = nullptr);

  [[nodiscard]] QVariantList pages() const;

  // Display model: [{title, blocks}]. The first section has an empty title and
  // holds the text before the first "##" heading. A block is one of
  // {kind: "paragraph" | "subheading" | "code", text},
  // {kind: "steps" | "bullets", items}. The page title and metadata header are
  // left out, and page links become plain text (see related()).
  Q_INVOKABLE QVariantList sections(const QString &id) const;
  // Pages linked from this page as [text](other-page.md): [{id, title, icon}].
  Q_INVOKABLE QVariantList related(const QString &id) const;
  // The file exactly as authored, metadata header included.
  Q_INVOKABLE QString rawPage(const QString &id) const;
  // Every page in one Markdown document with a generated table of contents.
  Q_INVOKABLE QString bundle() const;
  // Ids of pages whose text holds every word of query, in reading order.
  Q_INVOKABLE QStringList search(const QString &query) const;

  // Splits a leading "---" YAML block into meta; returns the remaining body.
  // Values are strings, or string lists for "key:" followed by "- item" lines.
  static QString parseFrontmatter(const QString &raw, QVariantMap *meta);

private:
  struct Page {
    QString id;
    QVariantMap meta;
    QString raw;
    QString body;
  };

  [[nodiscard]] const Page *find(const QString &id) const;

  QList<Page> m_pages;
};
