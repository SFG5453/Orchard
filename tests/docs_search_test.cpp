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

#include "docs/bert_tokenizer.h"
#include "docs/docs_assistant.h"
#include "docs/docs_embedder.h"
#include "docs/docs_index.h"
#include "docs/docs_library.h"

#include <QDebug>
#include <QEventLoop>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>
#include <cmath>

namespace {
QString vocabPath() { return QStringLiteral(ORCHARD_DOCS_MODEL_DIR "/vocab.txt"); }

void writePage(const QTemporaryDir &dir, const QString &name, const QString &text)
{
    QFile file(dir.filePath(name));
    QVERIFY(file.open(QIODevice::WriteOnly | QIODevice::Text));
    file.write(text.toUtf8());
}

QList<int> idsOf(const QJsonArray &array)
{
    QList<int> ids;
    for (const QJsonValue &value : array)
        ids.append(value.toInt());
    return ids;
}

QString listed(const QList<int> &ids)
{
    QStringList parts;
    for (const int id : ids)
        parts << QString::number(id);
    return parts.join(QLatin1Char(' '));
}

// Two pages: an intro, two sections and a page summary each, small enough to reason about.
void writeSamplePages(const QTemporaryDir &dir)
{
    writePage(dir, "alpha.md",
              "---\ntitle: Alpha\nsummary: Alpha pages.\norder: 1\nkeywords:\n  - one\n  - two\n---\n\n# Alpha\n\n"
              "Intro text that only the summary covers.\n\n## Open the queue\n\nSelect **Queue** in the `player` bar.\n\n"
              "## Clear the queue\n\n1. Select Clear.\n2. Done.\n");
    writePage(dir, "beta.md",
              "---\ntitle: Beta\nsummary: Beta pages.\norder: 2\n---\n\n# Beta\n\n## Turn on shuffle\n\nPress the shuffle button.\n");
}

struct Waited {
    DocsEmbedder::Vectors vectors;
    QString error;
};

Waited embedAndWait(DocsEmbedder &embedder, const QList<QList<int>> &sequences)
{
    Waited result;
    bool finished = false;
    QEventLoop loop;
    embedder.embed(sequences, [&](const DocsEmbedder::Vectors &vectors, const QString &error) {
        result = {vectors, error};
        finished = true;
        loop.quit();
    });
    // A request that fails at once has answered before the loop starts.
    if (!finished) {
        QTimer::singleShot(20000, &loop, &QEventLoop::quit);
        loop.exec();
    }
    return result;
}
} // namespace

class DocsSearchTest : public QObject {
    Q_OBJECT

private slots:
    void tokenizerMatchesHuggingFace()
    {
        QFile file(QStringLiteral(ORCHARD_TEST_DATA_DIR "/bert_tokenizer_golden.json"));
        QVERIFY(file.open(QIODevice::ReadOnly));
        const QJsonObject golden = QJsonDocument::fromJson(file.readAll()).object();
        const int maxLength = golden.value("maxLength").toInt();
        const QJsonArray cases = golden.value("cases").toArray();
        QVERIFY(cases.size() > 20);

        const BertTokenizer tokenizer(vocabPath());
        QVERIFY(tokenizer.isValid());
        for (const QJsonValue &entry : cases) {
            const QString text = entry.toObject().value("text").toString();
            const QList<int> want = idsOf(entry.toObject().value("ids").toArray());
            const QList<int> got = tokenizer.encode(text, maxLength);
            QVERIFY2(got == want,
                     qPrintable(QStringLiteral("text: %1\nwant: %2\n got: %3")
                                    .arg(text.left(60), listed(want), listed(got))));
        }
    }

    void tokenizerRefusesWhatItCannotDo()
    {
        const BertTokenizer missing(QStringLiteral("/nonexistent/vocab.txt"));
        QVERIFY(!missing.isValid());
        QVERIFY(missing.encode(QStringLiteral("anything")).isEmpty());

        const BertTokenizer tokenizer(vocabPath());
        QVERIFY(tokenizer.encode(QStringLiteral("anything"), 1).isEmpty());
        // Cut to the limit with [CLS] first and [SEP] last.
        const QList<int> cut = tokenizer.encode(QStringLiteral("open the queue panel in the player"), 4);
        QCOMPARE(cut.size(), 4);
        QCOMPARE(cut.first(), 101);
        QCOMPARE(cut.last(), 102);
    }

