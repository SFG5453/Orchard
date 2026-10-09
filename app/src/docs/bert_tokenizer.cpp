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

#include "bert_tokenizer.h"
#include "model_paths.h"

#include <QChar>
#include <QDir>
#include <QFile>
#include <string>

namespace {
constexpr qsizetype kMaxWordChars = 100;

bool isControl(char32_t c)
{
    if (c == U'\t' || c == U'\n' || c == U'\r')
        return false;
    switch (QChar::category(c)) {
    case QChar::Other_Control:
    case QChar::Other_Format:
    case QChar::Other_Surrogate:
    case QChar::Other_PrivateUse:
    case QChar::Other_NotAssigned:
        return true;
    default:
        return false;
    }
}

bool isCjk(char32_t c)
{
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2A6DF)
        || (c >= 0x2A700 && c <= 0x2B73F) || (c >= 0x2B740 && c <= 0x2B81F) || (c >= 0x2B820 && c <= 0x2CEAF)
        || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x2F800 && c <= 0x2FA1F);
}

// ASCII symbols count as punctuation here, as they do in BERT.
bool isPunctuation(char32_t c)
{
    if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126))
        return true;
    return QChar::isPunct(c);
}

// Drop controls, space out CJK, strip accents, lowercase. Order matters: marks go before the case fold.
std::u32string normalized(const QString &text)
{
    std::u32string cleaned;
    cleaned.reserve(static_cast<size_t>(text.size()));
    for (const char32_t c : text.toUcs4()) {
        if (c == 0 || c == 0xFFFD || isControl(c))
            continue;
        if (QChar::isSpace(c)) {
            cleaned.push_back(U' ');
        } else if (isCjk(c)) {
            cleaned.push_back(U' ');
            cleaned.push_back(c);
            cleaned.push_back(U' ');
        } else {
            cleaned.push_back(c);
        }
    }

    const QString decomposed = QString::fromUcs4(cleaned.data(), static_cast<qsizetype>(cleaned.size()))
                                   .normalized(QString::NormalizationForm_D);
    std::u32string folded;
    folded.reserve(cleaned.size());
    for (const char32_t c : decomposed.toUcs4()) {
        if (QChar::category(c) != QChar::Mark_NonSpacing)
            folded.push_back(QChar::toLower(c));
    }
    return folded;
}
} // namespace

BertTokenizer::BertTokenizer(const QString &vocabPath)
{
    const QString path = vocabPath.isEmpty()
        ? QDir(orchardModelsDirectory()).filePath(QStringLiteral("docs-search/vocab.txt"))
        : vocabPath;
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return;
    const QList<QByteArray> lines = file.readAll().split('\n');
    m_vocab.reserve(lines.size());
    for (qsizetype i = 0; i < lines.size(); ++i) {
        QString token = QString::fromUtf8(lines.at(i));
        while (!token.isEmpty() && token.back().isSpace())
            token.chop(1);
        // The newline that ends the file leaves one empty element behind.
        if (token.isEmpty() && i == lines.size() - 1)
            break;
        m_vocab.insert(token, static_cast<int>(i));
    }
    m_unk = m_vocab.value(QStringLiteral("[UNK]"), -1);
    m_cls = m_vocab.value(QStringLiteral("[CLS]"), -1);
    m_sep = m_vocab.value(QStringLiteral("[SEP]"), -1);
}

void BertTokenizer::appendWord(const QString &word, QList<int> &ids) const
{
    // Code point boundaries keep astral characters whole.
    QList<qsizetype> starts;
    for (qsizetype i = 0; i < word.size(); ++i) {
        starts.append(i);
        if (word.at(i).isHighSurrogate() && i + 1 < word.size() && word.at(i + 1).isLowSurrogate())
            ++i;
    }
    const qsizetype count = starts.size();
    if (count > kMaxWordChars) {
        ids.append(m_unk);
        return;
    }
    starts.append(word.size());

    // Longest match first; one miss turns the whole word into [UNK].
    QList<int> pieces;
    qsizetype from = 0;
    while (from < count) {
        qsizetype to = count;
        int id = -1;
        for (; to > from; --to) {
            QString piece = word.mid(starts.at(from), starts.at(to) - starts.at(from));
            if (from > 0)
                piece.prepend(QLatin1String("##"));
            const auto hit = m_vocab.constFind(piece);
            if (hit != m_vocab.constEnd()) {
                id = hit.value();
                break;
            }
        }
        if (id < 0) {
            ids.append(m_unk);
            return;
        }
        pieces.append(id);
        from = to;
    }
    ids.append(pieces);
}

QList<int> BertTokenizer::encode(const QString &text, int maxLength) const
{
    if (!isValid() || maxLength < 2)
        return {};

    QList<int> ids{m_cls};
    std::u32string word;
    const auto flush = [&] {
        if (!word.empty())
            appendWord(QString::fromUcs4(word.data(), static_cast<qsizetype>(word.size())), ids);
        word.clear();
    };
    for (const char32_t c : normalized(text)) {
        if (ids.size() >= maxLength - 1)
            break;
        if (c == U' ') {
            flush();
        } else if (isPunctuation(c)) {
            flush();
            word.push_back(c);
            flush();
        } else {
            word.push_back(c);
        }
    }
    flush();
    // A last word can overshoot; keep room for [SEP].
    if (ids.size() > maxLength - 1)
        ids.resize(maxLength - 1);
    ids.append(m_sep);
    return ids;
}
