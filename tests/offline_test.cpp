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

#include "offline/connectivity_monitor.h"
#include "offline/download_manager.h"
#include "offline/offline_library.h"
#include "offline/offline_store.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QImage>
#include <QSettings>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTimer>
#include <QtTest>

namespace {

// Scripted network: each probe consumes the next answer, then repeats the last.
struct FakeNetwork {
  QList<bool> answers;
  int calls{0};
  bool next() {
    const int index = qMin(calls++, static_cast<int>(answers.size()) - 1);
    return answers.at(index);
  }
  ConnectivityMonitor::Probe probe() {
    return [this](std::function<void(bool)> done) {
      const bool ok = next();
      QTimer::singleShot(0, [done = std::move(done), ok] { done(ok); });
    };
  }
};

ConnectivityMonitor::Timing quickTiming() {
  ConnectivityMonitor::Timing timing;
  timing.graceMs = 120;
  timing.pollMs = 100000;
  timing.tickMs = 10;
  timing.reportDebounceMs = 0;
  timing.systemHints = false;
  return timing;
}

QString writeFile(const QString &path, const QByteArray &bytes) {
  QFile file(path);
  QDir().mkpath(QFileInfo(path).absolutePath());
  if (file.open(QIODevice::WriteOnly))
    file.write(bytes);
  return path;
}

QString writePng(const QString &path) {
  QDir().mkpath(QFileInfo(path).absolutePath());
  QImage image(8, 8, QImage::Format_RGB32);
  image.fill(Qt::red);
  image.save(path, "PNG");
  return path;
}

offline::TrackRecord record(const QString &id, const QString &dir, const QString &title = {}) {
  offline::TrackRecord track;
  track.id = id;
  track.meta = QJsonObject{{"id", id},
                           {"type", "song"},
                           {"title", title.isEmpty() ? id : title},
                           {"artist", "Artist"},
                           {"album", "Album"},
                           {"thumbnail", "https://example.invalid/cover=w226-h226"},
                           {"durationSeconds", 187}};
  track.path = writeFile(dir + "/audio/" + id + ".webm", QByteArray(2048, 'a'));
  track.mimeType = "audio/webm";
  track.quality = "high";
  track.bitrate = 128000;
  track.bytes = 2048;
  track.downloadedAt = 1000;
  return track;
}

} // namespace

class OfflineTest : public QObject {
  Q_OBJECT

private slots:
  void initTestCase() {
    QStandardPaths::setTestModeEnabled(true);
    QSettings().remove(QStringLiteral("downloads"));
  }

