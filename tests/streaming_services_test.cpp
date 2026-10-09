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

#include "appearance/animated_artwork_store.h"
#include "integrations/spotify_canvas.h"
#include "playback/adaptive_mix/adaptive_mix_controller.h"
#include "providers/qobuz/qobuz_oauth_callback.h"
#include "providers/qobuz/qobuz_service.h"

#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QSettings>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtTest>

// Spotify and Qobuz pieces that need no account, network or keychain.
class StreamingServicesTest final : public QObject {
  Q_OBJECT

private:
  QTemporaryDir m_settings;

private slots:
  void initTestCase() {
    QVERIFY(m_settings.isValid());
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setOrganizationName(QStringLiteral("OrchardTest"));
    QCoreApplication::setApplicationName(QStringLiteral("StreamingServices"));
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_settings.path());
  }

  void spotifyCookieInputs() {
    QCOMPARE(SpotifyCanvas::extractSpdc(QStringLiteral(" AQBraw ")), QStringLiteral("AQBraw"));
    QCOMPARE(SpotifyCanvas::extractSpdc(QStringLiteral("sp_t=x; sp_dc=AQBvalue; sp_key=y")),
             QStringLiteral("AQBvalue"));
    QVERIFY(SpotifyCanvas::extractSpdc(QStringLiteral("sp_t=x")).isEmpty());
    SpotifyCanvas spotify(nullptr, false);
    QVERIFY(!spotify.saveCookie(QStringLiteral("two words")));
    QVERIFY(spotify.messageIsError());
  }

  void spotifyCanvasRequestCarriesTheTrackUri() {
    const QByteArray body = SpotifyCanvas::canvasRequest(QStringLiteral("4uLU6hMCjMI75M1A2tKUQC"));
    QCOMPARE(body.left(4).toHex(), QByteArray("0a260a24"));
    QCOMPARE(body.mid(4), QByteArray("spotify:track:4uLU6hMCjMI75M1A2tKUQC"));
  }

  void spotifyCanvasUrlComesOutOfProtobuf() {
    const QByteArray url = "https://canvaz.scdn.co/upload/artist/a1/video/b2.cnvs.mp4";
    const QByteArray reply = QByteArray("\x0a\x5a\x0a\x24spotify:track:x\x12") + char(url.size()) + url +
                             QByteArray("\x1a\x00\x22", 3);
    QCOMPARE(SpotifyCanvas::canvasUrlFromProtobuf(reply), QString::fromLatin1(url));
    QVERIFY(SpotifyCanvas::canvasUrlFromProtobuf(QByteArray()).isEmpty());
  }

  void spotifySearchOnlyAcceptsTheSameSong() {
    const QJsonObject result = QJsonDocument::fromJson(R"json({"data":{"searchV2":{"tracksV2":{"items":[
      {"item":{"data":{"id":"wrong","name":"Love Story","artists":{"items":[{"profile":{"name":"Taylor Swift"}}]}}}},
      {"item":{"data":{"uri":"spotify:track:right","name":"Love (feat. Zacari)","artists":{"items":[{"profile":{"name":"Kendrick Lamar"}},{"profile":{"name":"Zacari"}}]}}}}
    ]}}}})json").object();
    QCOMPARE(SpotifyCanvas::matchingTrackId(result, QStringLiteral("LOVE. (feat. Zacari)"),
                                            QStringLiteral("Kendrick Lamar")),
             QStringLiteral("right"));
    QVERIFY(SpotifyCanvas::matchingTrackId(result, QStringLiteral("Love"), QStringLiteral("Someone Else")).isEmpty());
  }

  void qobuzCallbackReadsTheCode() {
    const QByteArray path = "/qobuz-callback";
    QCOMPARE(QobuzOAuthCallback::codeFromRequest(
                 "GET /qobuz-callback?code_autorisation=abc%2B1 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", path),
             QStringLiteral("abc+1"));
    QVERIFY(QobuzOAuthCallback::codeFromRequest("GET /favicon.ico HTTP/1.1\r\n\r\n", path).isEmpty());
    QVERIFY(QobuzOAuthCallback::codeFromRequest("POST /qobuz-callback?code_autorisation=x HTTP/1.1\r\n\r\n", path).isEmpty());
  }

  void qobuzCallbackAnswersTheBrowserOnce() {
    QobuzOAuthCallback callback;
    QSignalSpy received(&callback, &QobuzOAuthCallback::codeReceived);
    QUrl url = callback.listen();
    QVERIFY(url.isValid());
    url.setQuery(QStringLiteral("code_autorisation=xyz"));
    QNetworkAccessManager browser;
    QNetworkReply *reply = browser.get(QNetworkRequest(url));
    QTRY_VERIFY(reply->isFinished());
    QCOMPARE(reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt(), 200);
    QCOMPARE(received.count(), 1);
    QCOMPARE(received.first().at(0).toString(), QStringLiteral("xyz"));
    QVERIFY(!callback.listening());
    reply->deleteLater();
  }

  void qobuzOffAnswersAtOnce() {
    QobuzService qobuz(nullptr, false);
    QVERIFY(!qobuz.active());
    bool answered = false;
    qobuz.resolveTrack({{QStringLiteral("title"), QStringLiteral("Song")}},
                       [&answered](const QJsonValue &result, const QString &error) {
      answered = result.isNull() && error.isEmpty();
    });
    QVERIFY(answered);
    // Connected is not enough; only MAX turns Qobuz playback on.
    qobuz.setEnabled(true);
    QVERIFY(!qobuz.active());
  }

  void adaptiveMixStepsAsideAndComesBack() {
    AdaptiveMixController mix;
    mix.setMode(QStringLiteral("adaptive"));
    QSignalSpy changed(&mix, &AdaptiveMixController::modeChanged);
    mix.setAvailable(false);
    QCOMPARE(mix.mode(), QStringLiteral("standard"));
    QVERIFY(!mix.enabled());
    mix.setMode(QStringLiteral("adaptive"));
    QCOMPARE(mix.mode(), QStringLiteral("standard"));
    mix.setAvailable(true);
    QCOMPARE(mix.mode(), QStringLiteral("adaptive"));
    QCOMPARE(changed.count(), 2);
  }

  void artworkMissesCanBeForgotten() {
    QTemporaryDir dir;
    AnimatedArtworkStore store(dir.filePath(QStringLiteral("artwork.sqlite")));
    store.store(QStringLiteral("miss"), QString(), QString());
    store.store(QStringLiteral("hit"), QStringLiteral("https://x/a.mp4"), QStringLiteral("boidu"));
    QVERIFY(store.lookup(QStringLiteral("miss")).has_value());
    QCOMPARE(store.forgetMisses(), 1);
    QVERIFY(!store.lookup(QStringLiteral("miss")).has_value());
    QCOMPARE(store.lookup(QStringLiteral("hit")).value_or(QString()), QStringLiteral("https://x/a.mp4"));
  }
};

QTEST_GUILESS_MAIN(StreamingServicesTest)
#include "streaming_services_test.moc"
