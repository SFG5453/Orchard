---
title: Use the docs
summary: Open the docs window, search it or ask it a question, move between pages, and copy pages for an AI assistant.
group: Help
icon: book-open
keywords:
  - docs
  - documentation
  - help
  - search docs
  - ask a question
  - how do I
  - best matches
  - natural language search
  - copy docs
  - ai assistant
  - markdown
platforms:
  - desktop
order: 160
---

# Use the docs

The docs are a window of their own with a page list on the left and the page on the right. Every page is plain Markdown, so an AI assistant can read it as well.

## Open the docs

Use any of these:

- Select the **Docs** button (a book icon) in the top bar, left of **Account and settings**.
- Press `/` or Ctrl+K, type "Docs", press the Down arrow once to highlight **Docs**, and press Enter. See [Search and Spotlight](search.md).
- Select **Docs** at the bottom of the Settings sidebar. Settings closes and the docs open.

## Close the docs

Press Esc, select the **X** button in the top right corner, or click outside the window.

## Find a page in the docs

1. Click the **Search or ask a question** box at the top of the page list. It is ready for typing when the docs open.
2. Type one or more words.
3. Click a row in the list, or press Enter to open the first row.

The list shows **Best matches** for your words. See "Ask the docs a question" below. When there are no best matches, the list keeps the pages that contain every word. It says "No pages match" followed by your words when nothing fits. Select the clear button in the search box to see every page again.

## Ask the docs a question

Type a question the way you would say it, for example "how do I open the queue" or "how to turn on crossfade". Orchard replaces the page list with **Best matches**: the sections of the docs that answer your question, best first.

1. Click the **Search or ask a question** box at the top of the page list.
2. Type your question and pause. A question needs at least three characters.
3. Click a row under **Best matches**, or press Enter to open the first row.

Each row shows the name of a section and the page it belongs to. The page opens at that section, and the section's card lights up for a moment. A row that shows a page name with a description opens the top of that page. The row you opened stays marked.

While Orchard looks for answers, a thin green bar sweeps under the search box. The first question after Orchard starts takes about a second longer, because Orchard reads the docs first.

A small search model that ships with Orchard runs on your computer. Your question does not leave your computer.

Clear the search box to see every page again. If no **Best matches** appear, see [Troubleshooting](troubleshooting.md).

## Move between pages

Click a page in the list on the left. At the bottom of each page, **Previous** and **Next** open the neighboring pages, and **Related pages** lists the pages that the current page links to.

## Copy a page for an AI assistant

Select **Copy page** at the top of the page. Orchard puts the page on the clipboard as Markdown, including its metadata header, and the button reads "Copied" for a moment. Paste it into the assistant of your choice and ask your question.

## Copy every page for an AI assistant

Select **Copy all docs for AI** at the bottom of the page list. Orchard copies one Markdown document with a table of contents followed by every page. The button reads "Copied all docs" for a moment.

## Read the docs outside Orchard

The pages live in the `docs/app` folder of the Orchard source as `.md` files. Each file starts with a metadata block that holds the title, summary, group, icon, keywords, and platforms.
