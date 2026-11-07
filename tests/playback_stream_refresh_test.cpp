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

#include "auth/auth_manager.h"
#include "playback/playback_controller.h"
#include "providers/youtube/youtube_provider.h"

#include <QDataStream>
#include <QJsonArray>
#include <QSettings>
#include <QStandardPaths>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QtTest>

namespace {
QList<QJsonObject> invocations;

QByteArray silentWave(int seconds = 60) {
  const QByteArray samples(seconds * 8000 * 2, '\0');
  QByteArray wave;
  QDataStream out(&wave, QIODevice::WriteOnly);
  out.setByteOrder(QDataStream::LittleEndian);
  out.writeRawData("RIFF", 4);
  out << quint32(36 + samples.size());
  out.writeRawData("WAVEfmt ", 8);
  out << quint32(16) << quint16(1) << quint16(1) << quint32(8000)
      << quint32(16000) << quint16(2) << quint16(16);
  out.writeRawData("data", 4);
  out << quint32(samples.size());
  out.writeRawData(samples.constData(), samples.size());
  return wave;
}
}

YouTubeProvider::YouTubeProvider(QObject *parent) : QObject(parent), m_runtime(nullptr) {}
quint64 YouTubeProvider::invoke(const QString &, const QJsonValue &payload) {
  invocations.append(payload.toObject());
  return invocations.size();
}
quint64 YouTubeProvider::loadAccountProfile(const QJsonObject &) { return 0; }
quint64 YouTubeProvider::enrichAccountProfile(const QJsonObject &) { return 0; }
void YouTubeProvider::cancelAll() {}

class PlaybackRecoveryTest final : public QObject {
  Q_OBJECT
private:
  QTemporaryDir m_settings;
  struct Session {
    YouTubeProvider provider;
    AuthManager auth{&provider, true};
    PlaybackController playback{&provider, &auth, nullptr};
  };

  QJsonObject stream() {
    return {{"url", "https://fixture.googlevideo.com/audio"},
            {"provider", "fixture"}, {"mimeType", "audio/wav"},
            {"contentLength", silentWave().size()}, {"durationSeconds", 60}};
  }

  void load(Session &session) {
    auto &playback = session.playback;
    playback.ensurePlaybackBackend();
    playback.m_track = {{"id", "abcdefghijk"}, {"type", "song"}, {"durationSeconds", 60}};
    playback.m_resumePaused = true;
    playback.m_proxy->setRangeReader([wave = silentWave()](const QJsonObject &, qint64 start,
                                                         qint64 end, auto done) {
      QTimer::singleShot(0, [wave, start, end, done] { done(wave.mid(start, end - start + 1), 0); });
    });
    playback.startResolvedStream(stream());
    QTRY_VERIFY(playback.m_player->isSeekable());
    QTRY_VERIFY(playback.m_player->mediaStatus() == QMediaPlayer::LoadedMedia ||
                playback.m_player->mediaStatus() == QMediaPlayer::BufferedMedia);
    invocations.clear();
  }

private slots:
  void initTestCase() {
    QVERIFY(m_settings.isValid());
    QStandardPaths::setTestModeEnabled(true);
    QCoreApplication::setOrganizationName("OrchardTest");
    QCoreApplication::setApplicationName("PlaybackStreamRefresh");
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_settings.path());
  }

  void init() {
    QSettings settings;
    settings.clear();
    settings.setValue("playback/persistenceEnabled", false);
    settings.setValue("playback/autoplayEnabled", false);
    settings.setValue("playback/crossfadeEnabled", false);
    settings.setValue("playback/youtubeHistoryEnabled", false);
    invocations.clear();
  }

  void rejectedChunkRefreshesWithoutRestartingThePlayer() {
    QTcpServer upstream;
    QVERIFY(upstream.listen(QHostAddress::LocalHost));
    connect(&upstream, &QTcpServer::newConnection, this, [&] {
      auto *socket = upstream.nextPendingConnection();
      connect(socket, &QTcpSocket::readyRead, socket, [socket] {
        socket->readAll();
        socket->write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        socket->disconnectFromHost();
      });
    });
    Session session;
    load(session);
    auto &playback = session.playback;
    auto source = stream();
    source.remove("provider");
    source.insert("url", QString("http://127.0.0.1:%1/audio").arg(upstream.serverPort()));
    // The spare proxy serves the rejected stream; the decoder's own URL stays valid.
    std::swap(playback.m_proxy, playback.m_nextProxy);
    const QUrl local = playback.m_proxy->open(source);
    QTcpSocket client;
    client.connectToHost(local.host(), local.port());
    QVERIFY(client.waitForConnected());
    client.write("GET " + local.path().toLatin1() + " HTTP/1.1\r\n\r\n");
    QTRY_COMPARE(invocations.size(), 1);
    QVERIFY(invocations.first().value("refreshStream").toBool());
    QCOMPARE(invocations.first().value("track").toObject().value("id").toString(), QString("abcdefghijk"));
    QVERIFY(!playback.loading());
    // A failed refresh falls back to the restart path.
    session.provider.requestFailed(1, "Unable to refresh stream");
    QTRY_COMPARE(invocations.size(), 2);
    QVERIFY(playback.loading());
  }
};

QTEST_MAIN(PlaybackRecoveryTest)
#include "playback_stream_refresh_test.moc"
