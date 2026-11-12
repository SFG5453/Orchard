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


#include "docs/docs_assistant.h"
#include "docs/docs_index.h"
#include "docs/docs_library.h"

#include <QDebug>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>

namespace {
void writePage(const QTemporaryDir &dir, const QString &name, const QString &text)
{
    QFile file(dir.filePath(name));
    QVERIFY(file.open(QIODevice::WriteOnly | QIODevice::Text));
    file.write(text.toUtf8());
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

QString sectionOf(const QVariant &hit) { return hit.toMap().value("section").toString(); }
} // namespace

class DocsSearchTest : public QObject {
    Q_OBJECT

private slots:
    void stemsJoinWordForms()
    {
        QCOMPARE(DocsIndex::stems(QStringLiteral("How do I open the queues?")), (QSet<QString>{"open", "queu"}));
        QCOMPARE(DocsIndex::stems(QStringLiteral("queued, queue, queuing")), (QSet<QString>{"queu"}));
        QCOMPARE(DocsIndex::stems(QStringLiteral("Last.fm isn't song.link")), (QSet<QString>{"last", "fm", "isnt", "song", "link"}));
        QVERIFY(DocsIndex::stems(QStringLiteral("how do I")).isEmpty());
        QCOMPARE(DocsIndex::terms(QStringLiteral("queue the queues")), (QStringList{"queu", "queu"}));
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
        QCOMPARE(chunks.at(1).titleText, QStringLiteral("Alpha Open the queue"));
        QCOMPARE(chunks.at(1).bodyText, QStringLiteral("Alpha: Open the queue\nSelect Queue in the player bar."));
        QCOMPARE(chunks.at(1).snippet, QStringLiteral("Select Queue in the player bar."));
        QCOMPARE(chunks.at(2).bodyText, QStringLiteral("Alpha: Clear the queue\nSelect Clear.\nDone."));
        QCOMPARE(chunks.at(4).pageId, QStringLiteral("beta"));
    }

    void rankingPrefersTheMatchingSection()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        QVERIFY(!index.ready());
        index.setChunks(DocsIndex::chunksOf(library));
        QVERIFY(index.ready());

        QVariantList hits = index.rank(QStringLiteral("how do I clear the queue"));
        QVERIFY(!hits.isEmpty());
        QCOMPARE(sectionOf(hits.first()), QStringLiteral("Clear the queue"));
        QCOMPARE(hits.first().toMap().value("pageTitle").toString(), QStringLiteral("Alpha"));
        for (qsizetype i = 1; i < hits.size(); ++i)
            QVERIFY(hits.at(i - 1).toMap().value("score").toDouble() >= hits.at(i).toMap().value("score").toDouble());

        hits = index.rank(QStringLiteral("shuffle"));
        QCOMPARE(sectionOf(hits.first()), QStringLiteral("Turn on shuffle"));
        QCOMPARE(index.rank(QStringLiteral("shuffle"), 0).size(), 0);
    }

