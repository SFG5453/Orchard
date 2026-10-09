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

#include "docs_index.h"

#include "docs_library.h"

#include <QVariantMap>
#include <algorithm>

namespace {
// Weights come from 63 hand-written questions over docs/app (top 3 holds the right section 95% of the time).
constexpr float kBodyWeight = 0.7f;
constexpr float kTitleWeight = 0.3f;
constexpr float kWordOverlapWeight = 0.1f;
// A page-level hit loses ties to a section that says the same thing.
constexpr float kPagePenalty = 0.06f;
// Unrelated questions top out near 0.60 and the weakest real question scores 0.66.
constexpr float kFloor = 0.62f;
// Results more than this below the best one are noise.
constexpr float kWindow = 0.08f;
constexpr qsizetype kSnippetChars = 110;

float dot(const QList<float> &a, const QList<float> &b)
{
    float sum = 0;
    for (qsizetype i = 0; i < a.size(); ++i)
        sum += a.at(i) * b.at(i);
    return sum;
}

QString stem(QString word)
{
    if (word.endsWith(QLatin1String("ies")) && word.size() > 4)
        word = word.chopped(3) + QLatin1Char('y');
    else if (word.endsWith(QLatin1Char('s')) && !word.endsWith(QLatin1String("ss")) && word.size() > 3)
        word.chop(1);
    if (word.endsWith(QLatin1String("ing")) && word.size() > 5)
        word.chop(3);
    else if (word.endsWith(QLatin1String("ed")) && word.size() > 4)
        word.chop(2);
    // So queue, queues and queued meet at the same stem.
    if (word.endsWith(QLatin1Char('e')) && word.size() > 3)
        word.chop(1);
    return word;
}

QString withoutMarkup(QString text)
{
    text.remove(QStringLiteral("**"));
    text.remove(QLatin1Char('`'));
    return text.trimmed();
}

// Plain text of a section's display blocks, one line per paragraph or list item.
QString flattened(const QVariantList &blocks)
{
    QStringList lines;
    for (const QVariant &entry : blocks) {
        const QVariantMap block = entry.toMap();
        const QStringList items = block.value(QStringLiteral("items")).toStringList();
        if (items.isEmpty())
            lines.append(block.value(QStringLiteral("text")).toString());
        else
            lines += items;
    }
    return withoutMarkup(lines.join(QLatin1Char('\n')));
}

QString snippetOf(const QString &text)
{
    QString line = text.section(QLatin1Char('\n'), 0, 0).simplified();
    if (line.size() <= kSnippetChars)
        return line;
    line.truncate(kSnippetChars);
    const qsizetype space = line.lastIndexOf(QLatin1Char(' '));
    if (space > kSnippetChars / 3)
        line.truncate(space);
    return line.trimmed() + QStringLiteral("…");
}
} // namespace

QList<DocsChunk> DocsIndex::chunksOf(const DocsLibrary &docs)
{
    QList<DocsChunk> chunks;
    for (const QVariant &entry : docs.pages()) {
        const QVariantMap page = entry.toMap();
        const QString id = page.value(QStringLiteral("id")).toString();
        const QString title = page.value(QStringLiteral("title")).toString();
        const QString icon = page.value(QStringLiteral("icon")).toString();
        const QString summary = page.value(QStringLiteral("summary")).toString();

        DocsChunk head;
        head.pageId = id;
        head.pageTitle = title;
        head.icon = icon;
        head.snippet = snippetOf(summary);
        head.titleText = title;
        head.bodyText = title + QLatin1Char('\n') + summary + QStringLiteral(" Keywords: ")
            + page.value(QStringLiteral("keywords")).toStringList().join(QStringLiteral(", "));
        chunks.append(head);

        for (const QVariant &sectionEntry : docs.sections(id)) {
            const QVariantMap section = sectionEntry.toMap();
            const QString name = section.value(QStringLiteral("title")).toString();
            // Text before the first heading is covered by the page summary.
            if (name.isEmpty())
                continue;
            const QString text = flattened(section.value(QStringLiteral("blocks")).toList());
            DocsChunk chunk;
            chunk.pageId = id;
            chunk.pageTitle = title;
            chunk.icon = icon;
            chunk.section = name;
            chunk.snippet = snippetOf(text);
            chunk.titleText = name;
            chunk.bodyText = title + QStringLiteral(": ") + name + QLatin1Char('\n') + text;
            chunks.append(chunk);
        }
    }
    return chunks;
}

