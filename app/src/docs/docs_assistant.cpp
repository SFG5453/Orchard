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

#include "docs_assistant.h"

#include "docs_library.h"

#include <QDebug>

namespace {
constexpr int kDebounceMs = 180;
// "qu" says nothing yet.
constexpr qsizetype kMinQuestionChars = 3;
constexpr int kMaxTokens = 256;
// Sequences per worker request; a line stays far below the worker's 512 KiB input limit.
constexpr qsizetype kBatch = 32;
// Three strikes and the docs fall back to word search until the next launch.
constexpr int kMaxFailures = 3;
} // namespace

struct DocsAssistant::Build {
    // Bodies first, then titles, matching DocsIndex::setVectors.
    QList<QList<int>> sequences;
    DocsEmbedder::Vectors vectors;
};

DocsAssistant::DocsAssistant(const DocsLibrary *docs, DocsEmbedder *embedder, BertTokenizer tokenizer, QObject *parent)
    : QObject(parent)
    , m_docs(docs)
    , m_embedder(embedder)
    , m_tokenizer(std::move(tokenizer))
{
    m_debounce.setSingleShot(true);
    m_debounce.setInterval(kDebounceMs);
    connect(&m_debounce, &QTimer::timeout, this, &DocsAssistant::process);
    refreshAvailability();
}

void DocsAssistant::refreshAvailability()
{
    const bool available = !m_disabled && m_tokenizer.isValid() && m_embedder->available();
    if (available == m_available)
        return;
    m_available = available;
    emit availableChanged();
}

void DocsAssistant::setBusy(bool busy)
{
    if (busy == m_busy)
        return;
    m_busy = busy;
    emit busyChanged();
}

void DocsAssistant::setAnswers(const QString &question, const QVariantList &answers)
{
    if (question == m_question && answers == m_answers)
        return;
    m_question = question;
    m_answers = answers;
    emit answersChanged();
}

void DocsAssistant::prepare()
{
    if (m_available && !m_building && !m_index.ready())
        buildIndex();
}

void DocsAssistant::ask(const QString &question)
{
    m_pending = question.simplified();
    if (m_pending.size() < kMinQuestionChars) {
        m_debounce.stop();
        setAnswers(QString(), {});
        setBusy(false);
        return;
    }
    if (!m_available)
        return;
    setBusy(true);
    m_debounce.start();
}

void DocsAssistant::process()
{
    if (m_pending.isEmpty())
        return;
    if (!m_index.ready())
        buildIndex();
    else
        embedQuestion();
}

void DocsAssistant::buildIndex()
{
    // The running build answers the pending question when it finishes.
    if (m_building)
        return;
    QList<DocsChunk> chunks = DocsIndex::chunksOf(*m_docs);
    if (chunks.isEmpty()) {
        fail(QStringLiteral("There are no docs to index."));
        return;
    }
    auto build = std::make_shared<Build>();
    build->sequences.reserve(chunks.size() * 2);
    for (const DocsChunk &chunk : std::as_const(chunks))
        build->sequences.append(m_tokenizer.encode(chunk.bodyText, kMaxTokens));
    for (const DocsChunk &chunk : std::as_const(chunks))
        build->sequences.append(m_tokenizer.encode(chunk.titleText, kMaxTokens));
    m_index.setChunks(std::move(chunks));
    m_building = true;
    embedNextBatch(build);
}

void DocsAssistant::embedNextBatch(std::shared_ptr<Build> build)
{
    const qsizetype start = build->vectors.size();
    if (start >= build->sequences.size()) {
        finishBuild(*build);
        return;
    }
    const qsizetype count = qMin(kBatch, build->sequences.size() - start);
    m_embedder->embed(build->sequences.mid(start, count),
                      [this, build](const DocsEmbedder::Vectors &vectors, const QString &error) {
                          if (!error.isEmpty() || vectors.isEmpty()) {
                              m_building = false;
                              m_index.clear();
                              fail(error);
                              return;
                          }
                          build->vectors += vectors;
                          embedNextBatch(build);
                      });
}

void DocsAssistant::finishBuild(const Build &build)
{
    const qsizetype count = m_index.chunks().size();
    m_index.setVectors(build.vectors.mid(0, count), build.vectors.mid(count, count));
    m_building = false;
    if (!m_index.ready()) {
        m_index.clear();
        fail(QStringLiteral("The docs index is incomplete."));
        return;
    }
    if (!m_pending.isEmpty())
        embedQuestion();
}

void DocsAssistant::embedQuestion()
{
    const QString question = m_pending;
    m_embedder->embed({m_tokenizer.encode(question, kMaxTokens)},
                      [this, question](const DocsEmbedder::Vectors &vectors, const QString &error) {
                          if (!error.isEmpty() || vectors.isEmpty()) {
                              fail(error);
                              return;
                          }
                          // A newer question owns the result.
                          if (question != m_pending)
                              return;
                          m_failures = 0;
                          setAnswers(question, m_index.rank(vectors.first(), question));
                          setBusy(false);
                      });
}

void DocsAssistant::fail(const QString &error)
{
    qWarning().noquote() << "Docs search:" << error;
    if (++m_failures >= kMaxFailures)
        m_disabled = true;
    setAnswers(m_pending, {});
    setBusy(false);
    refreshAvailability();
}
