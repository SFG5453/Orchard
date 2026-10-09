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

#include "docs_library.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QRegularExpression>
#include <algorithm>

namespace {
constexpr int kDefaultOrder = 1000;

QString unquoted(const QString &value)
{
    if (value.size() >= 2 && (value.front() == QLatin1Char('"') || value.front() == QLatin1Char('\''))
        && value.back() == value.front()) {
        return value.mid(1, value.size() - 2);
    }
    return value;
}

int orderOf(const QVariantMap &meta)
{
    bool ok = false;
    const int order = meta.value(QStringLiteral("order")).toString().toInt(&ok);
    return ok ? order : kDefaultOrder;
}
} // namespace

QString DocsLibrary::parseFrontmatter(const QString &raw, QVariantMap *meta)
{
    if (meta)
        meta->clear();
    QString text = raw;
    text.remove(QLatin1Char('\r'));
    if (!text.startsWith(QStringLiteral("---\n")))
        return text;

    const QStringList lines = text.split(QLatin1Char('\n'));
    qsizetype close = -1;
    for (qsizetype i = 1; i < lines.size(); ++i) {
        if (lines.at(i).trimmed() == QLatin1String("---")) {
            close = i;
            break;
        }
    }
    // Without a closing fence the header is plain body text.
    if (close < 0)
        return text;

    static const QRegularExpression keyLine(QStringLiteral(R"(^([A-Za-z][\w-]*):\s*(.*)$)"));
    static const QRegularExpression itemLine(QStringLiteral(R"(^\s*-\s+(.*)$)"));
    QVariantMap parsed;
    QString listKey;
    QStringList list;
    for (qsizetype i = 1; i < close; ++i) {
        const QString &line = lines.at(i);
        const QRegularExpressionMatch key = keyLine.match(line);
        if (key.hasMatch()) {
            const QString name = key.captured(1);
            const QString value = unquoted(key.captured(2).trimmed());
            listKey = value.isEmpty() ? name : QString();
            list.clear();
            parsed.insert(name, value.isEmpty() ? QVariant(QStringList()) : QVariant(value));
            continue;
        }
        const QRegularExpressionMatch item = itemLine.match(line);
        if (item.hasMatch() && !listKey.isEmpty()) {
            list.append(unquoted(item.captured(1).trimmed()));
            parsed.insert(listKey, list);
        }
    }
    if (meta)
        *meta = parsed;
    return lines.mid(close + 1).join(QLatin1Char('\n')).trimmed();
}

DocsLibrary::DocsLibrary(const QString &root, QObject *parent)
    : QObject(parent)
{
    const QDir dir(root);
    const QStringList files = dir.entryList({QStringLiteral("*.md")}, QDir::Files);
    for (const QString &name : files) {
        QFile file(dir.filePath(name));
        if (!file.open(QIODevice::ReadOnly | QIODevice::Text))
            continue;
        Page page;
        page.id = QFileInfo(name).completeBaseName();
        page.raw = QString::fromUtf8(file.readAll());
        page.body = parseFrontmatter(page.raw, &page.meta);
        if (page.meta.value(QStringLiteral("title")).toString().isEmpty())
            page.meta.insert(QStringLiteral("title"), page.id);
        m_pages.append(page);
    }
    // Explicit "order" first, file name as the tiebreak. Even docs need a bouncer.
    std::sort(m_pages.begin(), m_pages.end(), [](const Page &a, const Page &b) {
        const int left = orderOf(a.meta);
        const int right = orderOf(b.meta);
        return left != right ? left < right : a.id < b.id;
    });
}

const DocsLibrary::Page *DocsLibrary::find(const QString &id) const
{
    for (const Page &page : m_pages) {
        if (page.id == id)
            return &page;
    }
    return nullptr;
}

QVariantList DocsLibrary::pages() const
{
    QVariantList list;
    for (const Page &page : m_pages) {
        const QString icon = page.meta.value(QStringLiteral("icon")).toString();
        const QString group = page.meta.value(QStringLiteral("group")).toString();
        list.append(QVariantMap{
            {QStringLiteral("id"), page.id},
            {QStringLiteral("title"), page.meta.value(QStringLiteral("title"))},
            {QStringLiteral("summary"), page.meta.value(QStringLiteral("summary"))},
            {QStringLiteral("group"), group.isEmpty() ? QStringLiteral("Docs") : group},
            {QStringLiteral("icon"), icon.isEmpty() ? QStringLiteral("book-open") : icon},
            {QStringLiteral("keywords"), page.meta.value(QStringLiteral("keywords")).toStringList()},
        });
    }
    return list;
}

