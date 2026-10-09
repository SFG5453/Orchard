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

#include "local_lyrics.h"

#include <QFile>
#include <QRegularExpression>
#include <QStringDecoder>
#include <algorithm>

namespace local {
namespace {

struct Line {
  double start{0};
  double end{-1};
  QString text;
};

QVariantMap unavailable() {
  return {{QStringLiteral("status"), QStringLiteral("unavailable")},
          {QStringLiteral("mode"), QString()},
          {QStringLiteral("source"), QStringLiteral("local")},
          {QStringLiteral("lines"), QVariantList()}};
}

QVariantMap ready(const QList<Line> &lines, bool synced) {
  QVariantList out;
  for (const Line &line : lines) {
    QVariantMap entry{{QStringLiteral("text"), line.text}};
    if (synced) {
      entry.insert(QStringLiteral("startTime"), line.start);
      if (line.end > line.start)
        entry.insert(QStringLiteral("endTime"), line.end);
    }
    out.append(entry);
  }
  return {{QStringLiteral("status"), QStringLiteral("ready")},
          {QStringLiteral("mode"), synced ? QStringLiteral("synced") : QStringLiteral("unsynced")},
          {QStringLiteral("source"), QStringLiteral("local")},
          {QStringLiteral("lines"), out}};
}

QStringList splitLines(const QString &text) {
  QString normalized = text;
  normalized.replace(QStringLiteral("\r\n"), QStringLiteral("\n")).replace(QLatin1Char('\r'), QLatin1Char('\n'));
  return normalized.split(QLatin1Char('\n'));
}

// 00:01:02,500 or 01:02.50, comma or dot, hours optional.
double parseStamp(const QString &stamp) {
  const QStringList parts = stamp.trimmed().replace(QLatin1Char(','), QLatin1Char('.')).split(QLatin1Char(':'));
  double seconds = 0;
  for (const QString &part : parts)
    seconds = seconds * 60 + part.toDouble();
  return seconds;
}

QVariantMap parseSrt(const QStringList &rows) {
  static const QRegularExpression arrow(
      QStringLiteral("^\\s*(\\d+:\\d+:\\d+[.,]\\d+)\\s*-->\\s*(\\d+:\\d+:\\d+[.,]\\d+)"));
  QList<Line> lines;
  for (qsizetype i = 0; i < rows.size(); ++i) {
    const auto match = arrow.match(rows.at(i));
    if (!match.hasMatch())
      continue;
    QStringList text;
    for (++i; i < rows.size() && !rows.at(i).trimmed().isEmpty(); ++i)
      text.append(rows.at(i).trimmed());
    if (!text.isEmpty())
      lines.append({parseStamp(match.captured(1)), parseStamp(match.captured(2)), text.join(QLatin1Char(' '))});
  }
  return lines.isEmpty() ? unavailable() : ready(lines, true);
}

QVariantMap parseLrc(const QStringList &rows) {
  static const QRegularExpression stamp(QStringLiteral("\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\]"));
  static const QRegularExpression offsetTag(QStringLiteral("^\\s*\\[offset:\\s*(-?\\d+)\\s*\\]"),
                                            QRegularExpression::CaseInsensitiveOption);
  // <00:12.34> word stamps from enhanced LRC; the line view is line-level.
  static const QRegularExpression wordStamp(QStringLiteral("<\\d+:\\d+(?:[.:]\\d+)?>"));
  double offset = 0;
  QList<Line> lines;
  for (const QString &row : rows) {
    if (const auto tag = offsetTag.match(row); tag.hasMatch()) {
      // The spec says positive offsets shift lyrics earlier, in milliseconds.
      offset = -tag.captured(1).toDouble() / 1000.0;
      continue;
    }
    QList<double> starts;
    qsizetype textStart = 0;
    for (auto it = stamp.globalMatch(row); it.hasNext();) {
      const auto match = it.next();
      if (match.capturedStart() != textStart)
        break;
      starts.append(match.captured(1).toDouble() * 60 + match.captured(2).replace(QLatin1Char(':'), QLatin1Char('.')).toDouble());
      textStart = match.capturedEnd();
    }
    if (starts.isEmpty())
      continue;
    QString text = row.mid(textStart);
    text.remove(wordStamp);
    text = text.trimmed();
    // Blank stamped lines mark instrumental gaps; keep them as breathing room.
    for (const double start : starts)
      lines.append({qMax(0.0, start + offset), -1, text});
  }
  if (lines.isEmpty())
    return unavailable();
  std::stable_sort(lines.begin(), lines.end(), [](const Line &a, const Line &b) { return a.start < b.start; });
  for (qsizetype i = 0; i + 1 < lines.size(); ++i)
    lines[i].end = lines.at(i + 1).start;
  return ready(lines, true);
}

QVariantMap parsePlain(const QStringList &rows) {
  QList<Line> lines;
  for (const QString &row : rows) {
    const QString text = row.trimmed();
    if (!text.isEmpty())
      lines.append({0, -1, text});
  }
  return lines.isEmpty() ? unavailable() : ready(lines, false);
}

} // namespace

QVariantMap parseLyrics(const QString &input) {
  QString text = input;
  if (text.startsWith(QChar(0xFEFF)))
    text.remove(0, 1);
  if (text.trimmed().isEmpty())
    return unavailable();
  const QStringList rows = splitLines(text);
  static const QRegularExpression srtHint(QStringLiteral("\\d+:\\d+:\\d+[.,]\\d+\\s*-->"));
  if (srtHint.match(text).hasMatch())
    return parseSrt(rows);
  const QVariantMap synced = parseLrc(rows);
  return synced.value(QStringLiteral("status")).toString() == QStringLiteral("ready") ? synced : parsePlain(rows);
}

QVariantMap parseLyricsFile(const QString &path) {
  QFile file(path);
  if (!file.open(QIODevice::ReadOnly))
    return unavailable();
  const QByteArray bytes = file.read(2 * 1024 * 1024);
  QStringDecoder utf8(QStringDecoder::Utf8, QStringConverter::Flag::Stateless);
  const QString decoded = utf8(bytes);
  return parseLyrics(utf8.hasError() ? QString::fromLatin1(bytes) : decoded);
}

} // namespace local