  void staysOnlineWhileProbesSucceed() {
    FakeNetwork network{{true}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    monitor.start();
    QTRY_COMPARE(network.calls, 1);
    QTest::qWait(60);
    QVERIFY(!monitor.offline());
    QVERIFY(!monitor.retrying());
    QCOMPARE(monitor.stateName(), QStringLiteral("online"));
  }

  void failedProbeGivesOneGraceRetryThenParksOffline() {
    FakeNetwork network{{false}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    QSignalSpy lost(&monitor, &ConnectivityMonitor::lost);
    monitor.start();
    QTRY_VERIFY(monitor.retrying());
    QVERIFY(monitor.retryIn() >= 1);
    QTRY_VERIFY(monitor.offline());
    QCOMPARE(lost.count(), 1);
    QCOMPARE(network.calls, 2);
    QCOMPARE(monitor.retryIn(), 0);
    // Parked offline: nothing probes again until the user asks.
    QTest::qWait(300);
    QCOMPARE(network.calls, 2);
    monitor.reportFailure();
    QTest::qWait(50);
    QCOMPARE(network.calls, 2);
  }

  void graceRetrySuccessKeepsTheAppOnline() {
    FakeNetwork network{{false, true}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    QSignalSpy lost(&monitor, &ConnectivityMonitor::lost);
    QSignalSpy restored(&monitor, &ConnectivityMonitor::restored);
    monitor.start();
    QTRY_VERIFY(monitor.retrying());
    QTRY_COMPARE(network.calls, 2);
    QTRY_VERIFY(!monitor.retrying());
    QVERIFY(!monitor.offline());
    QCOMPARE(lost.count(), 0);
    QCOMPARE(restored.count(), 0);
  }

  void manualRetryLeavesOfflineOnlyWhenTheNetworkAnswers() {
    FakeNetwork network{{false, false, false, true}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    QSignalSpy restored(&monitor, &ConnectivityMonitor::restored);
    QSignalSpy retryFailed(&monitor, &ConnectivityMonitor::retryFailed);
    monitor.start();
    QTRY_VERIFY(monitor.offline());
    monitor.retry();
    QTRY_COMPARE(retryFailed.count(), 1);
    QVERIFY(monitor.offline());
    monitor.retry();
    QTRY_COMPARE(restored.count(), 1);
    QVERIFY(!monitor.offline());
    QVERIFY(!monitor.checking());
  }

  void retryDuringGraceSkipsTheWait() {
    FakeNetwork network{{false, true}};
    auto timing = quickTiming();
    timing.graceMs = 60000;
    ConnectivityMonitor monitor(network.probe(), timing);
    monitor.start();
    QTRY_VERIFY(monitor.retrying());
    QVERIFY(monitor.retryIn() > 50);
    monitor.retry();
    QTRY_VERIFY(!monitor.retrying());
    QVERIFY(!monitor.offline());
  }

  void failureReportsTriggerAProbeWhileOnline() {
    FakeNetwork network{{true, false}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    monitor.start();
    QTRY_COMPARE(network.calls, 1);
    QTRY_VERIFY(!monitor.checking());
    monitor.reportFailure();
    QTRY_VERIFY(monitor.retrying());
  }

  void storeRoundTripsTracksAndCollections() {
    QTemporaryDir dir;
    offline::OfflineStore store(dir.path());
    store.put(record(QStringLiteral("aaa"), dir.path()));
    store.put(record(QStringLiteral("bbb"), dir.path()));
    offline::CollectionRecord collection;
    collection.id = offline::kCollectionPrefix + QStringLiteral("PL1");
    collection.kind = QStringLiteral("album");
    collection.title = QStringLiteral("Mix");
    collection.trackIds = {QStringLiteral("bbb"), QStringLiteral("aaa")};
    store.collections.append(collection);
    QVERIFY(store.save());

    offline::OfflineStore loaded(dir.path());
    QVERIFY(loaded.load());
    QCOMPARE(loaded.order, (QStringList{QStringLiteral("bbb"), QStringLiteral("aaa")}));
    QCOMPARE(loaded.tracks.value(QStringLiteral("aaa")).bitrate, 128000);
    QCOMPARE(loaded.tracks.value(QStringLiteral("aaa")).meta.value("title").toString(), QStringLiteral("aaa"));
    QCOMPARE(loaded.collections.size(), 1);
    QCOMPARE(loaded.collections.first().trackIds, collection.trackIds);
    QCOMPARE(loaded.collections.first().kind, QStringLiteral("album"));
  }

  void pruneDropsSongsWhoseFileVanished() {
    QTemporaryDir dir;
    offline::OfflineStore store(dir.path());
    store.put(record(QStringLiteral("keep"), dir.path()));
    store.put(record(QStringLiteral("gone"), dir.path()));
    offline::CollectionRecord collection;
    collection.id = offline::kCollectionPrefix + QStringLiteral("PL2");
    collection.trackIds = {QStringLiteral("gone")};
    store.collections.append(collection);
    QFile::remove(store.tracks.value(QStringLiteral("gone")).path);
    QCOMPARE(store.prune(), 1);
    QVERIFY(store.tracks.contains(QStringLiteral("keep")));
    QVERIFY(!store.tracks.contains(QStringLiteral("gone")));
    QVERIFY(store.collections.isEmpty());
  }

  void sweepRemovesFilesNoRecordPointsAt() {
    QTemporaryDir dir;
    offline::OfflineStore store(dir.path());
    store.put(record(QStringLiteral("kept"), dir.path()));
    const QString orphan = writeFile(dir.path() + "/audio/kept.webmAbC123", QByteArray(10, 'x'));
    const QString strayArt = writePng(dir.path() + "/art/other.jpg");
    QCOMPARE(store.sweep(), 2);
    QVERIFY(!QFileInfo::exists(orphan));
    QVERIFY(!QFileInfo::exists(strayArt));
    QVERIFY(QFileInfo::exists(store.tracks.value(QStringLiteral("kept")).path));
  }

  void stemKeepsVideoIdsAndNeutralisesPaths() {
    QCOMPARE(offline::OfflineStore::stem(QStringLiteral("dQw4w9WgXcQ")), QStringLiteral("dQw4w9WgXcQ"));
    QCOMPARE(offline::OfflineStore::stem(QStringLiteral("../a/b:c")), QStringLiteral("___a_b_c"));
  }

  void sharedLoopsCountOnceInTheDiskTotal() {
    QTemporaryDir dir;
    offline::OfflineStore store(dir.path());
    const QString loop = writeFile(dir.path() + "/animated/loop.mp4", QByteArray(1000, 'v'));
    auto first = record(QStringLiteral("a1"), dir.path());
    auto second = record(QStringLiteral("a2"), dir.path());
    first.animatedPath = second.animatedPath = loop;
    store.put(first);
    store.put(second);
    QCOMPARE(store.totalBytes(), qint64(2048 + 2048 + 1000));
    QVERIFY(store.animatedInUse(loop));
    store.take(QStringLiteral("a1"));
    QVERIFY(store.animatedInUse(loop));
    store.take(QStringLiteral("a2"));
    QVERIFY(!store.animatedInUse(loop));
  }

  void rowsUseTheSavedCoverAndCarryTheDownloadedFlag() {
    QTemporaryDir dir;
    auto track = record(QStringLiteral("cov"), dir.path());
    QVariantMap row = offline::trackToVariant(track);
    QCOMPARE(row.value("thumbnail").toString(), QStringLiteral("https://example.invalid/cover=w226-h226"));
    QVERIFY(row.value("downloaded").toBool());
    QCOMPARE(row.value("duration").toString(), QStringLiteral("3:07"));
    track.thumbnailPath = writePng(dir.path() + "/art/cov.png");
    row = offline::trackToVariant(track);
    QVERIFY(row.value("thumbnail").toString().startsWith(QStringLiteral("file://")));
  }

  void collectionDetailListsOnlyDownloadedSongsInOrder() {
    QTemporaryDir dir;
    offline::OfflineStore store(dir.path());
    store.put(record(QStringLiteral("t1"), dir.path()));
    store.put(record(QStringLiteral("t3"), dir.path()));
    offline::CollectionRecord collection;
    collection.id = offline::kCollectionPrefix + QStringLiteral("PL3");
    collection.title = QStringLiteral("Road trip");
    collection.trackIds = {QStringLiteral("t3"), QStringLiteral("t2"), QStringLiteral("t1")};
    store.collections.append(collection);
    const QVariantMap detail = offline::collectionDetail(store.collections.first(), store);
    const QVariantList rows = detail.value("tracks").toList();
    QCOMPARE(rows.size(), 2);
    QCOMPARE(rows.at(0).toMap().value("id").toString(), QStringLiteral("t3"));
    QCOMPARE(rows.at(1).toMap().value("id").toString(), QStringLiteral("t1"));
    QCOMPARE(detail.value("source").toString(), QStringLiteral("download"));
    QCOMPARE(detail.value("totalTrackCount").toInt(), 2);
    QVERIFY(!detail.value("hasMoreTracks").toBool());
  }

  void managerReadsAnExistingStoreAndForgetsMissingFiles() {
    QTemporaryDir dir;
    {
      offline::OfflineStore store(dir.path());
      store.put(record(QStringLiteral("here"), dir.path()));
      store.put(record(QStringLiteral("lost"), dir.path()));
      QVERIFY(store.save());
      QFile::remove(store.tracks.value(QStringLiteral("lost")).path);
    }
    DownloadManager manager(nullptr, nullptr, dir.path());
    QVERIFY(manager.isDownloaded(QStringLiteral("here")));
    QVERIFY(!manager.isDownloaded(QStringLiteral("lost")));
    QCOMPARE(manager.count(), 1);
    QCOMPARE(manager.stateOf(QStringLiteral("here")), QStringLiteral("downloaded"));
    QCOMPARE(manager.stateOf(QStringLiteral("nope")), QStringLiteral("none"));
    QVERIFY(manager.audioPathFor(QStringLiteral("here")).endsWith(QStringLiteral("here.webm")));
    QVERIFY(manager.audioPathFor(QStringLiteral("lost")).isEmpty());
    QCOMPARE(manager.bitrateFor(QStringLiteral("here")), 128000);
  }

  void qualityAcceptsOnlyKnownTiersAndPersists() {
    QTemporaryDir dir;
    DownloadManager manager(nullptr, nullptr, dir.path());
    QCOMPARE(manager.quality(), QStringLiteral("high"));
    QSignalSpy changed(&manager, &DownloadManager::settingsChanged);
    manager.setQuality(QStringLiteral("max"));
    QCOMPARE(manager.quality(), QStringLiteral("high"));
    QCOMPARE(changed.count(), 0);
    manager.setQuality(QStringLiteral("saver"));
    QCOMPARE(manager.quality(), QStringLiteral("saver"));
    DownloadManager reopened(nullptr, nullptr, dir.path());
    QCOMPARE(reopened.quality(), QStringLiteral("saver"));
    reopened.setQuality(QStringLiteral("high"));
    QVERIFY(!reopened.animatedArtwork());
    reopened.setAnimatedArtwork(true);
    DownloadManager again(nullptr, nullptr, dir.path());
    QVERIFY(again.animatedArtwork());
    again.setAnimatedArtwork(false);
  }

  void removeDeletesFilesAndEmptiedCollections() {
    QTemporaryDir dir;
    QString audio;
    {
      offline::OfflineStore store(dir.path());
      auto track = record(QStringLiteral("one"), dir.path());
      track.thumbnailPath = writePng(dir.path() + "/art/one.jpg");
      audio = track.path;
      store.put(track);
      store.put(record(QStringLiteral("two"), dir.path()));
      offline::CollectionRecord collection;
      collection.id = offline::kCollectionPrefix + QStringLiteral("PL4");
      collection.trackIds = {QStringLiteral("one")};
      store.collections.append(collection);
      QVERIFY(store.save());
    }
    DownloadManager manager(nullptr, nullptr, dir.path());
    QSignalSpy changed(&manager, &DownloadManager::changed);
    manager.remove(QStringLiteral("one"));
    QVERIFY(!QFileInfo::exists(audio));
    QVERIFY(!QFileInfo::exists(dir.path() + "/art/one.jpg"));
    QVERIFY(!manager.isDownloaded(QStringLiteral("one")));
    QVERIFY(manager.isDownloaded(QStringLiteral("two")));
    QVERIFY(manager.store().collections.isEmpty());
    QVERIFY(changed.count() >= 1);
  }

  void clearAllEmptiesTheStoreAndTheDisk() {
    QTemporaryDir dir;
    {
      offline::OfflineStore store(dir.path());
      store.put(record(QStringLiteral("x"), dir.path()));
      store.put(record(QStringLiteral("y"), dir.path()));
      QVERIFY(store.save());
    }
    DownloadManager manager(nullptr, nullptr, dir.path());
    QSignalSpy notice(&manager, &DownloadManager::noticeRequested);
    QCOMPARE(manager.count(), 2);
    QVERIFY(manager.bytesUsed() > 0);
    manager.clearAll();
    QCOMPARE(manager.count(), 0);
    QCOMPARE(manager.bytesUsed(), 0.0);
    QVERIFY(!QDir(dir.path() + "/audio").exists());
    QCOMPARE(notice.count(), 1);
    DownloadManager reopened(nullptr, nullptr, dir.path());
    QCOMPARE(reopened.count(), 0);
  }

  void collectionStateCountsDownloadedSongs() {
    QTemporaryDir dir;
    {
      offline::OfflineStore store(dir.path());
      store.put(record(QStringLiteral("d1"), dir.path()));
      QVERIFY(store.save());
    }
    DownloadManager manager(nullptr, nullptr, dir.path());
    const auto row = [](const QString &id, const QString &type = QStringLiteral("song")) {
      return QVariantMap{{"id", id}, {"type", type}};
    };
    const QVariantMap state = manager.collectionState(
        {row("d1"), row("d2"), row("local:abc"), row("d3", "album")});
    QCOMPARE(state.value("total").toInt(), 2);
    QCOMPARE(state.value("downloaded").toInt(), 1);
    QCOMPARE(state.value("pending").toInt(), 0);
  }

  void eligibilityRejectsLocalAndUnplayableRows() {
    QVERIFY(DownloadManager::eligible({{"id", "abc"}, {"type", "song"}}));
    QVERIFY(DownloadManager::eligible({{"id", "abc"}, {"type", "video"}}));
    QVERIFY(!DownloadManager::eligible({{"id", "local:1"}, {"type", "song"}}));
    QVERIFY(!DownloadManager::eligible({{"id", "abc"}, {"type", "song"}, {"unplayable", true}}));
    QVERIFY(!DownloadManager::eligible({{"id", "abc"}, {"type", "album"}}));
    QVERIFY(!DownloadManager::eligible({{"type", "song"}}));
  }

  void downloadsAreRefusedWhileOffline() {
    QTemporaryDir dir;
    FakeNetwork network{{false}};
    ConnectivityMonitor monitor(network.probe(), quickTiming());
    DownloadManager manager(nullptr, nullptr, dir.path());
    manager.setConnectivity(&monitor);
    monitor.start();
    QTRY_VERIFY(monitor.offline());
    QSignalSpy notice(&manager, &DownloadManager::noticeRequested);
    manager.download({{"id", "abc"}, {"type", "song"}, {"title", "T"}});
    QCOMPARE(notice.count(), 1);
    QVERIFY(notice.first().first().toString().contains(QStringLiteral("offline")));
    QCOMPARE(manager.pending(), 0);
    QCOMPARE(manager.stateOf(QStringLiteral("abc")), QStringLiteral("none"));
  }

  void offlineLibraryMergesDownloadsAndSearchesThem() {
    QTemporaryDir dir;
    {
      offline::OfflineStore store(dir.path());
      store.put(record(QStringLiteral("s1"), dir.path(), QStringLiteral("Blue Monday")));
      store.put(record(QStringLiteral("s2"), dir.path(), QStringLiteral("Red Rain")));
      offline::CollectionRecord collection;
      collection.id = offline::kCollectionPrefix + QStringLiteral("PL5");
      collection.title = QStringLiteral("Blue Hour");
      collection.author = QStringLiteral("Someone");
      collection.trackIds = {QStringLiteral("s1")};
      store.collections.append(collection);
      QVERIFY(store.save());
    }
    DownloadManager manager(nullptr, nullptr, dir.path());
    OfflineLibrary library(&manager, nullptr, nullptr);
    QCOMPARE(library.songs().size(), 2);
    QCOMPARE(library.songs().first().toMap().value("id").toString(), QStringLiteral("s2"));
    QCOMPARE(library.playlists().size(), 1);
    QCOMPARE(library.homeSections().size(), 2);

    const QVariantList all = library.search(QStringLiteral("blue"));
    QCOMPARE(all.size(), 2);
    QCOMPARE(all.at(0).toMap().value("key").toString(), QStringLiteral("songs"));
    QCOMPARE(all.at(0).toMap().value("items").toList().size(), 1);
    QCOMPARE(all.at(1).toMap().value("key").toString(), QStringLiteral("playlists"));
    QCOMPARE(library.search(QStringLiteral("blue"), QStringLiteral("songs")).size(), 1);
    QCOMPARE(library.search(QStringLiteral("blue"), QStringLiteral("albums")).size(), 0);
    QCOMPARE(library.search(QStringLiteral("artist album")).size(), 1);
    QCOMPARE(library.search(QStringLiteral("zzz")).size(), 0);
    QCOMPARE(library.search(QStringLiteral("   ")).size(), 0);

    const QString id = offline::kCollectionPrefix + QStringLiteral("PL5");
    QCOMPARE(library.collectionDetail(id).value("tracks").toList().size(), 1);
    QVERIFY(library.collectionDetail(QStringLiteral("offline-playlist:none")).isEmpty());
    QCOMPARE(library.collectionIdFor({{"playlistId", "VLPL5"}}), id);
    QCOMPARE(library.collectionIdFor({{"browseId", "VLPL5"}}), id);
    QVERIFY(library.collectionIdFor({{"browseId", "VLother"}}).isEmpty());
  }
};

QTEST_GUILESS_MAIN(OfflineTest)
#include "offline_test.moc"