QVariantList DocsLibrary::sections(const QString &id) const
{
    const Page *page = find(id);
    if (!page)
        return {};

    static const QRegularExpression headingLine(QStringLiteral(R"(^(#{1,3})\s+(.+?)\s*$)"));
    static const QRegularExpression orderedItem(QStringLiteral(R"(^\d+\.\s+(.*)$)"));
    static const QRegularExpression bulletItem(QStringLiteral(R"(^[-*]\s+(.*)$)"));
    static const QRegularExpression link(QStringLiteral(R"(\[([^\]]+)\]\([^)\s]+\))"));

    QVariantList sections;
    QVariantList blocks;
    QString title;
    QStringList paragraph;
    QStringList items;
    QString listKind;
    QStringList code;
    bool fenced = false;

    // The viewer has no link styling, so page links read as plain text.
    const auto plain = [&](QString text) { return text.replace(link, QStringLiteral("\\1")); };
    const auto textBlock = [](const QString &kind, const QString &text) {
        return QVariantMap{{QStringLiteral("kind"), kind}, {QStringLiteral("text"), text}};
    };
    const auto flushParagraph = [&] {
        if (!paragraph.isEmpty())
            blocks.append(textBlock(QStringLiteral("paragraph"), plain(paragraph.join(QLatin1Char('\n')))));
        paragraph.clear();
    };
    const auto flushList = [&] {
        if (!items.isEmpty()) {
            QStringList shown;
            for (const QString &item : std::as_const(items))
                shown.append(plain(item));
            blocks.append(QVariantMap{{QStringLiteral("kind"), listKind}, {QStringLiteral("items"), shown}});
        }
        items.clear();
        listKind.clear();
    };
    const auto flushSection = [&] {
        flushParagraph();
        flushList();
        if (!blocks.isEmpty() || !title.isEmpty()) {
            sections.append(QVariantMap{{QStringLiteral("title"), title}, {QStringLiteral("blocks"), blocks}});
        }
        blocks.clear();
        title.clear();
    };
    const auto addItem = [&](const QString &kind, const QString &text) {
        flushParagraph();
        if (listKind != kind)
            flushList();
        listKind = kind;
        items.append(text);
    };

    for (const QString &line : page->body.split(QLatin1Char('\n'))) {
        if (fenced) {
            if (line.startsWith(QStringLiteral("```"))) {
                blocks.append(textBlock(QStringLiteral("code"), code.join(QLatin1Char('\n'))));
                code.clear();
                fenced = false;
            } else {
                code.append(line);
            }
            continue;
        }
        if (line.startsWith(QStringLiteral("```"))) {
            flushParagraph();
            flushList();
            fenced = true;
            continue;
        }
        const QRegularExpressionMatch heading = headingLine.match(line);
        if (heading.hasMatch()) {
            const qsizetype level = heading.capturedLength(1);
            // The title lives in the page header.
            if (level == 1) {
                flushSection();
            } else if (level == 2) {
                flushSection();
                title = heading.captured(2);
            } else {
                flushParagraph();
                flushList();
                blocks.append(textBlock(QStringLiteral("subheading"), heading.captured(2)));
            }
            continue;
        }
        if (line.trimmed().isEmpty()) {
            flushParagraph();
            flushList();
            continue;
        }
        const QRegularExpressionMatch ordered = orderedItem.match(line);
        const QRegularExpressionMatch bullet = bulletItem.match(line);
        if (ordered.hasMatch()) {
            addItem(QStringLiteral("steps"), ordered.captured(1));
        } else if (bullet.hasMatch()) {
            addItem(QStringLiteral("bullets"), bullet.captured(1));
        } else if (!items.isEmpty() && line.startsWith(QLatin1Char(' '))) {
            items.last() += QLatin1Char(' ') + line.trimmed();
        } else {
            flushList();
            paragraph.append(line);
        }
    }
    flushSection();
    return sections;
}

QVariantList DocsLibrary::related(const QString &id) const
{
    const Page *page = find(id);
    if (!page)
        return {};

    static const QRegularExpression pageLink(QStringLiteral(R"(\]\(([a-z0-9-]+)\.md(?:#[^)]*)?\))"));
    QVariantList list;
    QStringList seen{id};
    auto matches = pageLink.globalMatch(page->body);
    while (matches.hasNext()) {
        const QString target = matches.next().captured(1);
        const Page *linked = find(target);
        if (!linked || seen.contains(target))
            continue;
        seen.append(target);
        const QString icon = linked->meta.value(QStringLiteral("icon")).toString();
        list.append(QVariantMap{{QStringLiteral("id"), target},
                                {QStringLiteral("title"), linked->meta.value(QStringLiteral("title"))},
                                {QStringLiteral("icon"), icon.isEmpty() ? QStringLiteral("book-open") : icon}});
    }
    return list;
}

QString DocsLibrary::rawPage(const QString &id) const
{
    const Page *page = find(id);
    return page ? page->raw : QString();
}

QString DocsLibrary::bundle() const
{
    QString out = QStringLiteral(
        "# Orchard documentation\n\n"
        "Orchard is a desktop music player that streams from YouTube Music. "
        "This document holds every in-app documentation page as Markdown. "
        "Each page starts with a metadata block (title, summary, keywords, platforms). "
        "A link such as audio-engine.md points to the page with that id in this document.\n\n"
        "## Pages\n\n");
    for (const Page &page : m_pages) {
        out += QStringLiteral("- %1.md (%2): %3\n")
                   .arg(page.id, page.meta.value(QStringLiteral("title")).toString(),
                        page.meta.value(QStringLiteral("summary")).toString());
    }
    for (const Page &page : m_pages)
        out += QStringLiteral("\n<!-- page: %1 -->\n%2\n").arg(page.id, page.raw.trimmed());
    return out;
}

QStringList DocsLibrary::search(const QString &query) const
{
    const QStringList terms = query.toLower().split(QRegularExpression(QStringLiteral(R"(\s+)")), Qt::SkipEmptyParts);
    QStringList ids;
    for (const Page &page : m_pages) {
        const QString haystack = (page.meta.value(QStringLiteral("title")).toString() + QLatin1Char(' ')
                                  + page.meta.value(QStringLiteral("summary")).toString() + QLatin1Char(' ')
                                  + page.meta.value(QStringLiteral("keywords")).toStringList().join(QLatin1Char(' '))
                                  + QLatin1Char(' ') + page.body)
                                     .toLower();
        const bool all = std::all_of(terms.begin(), terms.end(), [&](const QString &term) {
            return haystack.contains(term);
        });
        if (all)
            ids.append(page.id);
    }
    return ids;
}
