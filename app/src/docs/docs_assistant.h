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

#include "bert_tokenizer.h"
#include "docs_embedder.h"
#include "docs_index.h"

#include <QObject>
#include <QTimer>
#include <QVariantList>
#include <memory>

class DocsLibrary;

// Answers plain-language questions ("how do I open the queue") with the docs sections that fit.
// Everything runs on this machine: the docs are embedded once per run, then each question is
// embedded and compared. Without the model files the assistant reports itself unavailable.
class DocsAssistant final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool available READ available NOTIFY availableChanged)
    // True from asking until the answers arrive; answers update before this clears.
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    // The question that answers belongs to.
    Q_PROPERTY(QString question READ question NOTIFY answersChanged)
    // [{pageId, pageTitle, icon, section, snippet, score}] best first; section is empty for a whole page.
    Q_PROPERTY(QVariantList answers READ answers NOTIFY answersChanged)

public:
    // docs and embedder must outlive the assistant.
    DocsAssistant(const DocsLibrary *docs, DocsEmbedder *embedder, BertTokenizer tokenizer, QObject *parent = nullptr);

    [[nodiscard]] bool available() const { return m_available; }
    [[nodiscard]] bool busy() const { return m_busy; }
    [[nodiscard]] QString question() const { return m_question; }
    [[nodiscard]] QVariantList answers() const { return m_answers; }

    // Indexes the docs ahead of the first question.
    Q_INVOKABLE void prepare();
    // Waits for a pause in typing, then looks for answers. A question under three characters clears them.
    Q_INVOKABLE void ask(const QString &question);

signals:
    void availableChanged();
    void busyChanged();
    void answersChanged();

private:
    struct Build;

    void process();
    void buildIndex();
    void embedNextBatch(std::shared_ptr<Build> build);
    void finishBuild(const Build &build);
    void embedQuestion();
    void fail(const QString &error);
    void setBusy(bool busy);
    void setAnswers(const QString &question, const QVariantList &answers);
    void refreshAvailability();

    const DocsLibrary *m_docs;
    DocsEmbedder *m_embedder;
    BertTokenizer m_tokenizer;
    DocsIndex m_index;
    QTimer m_debounce;
    QString m_pending;
    QString m_question;
    QVariantList m_answers;
    bool m_available = false;
    bool m_busy = false;
    bool m_building = false;
    bool m_disabled = false;
    int m_failures = 0;
};
