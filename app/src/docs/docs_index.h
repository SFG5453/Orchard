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
#include <QSet>
#include <QString>
#include <QVariantList>

class DocsLibrary;

// One searchable piece of the docs: a "##" section, or a whole page (empty section).
struct DocsChunk {
    QString pageId;
    QString pageTitle;
    QString icon;
    QString section;
    QString snippet;
    // Embedded for the title vector.
    QString titleText;
    // Embedded for the body vector: page title, section title and the section text.
    QString bodyText;
};

// Ranks docs chunks against a question vector. Holds no model; vectors come from DocsEmbedder.
class DocsIndex {
public:
    using Vector = QList<float>;

    static QList<DocsChunk> chunksOf(const DocsLibrary &docs);
    // Lowercased content words with light suffix stripping, for the word-overlap bonus.
    static QSet<QString> stems(const QString &text);

    void setChunks(QList<DocsChunk> chunks);
    // One body and one title vector per chunk, in chunk order.
    void setVectors(QList<Vector> body, QList<Vector> title);
    void clear();

    [[nodiscard]] const QList<DocsChunk> &chunks() const { return m_chunks; }
    [[nodiscard]] bool ready() const { return !m_chunks.isEmpty() && m_body.size() == m_chunks.size(); }

    // Best first: {pageId, pageTitle, icon, section, snippet, score}. Empty when nothing is relevant.
    [[nodiscard]] QVariantList rank(const Vector &query, const QString &question, int limit = 6) const;

private:
    QList<DocsChunk> m_chunks;
    QList<QSet<QString>> m_stems;
    QList<Vector> m_body;
    QList<Vector> m_title;
};
