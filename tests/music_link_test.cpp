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

#include "music_link.h"
#include "music_link_controller.h"
#include "auth/auth_manager.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonArray>
#include <QSettings>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTest>

namespace {
struct Invocation {
  QString method;
  QJsonObject payload;
};
QList<Invocation> calls;
quint64 nextId = 0;
QJsonObject song(const QString &id = QStringLiteral("abcdefghijk")) {
  return {{"id", id}, {"type", "song"}, {"title", "Shared song"}};
}
}

YouTubeProvider::YouTubeProvider(QObject *parent) : QObject(parent), m_runtime(nullptr) {}
quint64 YouTubeProvider::invoke(const QString &method, const QJsonValue &payload) {
  calls.append({method, payload.toObject()});
  return ++nextId;
}
quint64 YouTubeProvider::loadAccountProfile(const QJsonObject &) { return 0; }
quint64 YouTubeProvider::enrichAccountProfile(const QJsonObject &) { return 0; }
void YouTubeProvider::cancelAll() {}

class MusicLinkTest final : public QObject {
  Q_OBJECT
  QTemporaryDir m_settings;

  void signIn(AuthManager &auth) {
    auth.acceptHelperResult({{"cookie", "SAPISID=fixture; SID=fixture"},
                             {"userName", "Listener"}, {"userAvatar", "https://example.com/avatar"}});
    QVERIFY(auth.isSignedIn());
    QCoreApplication::processEvents();
  }

private slots:
  void initTestCase() {
    QVERIFY(m_settings.isValid());
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setOrganizationName("OrchardTest");
    QCoreApplication::setApplicationName("MusicLinks");
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_settings.path());
  }

  void init() {
    calls.clear();
    QSettings().clear();
  }

  void parsesLinks_data() {
    QTest::addColumn<QString>("url");
    QTest::addColumn<QString>("id");
    QTest::addColumn<int>("kind");
    const auto row = [](const char *name, const QString &url, const QString &id, MusicLink::Kind kind) {
      QTest::newRow(name) << url << id << int(kind);
    };
    for (const auto &host : {"music.youtube.com", "youtube.com", "www.youtube.com", "m.youtube.com"})
      row(host, QString("https://%1/watch?v=abcdefghijk&list=PL123&si=tracking").arg(host),
          "abcdefghijk", MusicLink::Kind::Song);
    row("short", "https://youtu.be/abcdefghijk?si=tracking&t=32", "abcdefghijk", MusicLink::Kind::Song);
    row("http", "http://youtube.com/watch?v=abcdefghijk", "abcdefghijk", MusicLink::Kind::Song);
    row("shorts", "https://youtube.com/shorts/abcdefghijk", "abcdefghijk", MusicLink::Kind::Song);
    row("embed", "https://youtube.com/embed/abcdefghijk", "abcdefghijk", MusicLink::Kind::Song);
    row("live", "https://youtube.com/live/abcdefghijk", "abcdefghijk", MusicLink::Kind::Song);
    row("playlist", "https://music.youtube.com/playlist?list=PL123&si=tracking", "PL123", MusicLink::Kind::Playlist);
    row("playlist-browse-id", "https://youtube.com/playlist?list=VLPL123", "PL123", MusicLink::Kind::Playlist);
    row("album-playlist", "https://music.youtube.com/playlist?list=OLAK5uy_sample", "OLAK5uy_sample", MusicLink::Kind::Playlist);
    row("album", "https://music.youtube.com/browse/MPREb_sample", "MPREb_sample", MusicLink::Kind::Album);
    row("protocol-song", "orchard://watch?v=abcdefghijk", "abcdefghijk", MusicLink::Kind::Song);
    row("protocol-playlist", "orchard://playlist?list=PL123", "PL123", MusicLink::Kind::Playlist);
    row("protocol-album", "orchard://browse/MPREb_sample", "MPREb_sample", MusicLink::Kind::Album);
  }

  void parsesLinks() {
    QFETCH(QString, url);
    QFETCH(QString, id);
    QFETCH(int, kind);
    const auto result = parseMusicLink(url);
    QVERIFY(result.has_value());
    QCOMPARE(result->id, id);
    QCOMPARE(int(result->kind), kind);
  }

  void rejectsUnsupportedLinks() {
    for (const auto &url : {"", "abcdefghijk", "--help", "file:///tmp/song", "javascript:alert(1)",
         "https://youtube.com.evil.example/watch?v=abcdefghijk", "https://evil.example/watch?v=abcdefghijk",
         "https://youtube.com@evil.example/watch?v=abcdefghijk", "https://user@youtube.com/watch?v=abcdefghijk",
         "https://youtube.com:444/watch?v=abcdefghijk", "https://youtube.com/watch?v=short",
         "https://youtube.com/watch?v=abcdefghijk%26other", "https://youtube.com/playlist?list=PL%2Fbad",
         "https://youtube.com/playlist?list=", "https://youtube.com/browse/UC123", "https://youtu.be/abcdefghijk/extra",
         "https://music.youtube.com/", "orchard://evil/watch?v=abcdefghijk", "ftp://youtube.com/watch?v=abcdefghijk"})
      QVERIFY2(!parseMusicLink(QString::fromLatin1(url)), url);
    QVERIFY(!parseMusicLink(QString(9000, QLatin1Char('x'))));
  }

  void resolvesSongWithMetadata() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    signIn(auth);
    MusicLinkController links(&provider, &auth);
    QSignalSpy played(&links, &MusicLinkController::songRequested);
    links.open("https://youtu.be/abcdefghijk");
    QCOMPARE(calls.size(), 1);
    QCOMPARE(calls.last().method, QString("catalog.track"));
    QCOMPARE(calls.last().payload.value("videoId").toString(), QString("abcdefghijk"));
    QVERIFY(!calls.last().payload.value("session").toObject().value("cookie").toString().isEmpty());
    provider.resultReady(nextId, song());
    QCOMPARE(played.size(), 1);
    QCOMPARE(played.first().first().toMap().value("title").toString(), QString("Shared song"));
  }

  void defersUntilSignedInAndKeepsLatest() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    MusicLinkController links(&provider, &auth);
    links.open("https://youtu.be/abcdefghijk");
    links.open("https://youtu.be/lmnopqrstuv");
    QCOMPARE(calls.size(), 0);
    signIn(auth);
    QCOMPARE(calls.size(), 1);
    QCOMPARE(calls.first().payload.value("videoId").toString(), QString("lmnopqrstuv"));
  }

  void ignoresStaleResultsAndErrors() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    signIn(auth);
    MusicLinkController links(&provider, &auth);
    QSignalSpy played(&links, &MusicLinkController::songRequested);
    QSignalSpy notices(&links, &MusicLinkController::noticeRequested);
    links.open("https://youtu.be/abcdefghijk");
    const auto old = nextId;
    links.open("https://youtu.be/lmnopqrstuv");
    provider.resultReady(old, song());
    provider.requestFailed(old, "stale failure");
    QCOMPARE(played.size(), 0);
    QCOMPARE(notices.size(), 0);
    provider.resultReady(nextId, song("lmnopqrstuv"));
    QCOMPARE(played.size(), 1);
  }

  void invalidatesLookupOnSessionChange() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    signIn(auth);
    MusicLinkController links(&provider, &auth);
    QSignalSpy played(&links, &MusicLinkController::songRequested);
    links.open("https://youtu.be/abcdefghijk");
    const auto old = nextId;
    auth.signOut();
    provider.resultReady(old, song());
    QCOMPARE(played.size(), 0);
    signIn(auth);
    QVERIFY(nextId > old);
    provider.resultReady(nextId, song());
    QCOMPARE(played.size(), 1);
  }

  void resolvesCollections_data() {
    QTest::addColumn<QString>("url");
    QTest::addColumn<QString>("method");
    QTest::addColumn<QString>("id");
    QTest::newRow("playlist") << QString("orchard://playlist?list=PL123") << QString("catalog.playlist") << QString("PL123");
    QTest::newRow("album") << QString("orchard://browse/MPREb_sample") << QString("catalog.album") << QString("MPREb_sample");
    QTest::newRow("album-playlist") << QString("https://music.youtube.com/playlist?list=OLAK5uy_sample")
        << QString("catalog.playlist") << QString("OLAK5uy_sample");
  }

  void resolvesCollections() {
    QFETCH(QString, url);
    QFETCH(QString, method);
    QFETCH(QString, id);
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    signIn(auth);
    MusicLinkController links(&provider, &auth);
    QSignalSpy played(&links, &MusicLinkController::collectionRequested);
    links.open(url);
    QCOMPARE(calls.last().method, method);
    QCOMPARE(calls.last().payload.value("browseId").toString(), id);
    provider.resultReady(nextId, QJsonObject{{"tracks", QJsonArray{
        QJsonObject{{"id", "unavailable"}, {"type", "song"}, {"unplayable", true}}, song()}},
        {"continuation", "page-two"}});
    QCOMPARE(played.size(), 1);
    QCOMPARE(played.first().at(0).toList().size(), 2);
    QCOMPARE(played.first().at(1).toInt(), 1);
    QCOMPARE(played.first().at(2).toString(), method == "catalog.playlist" ? id : QString());
    QCOMPARE(played.first().at(3).toString(), QString("page-two"));
  }

  void failuresDoNotPlay() {
    YouTubeProvider provider;
    AuthManager auth(&provider, true);
    signIn(auth);
    MusicLinkController links(&provider, &auth);
    QSignalSpy played(&links, &MusicLinkController::songRequested);
    QSignalSpy collections(&links, &MusicLinkController::collectionRequested);
    QSignalSpy notices(&links, &MusicLinkController::noticeRequested);
    links.open("https://example.com/watch?v=abcdefghijk");
    QCOMPARE(calls.size(), 0);
    links.open("https://youtu.be/abcdefghijk");
    provider.requestFailed(nextId, "unavailable");
    links.open("https://youtu.be/abcdefghijk");
    provider.resultReady(nextId, song("lmnopqrstuv"));
    links.open("orchard://playlist?list=PL123");
    provider.resultReady(nextId, QJsonObject{{"tracks", QJsonArray{}}});
    links.open("orchard://playlist?list=PL123");
    provider.resultReady(nextId, QJsonObject{{"tracks", QJsonArray{QJsonObject{
        {"id", "unavailable"}, {"type", "song"}, {"unplayable", true}}}}});
    QCOMPARE(played.size(), 0);
    QCOMPARE(collections.size(), 0);
    QCOMPARE(notices.size(), 5);
  }
};

QTEST_GUILESS_MAIN(MusicLinkTest)
#include "music_link_test.moc"
