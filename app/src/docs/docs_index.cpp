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
#include <cmath>

namespace {
constexpr float kK1 = 1.5f;
constexpr float kB = 0.6f;
// Title words count this many times over body words.
constexpr float kTitleBoost = 3.0f;
// A page-level hit loses ties to a section that says the same thing.
constexpr float kPageFactor = 0.8f;
// A synonym match counts for less than the word the question used.
constexpr float kSynonymFactor = 0.7f;
// Unrelated questions top out near 0.32 and the weakest real question scores 0.34.
constexpr float kFloor = 0.33f;
// Results this far below the best one are noise.
constexpr float kWindow = 0.6f;
constexpr qsizetype kSnippetChars = 110;

const QSet<QString> &filler()
{
    static const QSet<QString> words = {
        "an", "the", "to", "of", "in", "on", "at", "for", "and", "or", "is", "are", "was", "be", "been", "do", "does",
        "did", "can", "could", "should", "would", "will", "you", "we", "my", "me", "it", "its", "this", "that", "these",
        "those", "how", "what", "whats", "where", "when", "why", "which", "who", "with", "from", "by", "as", "into",
        "about", "so", "if", "then", "than", "there", "their", "them", "they", "your", "our", "us", "not", "no", "yes",
        "please", "go", "get", "want", "way", "some", "any"};
    return words;
}

// Question wording mapped to docs wording, by stem.
const QHash<QString, QStringList> &synonyms()
{
    static const QHash<QString, QStringList> table = {
        {"log", {"sign"}},
        {"logout", {"sign"}},
        {"login", {"sign"}},
        {"exit", {"clos", "leav"}},
        {"quit", {"clos"}},
        {"leav", {"clos"}},
        {"greyed", {"disabl"}},
        {"grey", {"disabl"}},
        {"gray", {"disabl"}},
        {"shortcut", {"keyboard", "key"}},
        {"hotkey", {"shortcut", "keyboard"}},
        {"bass", {"equalizer"}},
        {"treble", {"equalizer"}},
        {"eq", {"equalizer"}},
        {"louder", {"volum"}},
        {"quieter", {"volum"}},
        {"loop", {"repeat"}},
        {"track", {"song"}},
        {"tune", {"song"}},
        {"remov", {"delet", "clear"}},
        {"delet", {"remov"}},
        {"start", {"open", "turn"}},
        {"restart", {"start", "launch"}},
        {"reopen", {"start", "launch"}},
        {"enabl", {"turn"}},
        {"disabl", {"turn"}},
        {"broken", {"troubleshoot", "fix"}},
        {"work", {"troubleshoot"}},
    };
    return table;
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
            chunk.titleText = title + QLatin1Char(' ') + name;
            chunk.bodyText = title + QStringLiteral(": ") + name + QLatin1Char('\n') + text;
            chunks.append(chunk);
        }
    }
    return chunks;
}

QStringList DocsIndex::terms(const QString &text)
{
    QStringList out;
    QString word;
    const auto flush = [&] {
        if (word.size() > 1 && !filler().contains(word))
            out.append(stem(word));
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

QSet<QString> DocsIndex::stems(const QString &text)
{
    const QStringList list = terms(text);
    return {list.begin(), list.end()};
}

void DocsIndex::setChunks(QList<DocsChunk> chunks)
{
    clear();
    m_chunks = std::move(chunks);
    m_stats.reserve(m_chunks.size());
    float total = 0;
    for (const DocsChunk &chunk : std::as_const(m_chunks)) {
        Stats stats;
        const QStringList body = terms(chunk.bodyText);
        for (const QString &term : body)
            stats.tf[term] += 1;
        for (const QString &term : terms(chunk.titleText))
            stats.tf[term] += kTitleBoost;
        stats.length = static_cast<float>(body.size());
        total += stats.length;
        for (auto it = stats.tf.cbegin(); it != stats.tf.cend(); ++it)
            m_df[it.key()] += 1;
        m_stats.append(std::move(stats));
    }
    m_averageLength = m_chunks.isEmpty() ? 0 : total / static_cast<float>(m_chunks.size());
}

void DocsIndex::clear()
{
    m_chunks.clear();
    m_stats.clear();
    m_df.clear();
    m_averageLength = 0;
}

float DocsIndex::idf(const QString &term) const
{
    const auto n = static_cast<float>(m_chunks.size());
    const auto df = static_cast<float>(m_df.value(term));
    return std::log(1.0f + (n - df + 0.5f) / (df + 0.5f));
}

float DocsIndex::termScore(const Stats &stats, const QString &term) const
{
    const float tf = stats.tf.value(term);
    if (tf <= 0)
        return 0;
    const float norm = kK1 * (1 - kB + kB * stats.length / std::max(m_averageLength, 1.0f));
    return idf(term) * tf * (kK1 + 1) / (tf + norm);
}

QList<std::pair<float, qsizetype>> DocsIndex::scoreAll(const QString &question) const
{
    const QSet<QString> wanted = stems(question);
    QList<std::pair<float, qsizetype>> scored;
    float best = 0;
    for (const QString &term : wanted)
        best += idf(term) * (kK1 + 1);
    if (wanted.isEmpty() || best <= 0)
        return scored;
    scored.reserve(m_chunks.size());
    for (qsizetype i = 0; i < m_chunks.size(); ++i) {
        const Stats &stats = m_stats.at(i);
        float score = 0;
        for (const QString &term : wanted) {
            float hit = termScore(stats, term);
            for (const QString &other : synonyms().value(term))
                hit = std::max(hit, kSynonymFactor * idf(term) / std::max(idf(other), 0.01f) * termScore(stats, other));
            score += hit;
        }
        if (m_chunks.at(i).section.isEmpty())
            score *= kPageFactor;
        // Divided by the question's best possible score so one floor fits every question.
        scored.append({score / best, i});
    }
    std::stable_sort(scored.begin(), scored.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
    return scored;
}

QVariantList DocsIndex::rank(const QString &question, int limit) const
{
    const QList<std::pair<float, qsizetype>> scored = scoreAll(question);
    if (scored.isEmpty())
        return {};
    const float cutoff = std::max(kFloor, scored.first().first * kWindow);
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
