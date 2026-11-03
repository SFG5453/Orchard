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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "playback/apple_youtube_match.h"
#include <QTest>

namespace {

QVariantMap apple() {
  return {{QStringLiteral("title"), QStringLiteral("Come Thru (with Usher)")},
          {QStringLiteral("artist"), QStringLiteral("Summer Walker & Usher")},
          {QStringLiteral("album"), QStringLiteral("Over It")},
          {QStringLiteral("durationSeconds"), 181.0},
          {QStringLiteral("explicit"), true}};
}

QJsonObject song(const QString &id, const QString &title, const QString &artist, double seconds,
                 const QString &album = QStringLiteral("Over It"), const QString &views = QString()) {
  return {{QStringLiteral("id"), id},
          {QStringLiteral("title"), title},
          {QStringLiteral("artist"), artist},
          {QStringLiteral("album"), album},
          {QStringLiteral("durationSeconds"), seconds},
          {QStringLiteral("explicit"), true},
          {QStringLiteral("views"), views}};
}

} // namespace

class AppleYouTubeMatchTest : public QObject {
  Q_OBJECT

private slots:
  void picksTheSameRecording() {
    const QJsonArray candidates{
        song(QStringLiteral("remix"), QStringLiteral("Come Thru (Remix)"), QStringLiteral("Summer Walker"), 181),
        song(QStringLiteral("edit"), QStringLiteral("Come Thru"), QStringLiteral("Summer Walker"), 170),
        song(QStringLiteral("main"), QStringLiteral("Come Thru"), QStringLiteral("Summer Walker & Usher"), 182)};
    QCOMPARE(appleyt::bestVideoId(apple(), candidates), QStringLiteral("main"));
  }

  void rejectsUncreditedGuestsAndOtherCuts() {
    QVERIFY(appleyt::score(apple(), song(QStringLiteral("a"), QStringLiteral("Come Thru (feat. Drake)"),
                                         QStringLiteral("Summer Walker"), 181).toVariantMap()) < 0);
    QJsonObject clean = song(QStringLiteral("b"), QStringLiteral("Come Thru"), QStringLiteral("Summer Walker"), 181);
    clean.insert(QStringLiteral("explicit"), false);
    QVERIFY(appleyt::score(apple(), clean.toVariantMap()) < 0);
    QVERIFY(appleyt::score(apple(), song(QStringLiteral("c"), QStringLiteral("Come Thru"),
                                         QStringLiteral("Someone Else"), 181).toVariantMap()) < 0);
    QVERIFY(appleyt::bestVideoId(apple(), {}).isEmpty());
  }

  void breaksTiesOnPlays() {
    const QJsonArray candidates{
        song(QStringLiteral("dup"), QStringLiteral("Come Thru"), QStringLiteral("Summer Walker"), 181,
             QStringLiteral("Over It"), QStringLiteral("12K plays")),
        song(QStringLiteral("main"), QStringLiteral("Come Thru"), QStringLiteral("Summer Walker"), 181,
             QStringLiteral("Over It"), QStringLiteral("48M plays"))};
    QCOMPARE(appleyt::bestVideoId(apple(), candidates), QStringLiteral("main"));
    QCOMPARE(appleyt::playCount(QStringLiteral("1.2M plays")), 1.2e6);
    QCOMPARE(appleyt::playCount(QStringLiteral("947 views")), 947.0);
  }

  // Real search rows for an Apple song credited "Jhené Aiko", with the guest only in its title.
  void acceptsTitleCreditsAndPaddedUploads() {
    const QVariantMap soGood{{QStringLiteral("title"), QStringLiteral("So Good (feat. Kendrick Lamar)")},
                             {QStringLiteral("artist"), QStringLiteral("Jhené Aiko")},
                             {QStringLiteral("durationSeconds"), 231.0},
                             {QStringLiteral("explicit"), true}};
    QJsonObject other = song(QStringLiteral("tMBAtbxPQEk"), QStringLiteral("So Good"), QStringLiteral("B.o.B"), 211);
    other.insert(QStringLiteral("explicit"), false);
    const QJsonArray candidates{
        song(QStringLiteral("KFcZLiCJGyU"), QStringLiteral("So Good (feat. Kendrick Lamar)"),
             QStringLiteral("Jhené Aiko"), 238, QStringLiteral("Westside Whimsy")),
        other};
    QCOMPARE(appleyt::bestVideoId(soGood, candidates), QStringLiteral("KFcZLiCJGyU"));
  }

  void searchesWithoutCredits() {
    QCOMPARE(appleyt::searchQuery(apple()), QStringLiteral("come thru Summer Walker & Usher"));
  }
};

QTEST_GUILESS_MAIN(AppleYouTubeMatchTest)
#include "apple_youtube_match_test.moc"
