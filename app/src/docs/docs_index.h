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
#include <QList>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QVariantList>

class DocsLibrary;

// One searchable piece of the docs: a "##" section, or a whole page (empty section).
struct DocsChunk {
    QString pageId;
    QString pageTitle;
    QString icon;
    QString section;
    QString snippet;
    // Page title and section title, weighted above the body.
    QString titleText;
    // Page title, section title and the section text.
    QString bodyText;
};

// BM25 over docs chunks with a small synonym table for question wording.
class DocsIndex {
public:
    static QList<DocsChunk> chunksOf(const DocsLibrary &docs);
    // Content-word stems in order, repeats kept.
    static QStringList terms(const QString &text);
    // Distinct content-word stems.
    static QSet<QString> stems(const QString &text);

    void setChunks(QList<DocsChunk> chunks);
    void clear();

    [[nodiscard]] const QList<DocsChunk> &chunks() const { return m_chunks; }
    [[nodiscard]] bool ready() const { return !m_chunks.isEmpty(); }

    // Best first: {pageId, pageTitle, icon, section, snippet, score}. Empty when nothing is relevant.
    [[nodiscard]] QVariantList rank(const QString &question, int limit = 6) const;
    // Every chunk with its score, best first; for tuning.
    [[nodiscard]] QList<std::pair<float, qsizetype>> scoreAll(const QString &question) const;

private:
    struct Stats {
        QHash<QString, float> tf;
        float length = 0;
    };

    [[nodiscard]] float idf(const QString &term) const;
    [[nodiscard]] float termScore(const Stats &stats, const QString &term) const;

    QList<DocsChunk> m_chunks;
    QList<Stats> m_stats;
    QHash<QString, int> m_df;
    float m_averageLength = 0;
};
