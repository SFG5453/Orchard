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

#include "docs/docs_library.h"

#include <QFile>
#include <QRegularExpression>
#include <QSet>
#include <QTemporaryDir>
#include <QTest>

namespace {
void writePage(const QTemporaryDir &dir, const QString &name, const QString &text)
{
    QFile file(dir.filePath(name));
    QVERIFY(file.open(QIODevice::WriteOnly | QIODevice::Text));
    file.write(text.toUtf8());
}
} // namespace

class DocsLibraryTest : public QObject {
    Q_OBJECT

private slots:
    void parsesScalarsAndLists()
    {
        QVariantMap meta;
        const QString body = DocsLibrary::parseFrontmatter(
            QStringLiteral("---\ntitle: \"Quoted: title\"\norder: 20\nkeywords:\n  - one\n  - two words\n---\n\n# Body\n"),
            &meta);
        QCOMPARE(meta.value("title").toString(), QStringLiteral("Quoted: title"));
        QCOMPARE(meta.value("order").toString(), QStringLiteral("20"));
        QCOMPARE(meta.value("keywords").toStringList(), (QStringList{"one", "two words"}));
        QCOMPARE(body, QStringLiteral("# Body"));
    }

    void unterminatedHeaderStaysBody()
    {
        QVariantMap meta;
        const QString text = QStringLiteral("---\ntitle: Lost\nno closing fence\n");
        QCOMPARE(DocsLibrary::parseFrontmatter(text, &meta), text);
        QVERIFY(meta.isEmpty());
    }

    void handlesWindowsLineEndings()
    {
        QVariantMap meta;
        const QString body = DocsLibrary::parseFrontmatter(QStringLiteral("---\r\ntitle: Crlf\r\n---\r\nText\r\n"), &meta);
        QCOMPARE(meta.value("title").toString(), QStringLiteral("Crlf"));
        QCOMPARE(body, QStringLiteral("Text"));
    }

