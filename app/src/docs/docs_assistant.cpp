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

namespace {
// Lets typing settle so the list does not jump on every key.
constexpr int kDebounceMs = 180;
// "qu" says nothing yet.
constexpr qsizetype kMinQuestionChars = 3;
} // namespace

DocsAssistant::DocsAssistant(const DocsLibrary *docs, QObject *parent)
    : QObject(parent)
    , m_docs(docs)
    , m_available(!docs->pages().isEmpty())
{
    m_debounce.setSingleShot(true);
    m_debounce.setInterval(kDebounceMs);
    connect(&m_debounce, &QTimer::timeout, this, &DocsAssistant::process);
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
    if (m_available && !m_index.ready())
        m_index.setChunks(DocsIndex::chunksOf(*m_docs));
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
    prepare();
    setAnswers(m_pending, m_index.rank(m_pending));
    setBusy(false);
}
