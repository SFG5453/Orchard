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

// The desktop Mix Host end to end: uploaded bytes, loopback proxies, the real worker, PCM back.

#include "connect/connect_mix.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QProcess>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtTest>

namespace {

// Seventy seconds of something with a pulse, so the planner has beats to argue about.
QByteArray song(const QString &dir, const QString &name, const QStringList &encode) {
  const QString path = QDir(dir).filePath(name);
  QProcess ffmpeg;
  ffmpeg.start(QStringLiteral("ffmpeg"),
               QStringList{"-v", "error", "-y", "-f", "lavfi", "-i",
                           "sine=frequency=110:beep_factor=4:duration=70", "-f", "lavfi", "-i",
                           "anoisesrc=d=70:a=0.05", "-filter_complex", "amix=inputs=2,aformat=channel_layouts=stereo"} +
                   encode + QStringList{path});
  if (!ffmpeg.waitForFinished(60000) || ffmpeg.exitCode() != 0)
    return {};
  QFile file(path);
  return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
}

} // namespace

class ConnectMixTest : public QObject {
  Q_OBJECT

private slots:
  void phoneSourcesComeBackMixed() {
    const QString base = QCoreApplication::applicationDirPath();
    if (QStandardPaths::findExecutable(QStringLiteral("ffmpeg")).isEmpty() ||
        !QFile::exists(QDir(base).filePath(QStringLiteral("orchard-adaptive-mix"))) ||
        !QDir(QDir(base).filePath(QStringLiteral("models"))).exists())
      QSKIP("Needs ffmpeg, the adaptive-mix worker and its models");
    QTemporaryDir dir;
    // AAC in MP4 with its index at the end makes FFmpeg seek, so the proxy must serve ranges.
    const QByteArray outgoing = song(dir.path(), "out.m4a", {"-ar", "44100", "-c:a", "aac", "-b:a", "128k"});
    const QByteArray incoming = song(dir.path(), "in.webm", {"-ar", "48000", "-c:a", "libopus", "-b:a", "96k"});
    QVERIFY(!outgoing.isEmpty() && !incoming.isEmpty());

    QJsonObject answer;
    bool answered = false;
    QJsonObject sentMeta;
    QByteArray sent;
    ConnectMixHost host(
        [&](const QString &id, bool ok, const QJsonObject &result, const QString &error) {
          QCOMPARE(id, QStringLiteral("rpc-1"));
          answer = result;
          answer.insert("ok", ok);
          answer.insert("failure", error);
          answered = true;
        },
        [&](const QString &sessionId, const QJsonObject &meta, const QByteArray &payload) {
          if (sessionId != QStringLiteral("s1"))
            return QString();
          sentMeta = meta;
          sent = payload;
          return QStringLiteral("stream-1");
        });
    const auto source = [](const QString &role) {
      return QJsonObject{{"kind", "audio"}, {"codec", "source"}, {"meta", QJsonObject{{"mix", "m1"}, {"role", role}}}};
    };
    // Sources and the request may land in either order; the job waits for all three.
    host.acceptSource("s1", source("outgoing"), outgoing);
    host.prepare("rpc-1", "s1",
                 QJsonObject{{"mix", "m1"},
                             {"codecs", QJsonArray{"pcm_s16", "pcm_f32"}},
                             {"request", QJsonObject{{"outgoingDuration", 70.0},
                                                     {"incomingDuration", 70.0},
                                                     {"position", 20.0},
                                                     {"fadeSeconds", 6},
                                                     {"kind", "bestMixAnalyze"},
                                                     {"currentTrack", QJsonObject{{"id", "a"}, {"title", "A"}}},
                                                     {"nextTrack", QJsonObject{{"id", "b"}, {"title", "B"}}}}}});
    QVERIFY(!answered);
    host.acceptSource("s1", source("incoming"), incoming);
    QTRY_VERIFY_WITH_TIMEOUT(answered, 180000);
    QVERIFY2(answer.value("ok").toBool(), qPrintable(answer.value("failure").toString()));
    if (answer.contains("error")) {
      // A refusal is a valid answer for synthetic audio; the plumbing still made the round trip.
      QVERIFY(sent.isEmpty());
      return;
    }
    QCOMPARE(answer.value("stream").toString(), QStringLiteral("stream-1"));
    QCOMPARE(answer.value("codec").toString(), QStringLiteral("pcm_s16"));
    // Rendered at the phone player's rate, starting at its own media time.
    QCOMPARE(answer.value("rate").toInt(), 44100);
    QCOMPARE(answer.value("incomingRate").toInt(), 48000);
    QCOMPARE(sentMeta.value("sample_rate").toInt(), 44100);
    QCOMPARE(sentMeta.value("timestamp").toDouble(), answer.value("outgoingStart").toDouble());
    QCOMPARE(sent.size(), qRound64(answer.value("duration").toDouble() * 44100) * 4);
  }

  void unknownMixesAreRefused() {
    QString failure;
    ConnectMixHost host([&](const QString &, bool ok, const QJsonObject &, const QString &error) {
      if (!ok) failure = error;
    }, [](const QString &, const QJsonObject &, const QByteArray &) { return QString(); });
    host.prepare("rpc-1", "s1", QJsonObject{});
    QCOMPARE(failure, QStringLiteral("invalid_request"));
    host.prepare("rpc-2", "s1", QJsonObject{{"mix", "m1"}});
    failure.clear();
    // Another session cannot answer for a job it did not start.
    host.prepare("rpc-3", "s2", QJsonObject{{"mix", "m1"}});
    QCOMPARE(failure, QStringLiteral("invalid_request"));
  }
};

QTEST_GUILESS_MAIN(ConnectMixTest)
#include "connect_mix_test.moc"