    void ordersPagesByOrderThenId()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "zeta.md", "---\ntitle: Zeta\norder: 1\n---\n# Zeta\n");
        writePage(dir, "beta.md", "---\ntitle: Beta\norder: 5\n---\n# Beta\n");
        writePage(dir, "alpha.md", "---\ntitle: Alpha\norder: 5\n---\n# Alpha\n");
        // No header at all: falls back to the file name and sorts last.
        writePage(dir, "bare.md", "Just text.\n");
        writePage(dir, "ignored.txt", "not a page");

        const DocsLibrary library(dir.path());
        QStringList ids;
        for (const QVariant &page : library.pages())
            ids << page.toMap().value("id").toString();
        QCOMPARE(ids, (QStringList{"zeta", "alpha", "beta", "bare"}));
        QCOMPARE(library.pages().last().toMap().value("title").toString(), QStringLiteral("bare"));
    }

    void buildsSections()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "page.md",
                  "---\ntitle: Page\n---\n# Page\n\nIntro with [Other](other.md) link.\n\n## First\n\n"
                  "1. Step one\n2. Step two\n\n```\n# not a heading\n```\n\n### Deep\n\nTail.\n\n"
                  "## Lists\n\n- plain\n- wrapped\n  continuation\n");
        const DocsLibrary library(dir.path());
        const QVariantList sections = library.sections("page");

        QCOMPARE(sections.size(), 3);
        // Text before the first "##" becomes an untitled intro section.
        const QVariantMap intro = sections.at(0).toMap();
        QCOMPARE(intro.value("title").toString(), QString());
        const QVariantList introBlocks = intro.value("blocks").toList();
        QCOMPARE(introBlocks.size(), 1);
        QCOMPARE(introBlocks.first().toMap().value("kind").toString(), QStringLiteral("paragraph"));
        // Page links read as plain text because the viewer cannot style links.
        QCOMPARE(introBlocks.first().toMap().value("text").toString(), QStringLiteral("Intro with Other link."));

        const QVariantMap first = sections.at(1).toMap();
        QCOMPARE(first.value("title").toString(), QStringLiteral("First"));
        const QVariantList blocks = first.value("blocks").toList();
        QCOMPARE(blocks.size(), 4);
        QCOMPARE(blocks.at(0).toMap().value("kind").toString(), QStringLiteral("steps"));
        QCOMPARE(blocks.at(0).toMap().value("items").toStringList(), (QStringList{"Step one", "Step two"}));
        QCOMPARE(blocks.at(1).toMap().value("kind").toString(), QStringLiteral("code"));
        QCOMPARE(blocks.at(1).toMap().value("text").toString(), QStringLiteral("# not a heading"));
        QCOMPARE(blocks.at(2).toMap().value("kind").toString(), QStringLiteral("subheading"));
        QCOMPARE(blocks.at(2).toMap().value("text").toString(), QStringLiteral("Deep"));
        QCOMPARE(blocks.at(3).toMap().value("text").toString(), QStringLiteral("Tail."));

        const QVariantMap lists = sections.at(2).toMap();
        const QVariantMap bullets = lists.value("blocks").toList().first().toMap();
        QCOMPARE(bullets.value("kind").toString(), QStringLiteral("bullets"));
        QCOMPARE(bullets.value("items").toStringList(), (QStringList{"plain", "wrapped continuation"}));
        QVERIFY(library.sections("missing").isEmpty());
    }

    void searchMatchesEveryWord()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "a.md", "---\ntitle: Alpha\norder: 1\nkeywords:\n  - equalizer\n---\n# Alpha\nBass and treble.\n");
        writePage(dir, "b.md", "---\ntitle: Beta\norder: 2\n---\n# Beta\nTreble only.\n");
        const DocsLibrary library(dir.path());
        QCOMPARE(library.search("treble"), (QStringList{"a", "b"}));
        QCOMPARE(library.search("BASS treble"), (QStringList{"a"}));
        QCOMPARE(library.search("equalizer"), (QStringList{"a"}));
        QVERIFY(library.search("nothing like this").isEmpty());
        QCOMPARE(library.search("  "), (QStringList{"a", "b"}));
    }

    void pagesCarryGroupAndIcon()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "a.md", "---\ntitle: A\ngroup: Sound\nicon: play\nkeywords:\n  - one\n---\n# A\n");
        writePage(dir, "b.md", "---\ntitle: B\n---\n# B\n");
        const DocsLibrary library(dir.path());
        const QVariantMap a = library.pages().at(0).toMap();
        QCOMPARE(a.value("group").toString(), QStringLiteral("Sound"));
        QCOMPARE(a.value("icon").toString(), QStringLiteral("play"));
        QCOMPARE(a.value("keywords").toStringList(), (QStringList{"one"}));
        // Missing metadata falls back to a generic group and icon.
        const QVariantMap b = library.pages().at(1).toMap();
        QCOMPARE(b.value("group").toString(), QStringLiteral("Docs"));
        QCOMPARE(b.value("icon").toString(), QStringLiteral("book-open"));
    }

    void relatedFollowsPageLinks()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "a.md", "---\ntitle: A\norder: 1\n---\nSee [B](b.md), [B again](b.md#x), [self](a.md), [gone](gone.md).\n");
        writePage(dir, "b.md", "---\ntitle: B\norder: 2\n---\nBody.\n");
        const DocsLibrary library(dir.path());
        const QVariantList related = library.related("a");
        QCOMPARE(related.size(), 1);
        QCOMPARE(related.first().toMap().value("id").toString(), QStringLiteral("b"));
        QCOMPARE(related.first().toMap().value("title").toString(), QStringLiteral("B"));
        QCOMPARE(related.first().toMap().value("icon").toString(), QStringLiteral("book-open"));
        QVERIFY(library.related("b").isEmpty());
    }

    void bundleHoldsEveryPageVerbatim()
    {
        QTemporaryDir dir;
        QVERIFY(dir.isValid());
        writePage(dir, "a.md", "---\ntitle: A\nsummary: First page.\norder: 1\n---\n# A\n");
        writePage(dir, "b.md", "---\ntitle: B\nsummary: Second page.\norder: 2\n---\n# B\n");
        const DocsLibrary library(dir.path());
        const QString bundle = library.bundle();

        QVERIFY(bundle.startsWith(QStringLiteral("# Orchard documentation")));
        QVERIFY(bundle.contains(QStringLiteral("- a.md (A): First page.")));
        QVERIFY(bundle.contains(QStringLiteral("<!-- page: b -->\n---\ntitle: B")));
        QVERIFY(bundle.indexOf(QStringLiteral("<!-- page: a -->")) < bundle.indexOf(QStringLiteral("<!-- page: b -->")));
        QCOMPARE(library.rawPage("a"), QStringLiteral("---\ntitle: A\nsummary: First page.\norder: 1\n---\n# A\n"));
        QVERIFY(library.rawPage("missing").isEmpty());
    }

    void embeddedPagesMatchDisk()
    {
        const DocsLibrary embedded;
        const DocsLibrary disk{QStringLiteral(ORCHARD_DOCS_DIR)};
        QVERIFY(!embedded.pages().isEmpty());
        QCOMPARE(embedded.pages(), disk.pages());
        QCOMPARE(embedded.bundle(), disk.bundle());
    }

    // Docs reviewers never run in CI, so this test reads the shipped pages for them.
    void shippedDocsAreWellFormed()
    {
        const DocsLibrary library{QStringLiteral(ORCHARD_DOCS_DIR)};
        const QVariantList pages = library.pages();
        QVERIFY2(pages.size() >= 10, "docs/app should hold the shipped pages");

        QSet<QString> ids;
        for (const QVariant &page : pages)
            ids.insert(page.toMap().value("id").toString());

        static const QRegularExpression pageLink(QStringLiteral(R"(\]\(([^)\s]+)\))"));
        static const QRegularExpression tableRow(QStringLiteral("^\\|"), QRegularExpression::MultilineOption);
        QSet<int> orders;
        // The sidebar prints a label whenever the group changes, so each group must be one run.
        QStringList groupRuns;
        for (const QVariant &entry : pages) {
            const QString group = entry.toMap().value("group").toString();
            if (groupRuns.isEmpty() || groupRuns.last() != group) {
                QVERIFY2(!groupRuns.contains(group), qPrintable(QStringLiteral("group split in two runs: ") + group));
                groupRuns.append(group);
            }
        }
        for (const QVariant &entry : pages) {
            const QString id = entry.toMap().value("id").toString();
            const QString title = entry.toMap().value("title").toString();
            const QString raw = library.rawPage(id);
            QVariantMap meta;
            const QString body = DocsLibrary::parseFrontmatter(raw, &meta);

            QVERIFY2(!meta.value("title").toString().isEmpty(), qPrintable(id));
            QVERIFY2(!meta.value("summary").toString().isEmpty(), qPrintable(id));
            QVERIFY2(!meta.value("group").toString().isEmpty(), qPrintable(id));
            const QString icon = meta.value("icon").toString();
            QVERIFY2(QFile::exists(QStringLiteral(ORCHARD_DOCS_DIR "/../../app/qml/assets/lucide/") + icon + QStringLiteral(".svg")),
                     qPrintable(QStringLiteral("missing lucide icon '%1': ").arg(icon) + id));
            QVERIFY2(!meta.value("keywords").toStringList().isEmpty(), qPrintable(id));
            QVERIFY2(!meta.value("platforms").toStringList().isEmpty(), qPrintable(id));
            bool orderOk = false;
            const int order = meta.value("order").toString().toInt(&orderOk);
            QVERIFY2(orderOk, qPrintable(id));
            QVERIFY2(!orders.contains(order), qPrintable(QStringLiteral("duplicate order: ") + id));
            orders.insert(order);

            QVERIFY2(body.startsWith(QStringLiteral("# ") + title + QLatin1Char('\n')),
                     qPrintable(QStringLiteral("H1 must repeat the title: ") + id));
            QVERIFY2(!raw.contains(QChar(0x2014)), qPrintable(QStringLiteral("no em dashes: ") + id));
            QVERIFY2(!body.contains(tableRow),
                     qPrintable(QStringLiteral("no tables, the viewer renders them poorly: ") + id));

            auto links = pageLink.globalMatch(body);
            while (links.hasNext()) {
                const QString target = links.next().captured(1).section(QLatin1Char('#'), 0, 0);
                const QString where = id + QStringLiteral(" -> ") + target;
                QVERIFY2(target.endsWith(QStringLiteral(".md")), qPrintable(QStringLiteral("links must target pages: ") + where));
                QVERIFY2(ids.contains(target.chopped(3)), qPrintable(QStringLiteral("dangling link: ") + where));
            }
            QVERIFY2(!library.sections(id).isEmpty(), qPrintable(id));
        }
    }
};

QTEST_GUILESS_MAIN(DocsLibraryTest)
#include "docs_library_test.moc"
