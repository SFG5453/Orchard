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

#include "playback/playback_controller.h"
#include "playback/gapless_playback.h"
#include <QCoreApplication>
#include <QSettings>
#include <QTemporaryDir>
#include <QtTest>

class PlaybackPersistenceTest : public QObject {
    Q_OBJECT

private:
    QVariantMap sampleTrack(const QString &id, const QString &title) {
        return QVariantMap{
            {QStringLiteral("id"), id},
            {QStringLiteral("title"), title},
            {QStringLiteral("artist"), QStringLiteral("Test Artist")},
            {QStringLiteral("album"), QStringLiteral("Test Album")},
            {QStringLiteral("type"), QStringLiteral("song")},
            // Transient fields that should be evicted before writing to disk
            {QStringLiteral("bitrate"), 256},
            {QStringLiteral("streamUrl"), QStringLiteral("https://googlevideo.com/videoplayback?expire=12345")},
            {QStringLiteral("audioStreamUrl"), QStringLiteral("https://googlevideo.com/videoplayback?expire=12345")},
            {QStringLiteral("playbackFallbackTried"), true},
            {QStringLiteral("streamRefreshTried"), 1},
            {QStringLiteral("failedAudioItags"), QVariantList{140, 251}},
            {QStringLiteral("itag"), 140}
        };
    }

private slots:
    void gaplessRetainsPausedAndLoadingPreloads() {
        // Qt's paused preload can stay in BufferingMedia at the handoff.
        for (const auto status : {QMediaPlayer::LoadingMedia,
                                  QMediaPlayer::LoadedMedia,
                                  QMediaPlayer::StalledMedia,
                                  QMediaPlayer::BufferingMedia,
                                  QMediaPlayer::BufferedMedia}) {
            QVERIFY(canReuseGaplessMedia(status, QMediaPlayer::NoError));
            QVERIFY(!canReuseGaplessMedia(status, QMediaPlayer::NetworkError));
        }
        for (const auto status : {QMediaPlayer::NoMedia,
                                  QMediaPlayer::InvalidMedia,
                                  QMediaPlayer::EndOfMedia})
            QVERIFY(!canReuseGaplessMedia(status, QMediaPlayer::NoError));
    }

    void initTestCase() {
        QCoreApplication::setOrganizationName(QStringLiteral("OrchardTests"));
        QCoreApplication::setApplicationName(QStringLiteral("PlaybackPersistenceTest"));
    }

    void cleanup() {
        QSettings settings;
        settings.clear();
    }

    void sanitizeTrackRemovesEphemeralStreamProperties() {
        const QVariantMap original = sampleTrack(QStringLiteral("song_1"), QStringLiteral("Bohemian Rhapsody"));
        const QVariantMap sanitized = PlaybackController::sanitizeTrack(original);

        // Essential metadata must survive
        QCOMPARE(sanitized.value(QStringLiteral("id")).toString(), QStringLiteral("song_1"));
        QCOMPARE(sanitized.value(QStringLiteral("title")).toString(), QStringLiteral("Bohemian Rhapsody"));
        QCOMPARE(sanitized.value(QStringLiteral("artist")).toString(), QStringLiteral("Test Artist"));

        // Transient fields must be purged so we don't hold on to expired tickets
        QVERIFY(!sanitized.contains(QStringLiteral("bitrate")));
        QVERIFY(!sanitized.contains(QStringLiteral("streamUrl")));
        QVERIFY(!sanitized.contains(QStringLiteral("audioStreamUrl")));
        QVERIFY(!sanitized.contains(QStringLiteral("playbackFallbackTried")));
        QVERIFY(!sanitized.contains(QStringLiteral("streamRefreshTried")));
        QVERIFY(!sanitized.contains(QStringLiteral("failedAudioItags")));
        QVERIFY(!sanitized.contains(QStringLiteral("itag")));
    }

    void sanitizeTrackListFiltersAndEnforcesCap() {
        QVariantList list;
        // Add valid items plus invalid empty ones
        list.append(sampleTrack(QStringLiteral("id1"), QStringLiteral("Track 1")));
        list.append(QVariantMap{}); // Empty entry
        list.append(sampleTrack(QStringLiteral(""), QStringLiteral("No ID Track")));
        list.append(sampleTrack(QStringLiteral("id2"), QStringLiteral("Track 2")));
        list.append(sampleTrack(QStringLiteral("id3"), QStringLiteral("Track 3")));

        // Test with maxItems cap = 2
        const QVariantList capped = PlaybackController::sanitizeTrackList(list, 2);
        QCOMPARE(capped.size(), 2);
        QCOMPARE(capped.at(0).toMap().value(QStringLiteral("id")).toString(), QStringLiteral("id1"));
        QCOMPARE(capped.at(1).toMap().value(QStringLiteral("id")).toString(), QStringLiteral("id2"));
    }