    void synonymsReachDocsWording()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));

        // "remove" maps to "clear".
        const QVariantList hits = index.rank(QStringLiteral("remove everything from the queue"));
        QVERIFY(!hits.isEmpty());
        QCOMPARE(sectionOf(hits.first()), QStringLiteral("Clear the queue"));
    }

    void rankingStaysSilentWhenNothingFits()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsIndex index;
        QVERIFY(index.rank(QStringLiteral("queue")).isEmpty());
        index.setChunks(DocsIndex::chunksOf(library));

        QVERIFY(index.rank(QStringLiteral("how do I bake bread")).isEmpty());
        QVERIFY(index.rank(QStringLiteral("how do I")).isEmpty());
    }

    void assistantAnswersFromTheDocs()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writeSamplePages(dir);
        const DocsLibrary library(dir.path());
        DocsAssistant assistant(&library);
        QVERIFY(assistant.available());

        QSignalSpy spy(&assistant, &DocsAssistant::answersChanged);
        assistant.ask(QStringLiteral("how do I open the queue"));
        QVERIFY(assistant.busy());
        QVERIFY(spy.wait(2000));
        QVERIFY(!assistant.busy());
        QCOMPARE(sectionOf(assistant.answers().first()), QStringLiteral("Open the queue"));

        assistant.ask(QStringLiteral("qu"));
        QVERIFY(assistant.answers().isEmpty());
        QVERIFY(assistant.question().isEmpty());
    }

    void assistantIsUnavailableWithoutDocs()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        const DocsLibrary library(dir.path());
        DocsAssistant assistant(&library);
        QVERIFY(!assistant.available());
        assistant.ask(QStringLiteral("how do I open the queue"));
        QVERIFY(!assistant.busy());
        QVERIFY(assistant.answers().isEmpty());
    }

    void rankingQualityOnTheDocs()
    {
        const DocsLibrary library{QStringLiteral(ORCHARD_DOCS_DIR)};
        DocsIndex index;
        index.setChunks(DocsIndex::chunksOf(library));
        QVERIFY(index.ready());

        QFile file(QStringLiteral(ORCHARD_TEST_DATA_DIR "/docs_questions.json"));
        QVERIFY(file.open(QIODevice::ReadOnly));
        const QJsonObject data = QJsonDocument::fromJson(file.readAll()).object();
        const QJsonArray questions = data.value("questions").toArray();
        const QJsonArray unrelated = data.value("unrelated").toArray();
        QVERIFY(questions.size() >= 60);

        int top1 = 0;
        int top3 = 0;
        int top5 = 0;
        QStringList misses;
        for (const QJsonValue &value : questions) {
            const QJsonObject entry = value.toObject();
            const QString question = entry.value("question").toString();
            const QJsonArray accept = entry.value("accept").toArray();
            const QVariantList hits = index.rank(question, 5);
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
                    shown << QStringLiteral("%1#%2 %3").arg(hit.toMap().value("pageId").toString(), sectionOf(hit))
                                 .arg(hit.toMap().value("score").toDouble(), 0, 'f', 2);
                misses << QStringLiteral("%1 (rank %2) got %3")
                              .arg(question, first < 0 ? QStringLiteral("none") : QString::number(first + 1), shown.join(QStringLiteral(", ")));
            }
        }
        int silent = 0;
        QStringList answered;
        for (const QJsonValue &text : unrelated) {
            const QVariantList hits = index.rank(text.toString());
            silent += hits.isEmpty();
            if (!hits.isEmpty())
                answered << QStringLiteral("%1 (%2)").arg(text.toString()).arg(hits.first().toMap().value("score").toDouble(), 0, 'f', 2);
        }

        const int n = static_cast<int>(questions.size());
        qInfo().noquote() << QStringLiteral("docs questions: top 1 %1/%4, top 3 %2/%4, top 5 %3/%4; unrelated left unanswered %5/%6")
                                 .arg(top1).arg(top3).arg(top5).arg(n).arg(silent).arg(unrelated.size());
        for (const QString &miss : std::as_const(misses))
            qInfo().noquote() << "  not in top 3:" << miss;
        QList<std::pair<float, QString>> weakest;
        for (const QJsonValue &value : questions) {
            const QString question = value.toObject().value("question").toString();
            const auto scored = index.scoreAll(question);
            weakest.append({scored.isEmpty() ? 0.f : scored.first().first, question});
        }
        std::sort(weakest.begin(), weakest.end());
        for (qsizetype i = 0; i < qMin<qsizetype>(6, weakest.size()); ++i)
            qInfo().noquote() << QStringLiteral("  weak real question: %1 (%2)").arg(weakest.at(i).second).arg(weakest.at(i).first, 0, 'f', 2);
        for (const QString &text : std::as_const(answered))
            qInfo().noquote() << "  unrelated answered:" << text;
        QVERIFY(top1 * 100 >= n * 80);
        QVERIFY(top3 * 100 >= n * 90);
        QVERIFY(silent * 100 >= unrelated.size() * 75);
    }

    void answersQuestionsOnTheDocs()
    {
        const DocsLibrary library{QStringLiteral(ORCHARD_DOCS_DIR)};
        DocsAssistant assistant(&library);
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
            QVERIFY2(spy.wait(2000), entry.question);
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
            QVERIFY2(spy.wait(2000), unrelated);
            QVERIFY2(assistant.answers().isEmpty(), unrelated);
        }
        assistant.ask(QStringLiteral("how do I open the queue"));
        QTRY_VERIFY_WITH_TIMEOUT(!assistant.answers().isEmpty(), 2000);
        assistant.ask(QString());
        QVERIFY(assistant.answers().isEmpty());
        QVERIFY(assistant.question().isEmpty());
    }
};

QTEST_GUILESS_MAIN(DocsSearchTest)
#include "docs_search_test.moc"
