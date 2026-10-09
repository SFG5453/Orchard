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
#include <QString>

// WordPiece tokenizer for the bert-base-uncased vocabulary, matching Hugging Face's
// BertNormalizer (clean, CJK spacing, strip accents, lowercase), BertPreTokenizer and WordPiece.
class BertTokenizer {
public:
    // Reads one token per line; the line number is the id.
    explicit BertTokenizer(const QString &vocabPath = {});

    [[nodiscard]] bool isValid() const { return m_unk >= 0 && m_cls >= 0 && m_sep >= 0; }

    // [CLS] pieces [SEP], cut to maxLength ids. Empty when the vocabulary did not load.
    [[nodiscard]] QList<int> encode(const QString &text, int maxLength = 256) const;

private:
    void appendWord(const QString &word, QList<int> &ids) const;

    QHash<QString, int> m_vocab;
    int m_unk = -1;
    int m_cls = -1;
    int m_sep = -1;
};