QSet<QString> DocsIndex::stems(const QString &text)
{
    static const QSet<QString> filler = {
        "an", "the", "to", "of", "in", "on", "at", "for", "and", "or", "is", "are", "was", "be", "been", "do", "does",
        "did", "can", "could", "should", "would", "will", "you", "we", "my", "me", "it", "its", "this", "that", "these",
        "those", "how", "what", "whats", "where", "when", "why", "which", "who", "with", "from", "by", "as", "into",
        "about", "so", "if", "then", "than", "there", "their", "them", "they", "your", "our", "us", "not", "no", "yes",
        "please"};
    QSet<QString> out;
    QString word;
    const auto flush = [&] {
        if (word.size() > 1 && !filler.contains(word))
            out.insert(stem(word));
        word.clear();
    };
    for (const QChar c : text.toLower()) {
        if (c.isLetterOrNumber())
            word.append(c);
        else if (c != QLatin1Char('\''))
            flush();
    }
    flush();
    return out;
}

void DocsIndex::setChunks(QList<DocsChunk> chunks)
{
    m_chunks = std::move(chunks);
    m_body.clear();
    m_title.clear();
    m_stems.clear();
    m_stems.reserve(m_chunks.size());
    for (const DocsChunk &chunk : std::as_const(m_chunks))
        m_stems.append(stems(chunk.pageTitle + QLatin1Char(' ') + chunk.section + QLatin1Char(' ') + chunk.bodyText));
}

void DocsIndex::setVectors(QList<Vector> body, QList<Vector> title)
{
    const bool sized = body.size() == m_chunks.size() && title.size() == m_chunks.size();
    const qsizetype width = body.isEmpty() ? 0 : body.first().size();
    const auto uniform = [width](const QList<Vector> &vectors) {
        return std::all_of(vectors.begin(), vectors.end(), [width](const Vector &v) { return v.size() == width; });
    };
    if (!sized || width == 0 || !uniform(body) || !uniform(title)) {
        m_body.clear();
        m_title.clear();
        return;
    }
    m_body = std::move(body);
    m_title = std::move(title);
}

void DocsIndex::clear()
{
    m_chunks.clear();
    m_stems.clear();
    m_body.clear();
    m_title.clear();
}

QVariantList DocsIndex::rank(const Vector &query, const QString &question, int limit) const
{
    if (!ready() || query.size() != m_body.first().size())
        return {};

    const QSet<QString> wanted = stems(question);
    QList<std::pair<float, qsizetype>> scored;
    scored.reserve(m_chunks.size());
    for (qsizetype i = 0; i < m_chunks.size(); ++i) {
        float score = kBodyWeight * dot(query, m_body.at(i)) + kTitleWeight * dot(query, m_title.at(i));
        if (!wanted.isEmpty()) {
            const QSet<QString> &have = m_stems.at(i);
            const auto shared = std::count_if(wanted.begin(), wanted.end(), [&have](const QString &w) { return have.contains(w); });
            score += kWordOverlapWeight * static_cast<float>(shared) / static_cast<float>(wanted.size());
        }
        if (m_chunks.at(i).section.isEmpty())
            score -= kPagePenalty;
        scored.append({score, i});
    }
    std::stable_sort(scored.begin(), scored.end(), [](const auto &a, const auto &b) { return a.first > b.first; });

    const float cutoff = std::max(kFloor, scored.first().first - kWindow);
    QVariantList out;
    for (const auto &[score, index] : scored) {
        if (score < cutoff || out.size() >= limit)
            break;
        const DocsChunk &chunk = m_chunks.at(index);
        out.append(QVariantMap{{QStringLiteral("pageId"), chunk.pageId},
                               {QStringLiteral("pageTitle"), chunk.pageTitle},
                               {QStringLiteral("icon"), chunk.icon},
                               {QStringLiteral("section"), chunk.section},
                               {QStringLiteral("snippet"), chunk.snippet},
                               {QStringLiteral("score"), static_cast<double>(score)}});
    }
    return out;
}
