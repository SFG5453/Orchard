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

#include "playback/slop_detector.h"
#include "playback/slop_scan_worker.h"

#include <QAudioBuffer>
#include <QDir>
#include <QFile>
#include <QSettings>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtEndian>
#include <QtTest>

#include <random>

// White noise: the least AI thing a computer can make, ironically.
class SlopDetectorTest : public QObject {
  Q_OBJECT

  static QAudioBuffer noise(std::mt19937 &rng, int frames) {
    QAudioFormat format;
    format.setSampleRate(48000);
    format.setChannelCount(2);
    format.setSampleFormat(QAudioFormat::Float);
    QByteArray bytes(frames * 2 * int(sizeof(float)), Qt::Uninitialized);
    std::uniform_real_distribution<float> sample(-0.3f, 0.3f);
    auto *data = reinterpret_cast<float *>(bytes.data());
    for (int i = 0; i < frames * 2; ++i)
      data[i] = sample(rng);
    return QAudioBuffer(bytes, format);
  }

  // 16-bit stereo PCM WAV, the one container every QAudioDecoder backend reads.
  static bool writeNoiseWav(const QString &path, int seconds) {
    constexpr int rate = 44100;
    const quint32 dataBytes = quint32(seconds) * rate * 4;
    QByteArray header;
    auto u32 = [&header](quint32 v) { header.append(reinterpret_cast<const char *>(&v), 4); };
    auto u16 = [&header](quint16 v) { header.append(reinterpret_cast<const char *>(&v), 2); };
    header.append("RIFF");
    u32(36 + dataBytes);
    header.append("WAVEfmt ");
    u32(16);
    u16(1);
    u16(2);
    u32(rate);
    u32(rate * 4);
    u16(4);
    u16(16);
    header.append("data");
    u32(dataBytes);
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly) || file.write(header) != header.size())
      return false;
    std::mt19937 rng(11);
    std::uniform_int_distribution<int> sample(-9000, 9000);
    QByteArray pcm(dataBytes, Qt::Uninitialized);
    auto *data = reinterpret_cast<qint16 *>(pcm.data());
    for (quint32 i = 0; i < dataBytes / 2; ++i)
      data[i] = qToLittleEndian(qint16(sample(rng)));
    return file.write(pcm) == pcm.size();
  }

private slots:
  void initTestCase() {
    QStandardPaths::setTestModeEnabled(true);
    QDir(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)).removeRecursively();
    QSettings().remove(QStringLiteral("playback/slopAction"));
  }

  void unknownTrackHasNoVerdict() {
    SlopDetector detector;
    QCOMPARE(detector.probability(QStringLiteral("never-played")), -1.0);
    QVERIFY(!detector.isFlagged(QStringLiteral("never-played")));
  }

  void fullTrackVerdictPersists() {
    const QString id = QStringLiteral("noise-track");
    {
      SlopDetector detector;
      QSignalSpy flagged(&detector, &SlopDetector::flagged);
      std::mt19937 rng(7);
      // 300 s fills the detector, which forces a stored verdict.
      for (int second = 0; second < 301 && detector.probability(id) < 0.0; ++second)
        detector.feed(id, noise(rng, 48000));
      QVERIFY(detector.probability(id) >= 0.0);
      QVERIFY(detector.probability(id) < 0.1);
      QVERIFY(!detector.isFlagged(id));
      QCOMPARE(flagged.count(), 0);
    }
    const QString file = QDir(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation))
                             .filePath(QStringLiteral("slop-verdicts.sqlite"));
    QVERIFY(QFile::exists(file));
    SlopDetector reopened;
    QVERIFY(reopened.probability(id) >= 0.0);
  }

  void workerScoresAndDeletesDownloadedFile() {
    QTemporaryDir dir;
    const QString path = dir.filePath(QStringLiteral("noise.wav"));
    QVERIFY(writeNoiseWav(path, 20));
    SlopScanWorker worker;
    QSignalSpy analyzed(&worker, &SlopScanWorker::analyzed);
    worker.analyze(QStringLiteral("noise"), path);
    QTRY_COMPARE_WITH_TIMEOUT(analyzed.count(), 1, 30000);
    const auto result = analyzed.takeFirst();
    QCOMPARE(result.at(0).toString(), QStringLiteral("noise"));
    QVERIFY(result.at(1).toBool());
    QVERIFY(result.at(2).toFloat() < 0.1f);
    QVERIFY(result.at(3).toFloat() > 19.0f);
    QVERIFY(!QFile::exists(path));
  }

  void workerRejectsUndecodableFile() {
    QTemporaryDir dir;
    const QString path = dir.filePath(QStringLiteral("junk.webm"));
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    file.write(QByteArray(4096, 'x'));
    file.close();
    SlopScanWorker worker;
    QSignalSpy analyzed(&worker, &SlopScanWorker::analyzed);
    // Open failures are reported synchronously inside analyze().
    worker.analyze(QStringLiteral("junk"), path);
    QTRY_COMPARE_WITH_TIMEOUT(analyzed.count(), 1, 30000);
    QVERIFY(!analyzed.takeFirst().at(1).toBool());
  }

  // Scores real recordings: ORCHARD_SLOP_SAMPLES=a.webm:b.m4a. Copies, since the worker deletes.
  void scoresSampleFiles() {
    const QStringList samples =
        qEnvironmentVariable("ORCHARD_SLOP_SAMPLES").split(QLatin1Char(':'), Qt::SkipEmptyParts);
    if (samples.isEmpty())
      QSKIP("ORCHARD_SLOP_SAMPLES is not set");
    QTemporaryDir dir;
    SlopScanWorker worker;
    QSignalSpy analyzed(&worker, &SlopScanWorker::analyzed);
    for (const QString &sample : samples) {
      const QString copy = dir.filePath(QFileInfo(sample).fileName());
      QVERIFY(QFile::copy(sample, copy));
      worker.analyze(sample, copy);
      QTRY_COMPARE_WITH_TIMEOUT(analyzed.count(), 1, 60000);
      const auto result = analyzed.takeFirst();
      qInfo("%6.2f%% %5.0fs %s", result.at(2).toFloat() * 100.0, result.at(3).toFloat(),
            qPrintable(QFileInfo(sample).fileName()));
      QVERIFY(result.at(1).toBool());
    }
  }

  void offHidesMarksAndPersists() {
    {
      SlopDetector detector;
      QSignalSpy changed(&detector, &SlopDetector::flagsChanged);
      detector.setAction(QStringLiteral("off"));
      QCOMPARE(changed.count(), 1);
      detector.setAction(QStringLiteral("bogus"));
      QCOMPARE(detector.action(), QStringLiteral("off"));
    }
    SlopDetector reopened;
    QCOMPARE(reopened.action(), QStringLiteral("off"));
    QVERIFY(!reopened.enabled());
    reopened.setAction(QStringLiteral("mark"));
  }
};

QTEST_GUILESS_MAIN(SlopDetectorTest)
#include "slop_detector_test.moc"