    void persistsAndRestoresQueueHistoryAndShuffleSource() {
        QSettings settings;
        settings.clear();

        QVariantList queueTracks;
        queueTracks.append(sampleTrack(QStringLiteral("q1"), QStringLiteral("Queue One")));
        queueTracks.append(sampleTrack(QStringLiteral("q2"), QStringLiteral("Queue Two")));

        QVariantList historyTracks;
        historyTracks.append(sampleTrack(QStringLiteral("h1"), QStringLiteral("History One")));

        QVariantList shuffleSourceTracks;
        shuffleSourceTracks.append(sampleTrack(QStringLiteral("q2"), QStringLiteral("Queue Two")));
        shuffleSourceTracks.append(sampleTrack(QStringLiteral("q1"), QStringLiteral("Queue One")));

        // Write to settings as PlaybackController does
        settings.setValue(QStringLiteral("playback/persistenceEnabled"), true);
        settings.setValue(QStringLiteral("playback/persistedQueue"), PlaybackController::sanitizeTrackList(queueTracks));
        settings.setValue(QStringLiteral("playback/persistedHistory"), PlaybackController::sanitizeTrackList(historyTracks, 50));
        settings.setValue(QStringLiteral("playback/persistedShuffleSource"), PlaybackController::sanitizeTrackList(shuffleSourceTracks));
        settings.setValue(QStringLiteral("playback/repeatMode"), QStringLiteral("all"));
        settings.setValue(QStringLiteral("playback/shuffleEnabled"), true);

        // Read back
        const QVariantList restoredQueue = settings.value(QStringLiteral("playback/persistedQueue")).toList();
        const QVariantList restoredHistory = settings.value(QStringLiteral("playback/persistedHistory")).toList();
        const QVariantList restoredShuffleSource = settings.value(QStringLiteral("playback/persistedShuffleSource")).toList();
        const QString restoredRepeatMode = settings.value(QStringLiteral("playback/repeatMode")).toString();
        const bool restoredShuffle = settings.value(QStringLiteral("playback/shuffleEnabled")).toBool();

        QCOMPARE(restoredQueue.size(), 2);
        QCOMPARE(restoredQueue.at(0).toMap().value(QStringLiteral("id")).toString(), QStringLiteral("q1"));
        QCOMPARE(restoredHistory.size(), 1);
        QCOMPARE(restoredHistory.at(0).toMap().value(QStringLiteral("id")).toString(), QStringLiteral("h1"));
        QCOMPARE(restoredShuffleSource.size(), 2);
        QCOMPARE(restoredShuffleSource.at(0).toMap().value(QStringLiteral("id")).toString(), QStringLiteral("q2"));
        QCOMPARE(restoredRepeatMode, QStringLiteral("all"));
        QVERIFY(restoredShuffle);
    }

    void disablingPersistenceClearsPersistedKeys() {
        QSettings settings;
        settings.setValue(QStringLiteral("playback/persistedTrack"), sampleTrack(QStringLiteral("t1"), QStringLiteral("T1")));
        settings.setValue(QStringLiteral("playback/persistedPosition"), 42.5);
        settings.setValue(QStringLiteral("playback/persistedQueue"), QVariantList{sampleTrack(QStringLiteral("q1"), QStringLiteral("Q1"))});
        settings.setValue(QStringLiteral("playback/persistedHistory"), QVariantList{sampleTrack(QStringLiteral("h1"), QStringLiteral("H1"))});
        settings.setValue(QStringLiteral("playback/persistedShuffleSource"), QVariantList{sampleTrack(QStringLiteral("q1"), QStringLiteral("Q1"))});

        // Simulate setPlaybackPersistenceEnabled(false) cleanup
        settings.remove(QStringLiteral("playback/persistedTrack"));
        settings.remove(QStringLiteral("playback/persistedPosition"));
        settings.remove(QStringLiteral("playback/persistedQueue"));
        settings.remove(QStringLiteral("playback/persistedHistory"));
        settings.remove(QStringLiteral("playback/persistedShuffleSource"));

        QVERIFY(!settings.contains(QStringLiteral("playback/persistedTrack")));
        QVERIFY(!settings.contains(QStringLiteral("playback/persistedPosition")));
        QVERIFY(!settings.contains(QStringLiteral("playback/persistedQueue")));
        QVERIFY(!settings.contains(QStringLiteral("playback/persistedHistory")));
        QVERIFY(!settings.contains(QStringLiteral("playback/persistedShuffleSource")));
    }

    void fallbacksToLastSongWhenPersistedTrackIsEmpty() {
        QSettings settings;
        settings.clear();
        settings.setValue(QStringLiteral("playback/persistedTrack"), QVariantMap{});
        settings.setValue(QStringLiteral("playback/lastSong"), sampleTrack(QStringLiteral("fallback_id"), QStringLiteral("Fallback Title")));

        QVariantMap saved = settings.value(QStringLiteral("playback/persistedTrack")).toMap();
        if (saved.value(QStringLiteral("id")).toString().isEmpty()) {
            saved = settings.value(QStringLiteral("playback/lastSong")).toMap();
        }

        QCOMPARE(saved.value(QStringLiteral("id")).toString(), QStringLiteral("fallback_id"));
        QCOMPARE(saved.value(QStringLiteral("title")).toString(), QStringLiteral("Fallback Title"));
    }
};

QTEST_MAIN(PlaybackPersistenceTest)
#include "playback_persistence_test.moc"