    void defaultVocabularyMatchesTheFile()
    {
        const BertTokenizer staged;
        const BertTokenizer file(vocabPath());
        QVERIFY(staged.isValid());
        const QString text = QStringLiteral("How do I open the queue?");
        QCOMPARE(staged.encode(text), file.encode(text));
    }

    void stemsJoinWordForms()
    {
        QCOMPARE(DocsIndex::stems(QStringLiteral("How do I open the queues?")), (QSet<QString>{"open", "queu"}));
        QCOMPARE(DocsIndex::stems(QStringLiteral("queued, queue, queuing")), (QSet<QString>{"queu"}));
        QCOMPARE(DocsIndex::stems(QStringLiteral("Last.fm isn't song.link")), (QSet<QString>{"last", "fm", "isnt", "song", "link"}));
        QVERIFY(DocsIndex::stems(QStringLiteral("how do I")).isEmpty());
    }

    void chunksCoverPagesAndSections()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        const QList<DocsChunk> chunks = DocsIndex::chunksOf(library);

        // Per page: one summary chunk, then one chunk per "##" section. Intros are left to the summary.
        QCOMPARE(chunks.size(), 5);
        QCOMPARE(chunks.at(0).section, QString());
        QCOMPARE(chunks.at(0).bodyText, QStringLiteral("Alpha\nAlpha pages. Keywords: one, two"));
        QCOMPARE(chunks.at(1).section, QStringLiteral("Open the queue"));
        QCOMPARE(chunks.at(1).titleText, QStringLiteral("Open the queue"));
        QCOMPARE(chunks.at(1).bodyText, QStringLiteral("Alpha: Open the queue\nSelect Queue in the player bar."));
        QCOMPARE(chunks.at(1).snippet, QStringLiteral("Select Queue in the player bar."));
        QCOMPARE(chunks.at(2).bodyText, QStringLiteral("Alpha: Clear the queue\nSelect Clear.\nDone."));
        QCOMPARE(chunks.at(4).pageId, QStringLiteral("beta"));
    }

    void rankingBlendsTitleBodyAndWords()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));
        QVERIFY(!index.ready());

        // alpha page, open, clear, beta page, shuffle.
        const DocsIndex::Vector e1{1, 0, 0};
        const DocsIndex::Vector e2{0, 1, 0};
        const DocsIndex::Vector e3{0, 0, 1};
        const DocsIndex::Vector mix{0.8f, 0.6f, 0};
        index.setVectors({e3, e1, mix, e3, e2}, {e3, e1, mix, e3, e2});
        QVERIFY(index.ready());

        QVariantList hits = index.rank(e1, QStringLiteral("zzz"));
        QCOMPARE(hits.size(), 1);
        QCOMPARE(hits.first().toMap().value("section").toString(), QStringLiteral("Open the queue"));
        QCOMPARE(hits.first().toMap().value("pageTitle").toString(), QStringLiteral("Alpha"));

        // Close scores stay, in order; far ones go.
        hits = index.rank({0.9396926f, 0.3420201f, 0}, QStringLiteral("zzz"));
        QCOMPARE(hits.size(), 2);
        QCOMPARE(hits.at(0).toMap().value("section").toString(), QStringLiteral("Clear the queue"));
        QCOMPARE(hits.at(1).toMap().value("section").toString(), QStringLiteral("Open the queue"));
        QVERIFY(hits.at(0).toMap().value("score").toDouble() > hits.at(1).toMap().value("score").toDouble());

        QCOMPARE(index.rank(e1, QStringLiteral("zzz"), 0).size(), 0);
    }

    void sharedWordsBreakTies()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));
        const DocsIndex::Vector same{1, 0, 0};
        const DocsIndex::Vector away{0, 0, 1};
        // The alpha page chunk and both alpha sections look alike to the model.
        index.setVectors({same, same, same, away, away}, {same, same, same, away, away});

        // The question's words pick the section, and the page chunk falls out of range.
        QVariantList hits = index.rank(same, QStringLiteral("clear the queue"));
        QCOMPARE(hits.size(), 2);
        QCOMPARE(hits.at(0).toMap().value("section").toString(), QStringLiteral("Clear the queue"));
        QCOMPARE(hits.at(1).toMap().value("section").toString(), QStringLiteral("Open the queue"));

        // With no words to go on, sections keep reading order and the page chunk trails them.
        hits = index.rank(same, QStringLiteral("zzz"));
        QCOMPARE(hits.size(), 3);
        QCOMPARE(hits.at(0).toMap().value("section").toString(), QStringLiteral("Open the queue"));
        QCOMPARE(hits.at(1).toMap().value("section").toString(), QStringLiteral("Clear the queue"));
        QCOMPARE(hits.at(2).toMap().value("section").toString(), QString());
    }

    void rankingStaysSilentWhenNothingFits()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));
        const DocsIndex::Vector e1{1, 0, 0};
        const DocsIndex::Vector e2{0, 1, 0};
        index.setVectors({e1, e1, e1, e1, e1}, {e1, e1, e1, e1, e1});

        QVERIFY(index.rank(e2, QStringLiteral("anything")).isEmpty());
        // A vector of the wrong width cannot be compared.
        QVERIFY(index.rank({1, 0}, QStringLiteral("anything")).isEmpty());

        index.setVectors({e1}, {e1});
        QVERIFY(!index.ready());
        QVERIFY(index.rank(e1, QStringLiteral("anything")).isEmpty());
    }

    void embedderReportsAMissingWorker()
    {
        DocsEmbedder embedder(QStringLiteral("/nonexistent/orchard-adaptive-mix"), QStringLiteral("/nonexistent"));
        QVERIFY(!embedder.available());
        const Waited result = embedAndWait(embedder, {{101, 102}});
        QVERIFY(result.vectors.isEmpty());
        QVERIFY(!result.error.isEmpty());
    }

    void assistantIsUnavailableWithoutAWorker()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsEmbedder embedder(QStringLiteral("/nonexistent/orchard-adaptive-mix"), QStringLiteral("/nonexistent"));
        DocsAssistant assistant(&library, &embedder, BertTokenizer(vocabPath()));
        QVERIFY(!assistant.available());

        assistant.ask(QStringLiteral("how do I open the queue"));
        QVERIFY(!assistant.busy());
        QVERIFY(assistant.answers().isEmpty());
        assistant.prepare();
        QVERIFY(!assistant.busy());
    }

    // Runs the shipped worker and model when the build has staged them next to the tests.
    void embedsWithTheRealModel()
    {
        DocsEmbedder embedder(DocsEmbedder::defaultProgram(), QStringLiteral(ORCHARD_MODELS_SOURCE_DIR));
        if (!embedder.available())
            QSKIP("orchard-adaptive-mix is not staged next to the tests");
        const BertTokenizer tokenizer(vocabPath());

        const Waited result = embedAndWait(embedder, {tokenizer.encode(QStringLiteral("how do I open the queue")),
                                                      tokenizer.encode(QStringLiteral("Open the queue panel")),
                                                      tokenizer.encode(QStringLiteral("how do I bake bread"))});
        QVERIFY2(result.error.isEmpty(), qPrintable(result.error));
        QCOMPARE(result.vectors.size(), 3);
        for (const QList<float> &vector : result.vectors) {
            QCOMPARE(vector.size(), 384);
            float norm = 0;
            for (const float value : vector)
                norm += value * value;
            QVERIFY(std::abs(norm - 1.0f) < 1e-3f);
        }
        const auto cosine = [&](int a, int b) {
            float sum = 0;
            for (qsizetype i = 0; i < 384; ++i)
                sum += result.vectors.at(a).at(i) * result.vectors.at(b).at(i);
            return sum;
        };
        QVERIFY(cosine(0, 1) > cosine(0, 2) + 0.15f);
    }

    // Measures the shipped model on 63 hand-written questions. Thresholds leave room for docs edits.
    void rankingQualityOnTheDocs()
    {
        DocsEmbedder embedder(DocsEmbedder::defaultProgram(), QStringLiteral(ORCHARD_MODELS_SOURCE_DIR));
        if (!embedder.available())
            QSKIP("orchard-adaptive-mix is not staged next to the tests");
        const DocsLibrary library{QStringLiteral(ORCHARD_DOCS_DIR)};
        const BertTokenizer tokenizer(vocabPath());

        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));
        QList<QList<int>> bodies;
        QList<QList<int>> titles;
        for (const DocsChunk &chunk : index.chunks()) {
            bodies.append(tokenizer.encode(chunk.bodyText));
            titles.append(tokenizer.encode(chunk.titleText));
        }
        const Waited body = embedAndWait(embedder, bodies);
        const Waited title = embedAndWait(embedder, titles);
        QVERIFY2(body.error.isEmpty() && title.error.isEmpty(), qPrintable(body.error + title.error));
        index.setVectors(body.vectors, title.vectors);
        QVERIFY(index.ready());

        QFile file(QStringLiteral(ORCHARD_TEST_DATA_DIR "/docs_questions.json"));
        QVERIFY(file.open(QIODevice::ReadOnly));
        const QJsonObject data = QJsonDocument::fromJson(file.readAll()).object();
        const QJsonArray questions = data.value("questions").toArray();
        const QJsonArray unrelated = data.value("unrelated").toArray();
        QVERIFY(questions.size() >= 60);

        QList<QList<int>> asked;
        for (const QJsonValue &entry : questions)
            asked.append(tokenizer.encode(entry.toObject().value("question").toString()));
        for (const QJsonValue &text : unrelated)
            asked.append(tokenizer.encode(text.toString()));
        const Waited vectors = embedAndWait(embedder, asked);
        QVERIFY2(vectors.error.isEmpty(), qPrintable(vectors.error));

        int top1 = 0;
        int top3 = 0;
        int top5 = 0;
        QStringList misses;
        for (qsizetype i = 0; i < questions.size(); ++i) {
            const QJsonObject entry = questions.at(i).toObject();
            const QString question = entry.value("question").toString();
            const QJsonArray accept = entry.value("accept").toArray();
            const QVariantList hits = index.rank(vectors.vectors.at(i), question, 5);
            int first = -1;
            for (int rank = 0; rank < hits.size() && first < 0; ++rank) {
                const QVariantMap hit = hits.at(rank).toMap();
                for (const QJsonValue &want : accept) {
                    if (hit.value("pageId").toString() == want.toObject().value("page").toString()
                        && hit.value("section").toString().startsWith(want.toObject().value("section").toString(),
                                                                       Qt::CaseInsensitive))
                        first = rank;
                }
            }
            top1 += first == 0;
            top3 += first >= 0 && first < 3;
            top5 += first >= 0;
            if (first < 0 || first >= 3) {
                QStringList shown;
                for (const QVariant &hit : hits)
                    shown << hit.toMap().value("pageId").toString() + QLatin1Char('#') + hit.toMap().value("section").toString();
                misses << QStringLiteral("%1 (rank %2) got %3")
                              .arg(question, first < 0 ? QStringLiteral("none") : QString::number(first + 1), shown.join(QStringLiteral(", ")));
            }
        }
        int silent = 0;
        for (qsizetype i = 0; i < unrelated.size(); ++i)
            silent += index.rank(vectors.vectors.at(questions.size() + i), unrelated.at(i).toString()).isEmpty();

        const int n = static_cast<int>(questions.size());
        qInfo().noquote() << QStringLiteral("docs questions: top 1 %1/%4, top 3 %2/%4, top 5 %3/%4; unrelated left unanswered %5/%6")
                                 .arg(top1).arg(top3).arg(top5).arg(n).arg(silent).arg(unrelated.size());
        for (const QString &miss : std::as_const(misses))
            qInfo().noquote() << "  not in top 3:" << miss;
        QVERIFY(top1 * 100 >= n * 80);
        QVERIFY(top3 * 100 >= n * 90);
        QVERIFY(silent * 100 >= unrelated.size() * 75);
    }

    void restartsAfterTheWorkerLeavesIdle()
    {
        DocsEmbedder embedder(DocsEmbedder::defaultProgram(), QStringLiteral(ORCHARD_MODELS_SOURCE_DIR));
        if (!embedder.available())
            QSKIP("orchard-adaptive-mix is not staged next to the tests");
        embedder.setIdleTimeout(100);
        const BertTokenizer tokenizer(vocabPath());
        const QList<QList<int>> request{tokenizer.encode(QStringLiteral("how do I open the queue"))};

        const Waited first = embedAndWait(embedder, request);
        QVERIFY2(first.error.isEmpty(), qPrintable(first.error));
        // Long enough for the idle timer and the worker's exit to pass.
        QTest::qWait(600);
        const Waited second = embedAndWait(embedder, request);
        QVERIFY2(second.error.isEmpty(), qPrintable(second.error));
        QCOMPARE(second.vectors, first.vectors);
    }

    void answersQuestionsWithTheRealModel()
    {
        DocsEmbedder embedder(DocsEmbedder::defaultProgram(), QStringLiteral(ORCHARD_MODELS_SOURCE_DIR));
        if (!embedder.available())
            QSKIP("orchard-adaptive-mix is not staged next to the tests");
        const DocsLibrary library{QStringLiteral(ORCHARD_DOCS_DIR)};
        DocsAssistant assistant(&library, &embedder, BertTokenizer(vocabPath()));
        QVERIFY(assistant.available());

        // The right page must appear in the top three. Pages, not sections, so a renamed heading stays harmless.
        struct Case {
            const char *question;
            const char *page;
            const char *otherPage;
        };
        static const Case cases[] = {
            {"how do I open the queue", "queue", ""},
            {"how to open settings", "getting-started", ""},
            {"how to open the docs", "getting-started", "using-the-docs"},
            {"how do I turn on crossfade", "crossfade", ""},
            {"how do I change the audio output device", "audio-engine", ""},
            {"how to connect last.fm", "integrations", "troubleshooting"},
            {"how do I like a song", "playback", "library-and-playlists"},
            {"lyrics say unavailable", "lyrics", "troubleshooting"},
            {"how do I make a new playlist", "library-and-playlists", ""},
            {"how to go fullscreen", "playback", "shortcuts"},
            {"how do I change the streaming quality", "streaming-quality", ""},
            {"discord isn't showing my song", "troubleshooting", "integrations"},
            {"how do I make orchard remember the window size", "accounts", ""},
            {"how do I stop it skipping AI songs", "ai-music", ""},
            {"how do I use the equalizer", "audio-engine", ""},
        };
        for (const Case &entry : cases) {
            const QString question = QString::fromLatin1(entry.question);
            QSignalSpy spy(&assistant, &DocsAssistant::answersChanged);
            assistant.ask(question);
            QVERIFY2(spy.wait(20000), entry.question);
            QCOMPARE(assistant.question(), question);
            QVERIFY(!assistant.busy());

            QStringList pages;
            const QVariantList answers = assistant.answers();
            for (qsizetype i = 0; i < qMin<qsizetype>(3, answers.size()); ++i)
                pages << answers.at(i).toMap().value("pageId").toString();
            QVERIFY2(pages.contains(QString::fromLatin1(entry.page))
                         || (*entry.otherPage && pages.contains(QString::fromLatin1(entry.otherPage))),
                     qPrintable(question + QStringLiteral(" -> ") + pages.join(QLatin1Char(','))));
        }

        // Unrelated questions find nothing, and an empty question clears the answers.
        for (const char *unrelated : {"what's the weather today", "how do I bake bread", "who won the world cup"}) {
            QSignalSpy spy(&assistant, &DocsAssistant::answersChanged);
            assistant.ask(QString::fromLatin1(unrelated));
            QVERIFY2(spy.wait(20000), unrelated);
            QVERIFY2(assistant.answers().isEmpty(), unrelated);
        }
        assistant.ask(QStringLiteral("how do I open the queue"));
        QTRY_VERIFY_WITH_TIMEOUT(!assistant.answers().isEmpty(), 20000);
        assistant.ask(QString());
        QVERIFY(assistant.answers().isEmpty());
        QVERIFY(assistant.question().isEmpty());
    }
};

QTEST_GUILESS_MAIN(DocsSearchTest)
#include "docs_search_test.moc"
