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

#pragma once

#include "audio_engine_output.h"
#include "adaptive_mix/adaptive_mix_controller.h"
#include "best_mix_controller.h"
#include "slop_detector.h"
#include "audio_stream_proxy.h"
#include "playback_remote.h"
#include "local/local_track.h"
#include <QElapsedTimer>
#include <QHash>
#include <QJsonObject>
#include <QMediaPlayer>
#include <QObject>
#include <QSet>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

#include <algorithm>
#include <memory>

class YouTubeProvider;
class AuthManager;
class HomeController;
class SystemMediaBridge;
class Integrations;
class AnimatedArtworkService;
class ConnectivityMonitor;
class DownloadManager;
class QobuzService;

class PlaybackController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantMap track READ shownTrack NOTIFY stateChanged)
  Q_PROPERTY(QString queueError READ queueError NOTIFY queueChanged)
  Q_PROPERTY(QVariantList queue READ shownQueue NOTIFY queueChanged)
  Q_PROPERTY(QVariantList history READ shownHistory NOTIFY historyChanged)
  Q_PROPERTY(QString queueLayout READ queueLayout WRITE setQueueLayout NOTIFY queueLayoutChanged)
  Q_PROPERTY(BestMixController *bestMix READ bestMix CONSTANT)
  Q_PROPERTY(bool bestMixSorted READ bestMixSorted NOTIFY bestMixStateChanged)
  Q_PROPERTY(bool gaplessEnabled READ gaplessEnabled WRITE setGaplessEnabled
                 NOTIFY stateChanged)
  Q_PROPERTY(bool exponentialVolumeEnabled READ exponentialVolumeEnabled
                 WRITE setExponentialVolumeEnabled NOTIFY stateChanged)
  Q_PROPERTY(bool crossfadeEnabled READ crossfadeEnabled WRITE setCrossfadeEnabled
                 NOTIFY stateChanged)
  Q_PROPERTY(int crossfadeDuration READ crossfadeDuration WRITE setCrossfadeDuration
                 NOTIFY stateChanged)
  Q_PROPERTY(AdaptiveMixController *adaptiveMix READ adaptiveMix CONSTANT)
  Q_PROPERTY(QVariantMap transitionTrack READ transitionTrack NOTIFY stateChanged)
  Q_PROPERTY(double transitionPosition READ transitionPosition NOTIFY stateChanged)
  Q_PROPERTY(double transitionDuration READ transitionDuration NOTIFY stateChanged)
  Q_PROPERTY(bool crossfadeActive READ crossfadeActive NOTIFY stateChanged)
  Q_PROPERTY(double crossfadeProgress READ crossfadeProgress NOTIFY stateChanged)
  Q_PROPERTY(double displayPosition READ shownDisplayPosition NOTIFY stateChanged)
  Q_PROPERTY(double displayDuration READ shownDisplayDuration NOTIFY stateChanged)
  Q_PROPERTY(double mixStart READ mixStart NOTIFY stateChanged)
  Q_PROPERTY(double mixDuration READ mixDuration NOTIFY stateChanged)
  Q_PROPERTY(bool autoplayEnabled READ autoplayEnabled WRITE setAutoplayEnabled
                 NOTIFY stateChanged)
  Q_PROPERTY(bool playbackPersistenceEnabled READ playbackPersistenceEnabled
                 WRITE setPlaybackPersistenceEnabled NOTIFY stateChanged)
  Q_PROPERTY(bool youtubeHistoryEnabled READ youtubeHistoryEnabled
                 WRITE setYouTubeHistoryEnabled NOTIFY stateChanged)
  // "off", "button" (offer Skip) or "auto" for SponsorBlock non-music spans.
  Q_PROPERTY(QString nonMusicSkipMode READ nonMusicSkipMode WRITE setNonMusicSkipMode
                 NOTIFY stateChanged)
  // The non-music span under the playhead, or empty. Drives the Skip button.
  Q_PROPERTY(QVariantMap nonMusicSegment READ nonMusicSegment NOTIFY stateChanged)
  Q_PROPERTY(QVariantList skipSegments READ skipSegments NOTIFY stateChanged)
  Q_PROPERTY(bool autoplayLoading READ autoplayLoading NOTIFY stateChanged)
  Q_PROPERTY(QString autoplayError READ autoplayError NOTIFY stateChanged)
  Q_PROPERTY(QString streamQuality READ streamQuality WRITE setStreamQuality
                 NOTIFY streamQualityChanged)
  Q_PROPERTY(QString audioQuality READ streamQuality WRITE setStreamQuality
                 NOTIFY streamQualityChanged)
  // Signed in to Qobuz here, or on a connected Connect peer whose session this desktop borrows.
  Q_PROPERTY(bool qobuzReachable READ qobuzReachable NOTIFY streamQualityChanged)
  Q_PROPERTY(bool shuffleEnabled READ shownShuffle WRITE setShuffleEnabled
                 NOTIFY stateChanged)
  Q_PROPERTY(QString repeatMode READ shownRepeatMode WRITE setRepeatMode NOTIFY
                 stateChanged)
  Q_PROPERTY(bool playing READ shownPlaying NOTIFY stateChanged)
  Q_PROPERTY(bool loading READ shownLoading NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  Q_PROPERTY(double position READ shownPosition NOTIFY stateChanged)
  Q_PROPERTY(double audiblePosition READ shownAudiblePosition NOTIFY stateChanged)
  Q_PROPERTY(double duration READ shownDuration NOTIFY stateChanged)
  Q_PROPERTY(int bitrate READ bitrate NOTIFY stateChanged)
  Q_PROPERTY(bool canGoNext READ shownCanGoNext NOTIFY stateChanged)
  Q_PROPERTY(bool canGoPrevious READ shownCanGoPrevious NOTIFY stateChanged)
  // True while this desktop drives another device through Orchard Connect.
  Q_PROPERTY(bool remoteActive READ remoteActive NOTIFY stateChanged)
  Q_PROPERTY(QString animatedArtworkUrl READ animatedArtworkUrl NOTIFY
                 animatedArtworkUrlChanged)
  Q_PROPERTY(AudioEngineOutput *audioEngine READ audioEngine CONSTANT)
  Q_PROPERTY(SlopDetector *slop READ slop CONSTANT)
public:
  PlaybackController(YouTubeProvider *provider, AuthManager *auth,
                     HomeController *home,
                     SystemMediaBridge *systemMedia = nullptr,
                     Integrations *integrations = nullptr,
                     AnimatedArtworkService *animatedArtwork = nullptr,
                     QobuzService *qobuz = nullptr,
                     QObject *parent = nullptr);
  ~PlaybackController() override;
  QVariantMap track() const { return m_track; }
  QString animatedArtworkUrl() const { return m_animatedArtworkUrl; }
  AudioEngineOutput *audioEngine() { return &m_audioEngineOutput; }
  SlopDetector *slop() { return &m_slop; }
  // Bitrate in kbps. If this reads 0, we are either loading or communicating
  // via smoke signals.
  int bitrate() const {
    if (remoteActive())
      return 0;
    const int b = m_track.value(QStringLiteral("bitrate")).toInt();
    if (b <= 0)
      return 0;
    return b >= 1000 ? (b + 500) / 1000 : b;
  }
  QString queueError() const { return m_queueError; }
  QVariantList queue() const { return m_queue; }
  BestMixController *bestMix() { return &m_bestMix; }
  bool bestMixSorted() const { return m_bestMixSorted; }
  bool playing() const {
    return (m_player && m_player->playbackState() == QMediaPlayer::PlayingState) ||
           (m_crossfadeActive && m_nextPlayer &&
            m_nextPlayer->playbackState() == QMediaPlayer::PlayingState);
  }
  bool loading() const { return m_loading; }
  QString errorMessage() const { return m_error; }
  double position() const {
    if (m_resumePosition > 0.0)
      return m_resumePosition;
    return m_player ? m_player->position() / 1000.0 : 0.0;
  }
  // What the speakers play now; lyrics sync to this, the seek bar to position().
  double audiblePosition() const {
    const double audible = m_resumePosition > 0.0 || m_crossfadeActive
                               ? -1.0
                               : m_audioEngineOutput.audiblePosition();
    return audible >= 0.0 ? audible : position();
  }
  double duration() const {
    const double playerDuration = m_player ? m_player->duration() / 1000.0 : 0.0;
    return playerDuration > 0.0 ? playerDuration : m_restoredDuration;
  }
  bool gaplessEnabled() const { return m_gaplessEnabled; }
  void setGaplessEnabled(bool enabled);
  bool exponentialVolumeEnabled() const { return m_exponentialVolumeEnabled; }
  void setExponentialVolumeEnabled(bool enabled);
  bool crossfadeEnabled() const { return m_crossfadeEnabled; }
  void setCrossfadeEnabled(bool enabled);
  int crossfadeDuration() const { return m_crossfadeDuration; }
  void setCrossfadeDuration(int seconds);
  AdaptiveMixController *adaptiveMix() { return &m_adaptiveMix; }
  QVariantMap transitionTrack() const { return m_crossfadeTrack; }
  // The incoming song's own clock during a crossfade; 0 otherwise.
  double transitionPosition() const {
    return m_crossfadeActive && m_nextPlayer ? std::max(0.0, m_nextPlayer->position() / 1000.0) : 0.0;
  }
  double transitionDuration() const { return m_crossfadeActive ? m_crossfadeIncomingDuration : 0.0; }
  bool crossfadeActive() const { return m_crossfadeActive; }
  double crossfadeProgress() const { return m_crossfadeProgress; }
  double displayPosition() const;
  double displayDuration() const;
  // Outgoing position where the next mix begins, or -1 when none is planned.
  double mixStart() const;
  double mixDuration() const;
  bool autoplayEnabled() const { return m_autoplayEnabled; }
  bool autoplayLoading() const { return m_autoplayRequest != 0; }
  QString autoplayError() const { return m_autoplayError; }
  void setAutoplayEnabled(bool enabled);
  Q_INVOKABLE void retryAutoplay();
  bool playbackPersistenceEnabled() const { return m_persistenceEnabled; }
  void setPlaybackPersistenceEnabled(bool enabled);
  bool youtubeHistoryEnabled() const { return m_youtubeHistoryEnabled; }
  void setYouTubeHistoryEnabled(bool enabled);
  QString nonMusicSkipMode() const;
  void setNonMusicSkipMode(const QString &mode);
  QVariantMap nonMusicSegment() const;
  QVariantList skipSegments() const;
  Q_INVOKABLE void skipNonMusic();
  Q_INVOKABLE void restorePlayback();
  Q_INVOKABLE void savePlaybackState();
  // Last visited page snapshot; follows the same persistence switch as the queue.
  Q_INVOKABLE void savePage(const QVariantMap &page);
  Q_INVOKABLE QVariantMap restoredPage() const;
  // saver, normal, high or max. Saved MAX reads as high while Qobuz is disconnected.
  QString streamQuality() const;
  void setStreamQuality(const QString &quality);
  bool shuffleEnabled() const { return m_shuffleEnabled; }
  QString repeatMode() const { return m_repeatMode; }
  QVariantList history() const { return m_history; }
  // Video id the stream actually opened, which may be an album-audio match
  // for a music-video row. Empty until the track's stream has resolved.
  QString playbackVideoId(const QString &trackId) const {
    return m_playbackVideoIds.value(trackId);
  }
  // Drops transient stream keys so a saved track never carries an expired url.
  static QVariantMap sanitizeTrack(const QVariantMap &track);
  static QVariantList sanitizeTrackList(const QVariantList &tracks, int maxItems = 2500);
  bool canGoNext() const {
    return !m_queue.isEmpty() ||
           // Local songs get no recommendations, so autoplay is never a "next" for them.
           (m_autoplayEnabled && !m_track.isEmpty() && !local::isLocalTrack(m_track) && !offlineMode() &&
            m_autoplaySuppressed != m_track.value("id").toString()) ||
           (m_repeatMode == "all" && !m_track.isEmpty());
  }
  void setShuffleEnabled(bool enabled);
  void setRepeatMode(const QString &mode);
  Q_INVOKABLE void cycleRepeatMode();
  bool canGoPrevious() const {
    return !m_track.isEmpty() && (position() > 3.0 || !m_history.isEmpty());
  }

  Q_INVOKABLE void playSong(const QVariantMap &track);
  Q_INVOKABLE void playCollection(const QVariantList &tracks,
                                  int startIndex = 0, bool shuffle = false,
                                  const QString &playlistId = {},
                                  const QString &continuation = {});
  Q_INVOKABLE void retryQueueLoading();
  Q_INVOKABLE void enqueue(const QVariantMap &track, bool playNext = false);
  Q_INVOKABLE void removeFromQueue(int index);
  Q_INVOKABLE void clearQueue();
  Q_INVOKABLE void toggleBestMix();
  Q_INVOKABLE void moveQueueItem(int from, int to);
  Q_INVOKABLE void playQueueIndex(int index);
  // Rewinds to an already played song; the songs in between return to the front of the queue.
  Q_INVOKABLE void playHistoryIndex(int index);
  Q_INVOKABLE void play();
  Q_INVOKABLE void pause();
  Q_INVOKABLE void toggle();
  Q_INVOKABLE void seek(double seconds);
  Q_INVOKABLE void seekBy(double seconds);
  Q_INVOKABLE void stop();
  Q_INVOKABLE void next();
  Q_INVOKABLE void previous();

  // playback_video_source.cpp. Swaps the current track between album audio and its
  // music video's muxed stream at the same position, so picture and sound share a clock.
  void playVideoSource(const QJsonObject &stream);
  void playAlbumSource();

  // playback_remote.cpp. The plain getters above always describe this desktop's
  // own player; QML and other views read these, which follow a Connect target.
  void setRemote(PlaybackRemote *remote);
  // Set before the first stream; the range reader keeps this pointer.
  void setRemoteProvider(RemoteProvider *provider);
  // playback_offline.cpp: downloaded songs play from disk; offline mode limits the queue to them.
  void setOfflineSources(DownloadManager *downloads, ConnectivityMonitor *connectivity);
  bool offlineMode() const;
  // Connect roles moved, so a peer's provider session may have come or gone.
  void remoteProviderChanged();
  bool qobuzReachable() const;
  bool remoteActive() const { return m_remote && m_remote->active(); }
  // The remote's state moved or remote mode toggled.
  void remoteChanged();
  QVariantMap shownTrack() const;
  QVariantList shownQueue() const;
  QVariantList shownHistory() const;
  QString queueLayout() const { return m_queueLayout; }
  void setQueueLayout(const QString &layout);
  bool shownPlaying() const;
  bool shownLoading() const;
  double shownPosition() const;
  double shownAudiblePosition() const;
  double shownDuration() const;
  double shownDisplayPosition() const;
  double shownDisplayDuration() const;
  bool shownShuffle() const;
  QString shownRepeatMode() const;
  bool shownCanGoNext() const;
  bool shownCanGoPrevious() const;
  // Starts `tracks[index]` at `position` with the rest queued, as a Connect transfer needs.
  void playFrom(const QVariantList &tracks, int index, double position, bool play);
signals:
  void queueChanged();
  void historyChanged();
  void queueLayoutChanged();
  void bestMixStateChanged();
  void stateChanged();
  // The music video stream feeding the audio engine failed; album audio takes over.
  void videoSourceLost();
  void streamQualityChanged();
  void animatedArtworkUrlChanged();

private:
  // True when the action went to a Connect target.
  bool forwardRemote(const QString &action, const QVariantMap &args = {});
  PlaybackRemote *m_remote{nullptr};
  void updateAnimatedArtwork();
  void ensureAutoplay();
  // Applies the skip/remove setting to flagged tracks waiting in the queue.
  void purgeFlaggedFromQueue();
  void cancelAutoplay();
  void resolveTrackAlbum();
  bool m_autoplayEnabled{true};
  QString m_streamQuality{QStringLiteral("high")};
  bool m_qobuzWasReachable{false};
  // Temporarily store position so quality hopping doesn't send the user back to
  // second zero.
  double m_resumePosition{0.0};
  double m_restoredDuration{0.0};
  bool m_resumePaused{false};
  bool m_waitingForAutoplay{false};
  quint64 m_autoplayRequest{0};
  quint64 m_albumRequest{0};
  QString m_autoplaySeed;
  QString m_autoplaySuppressed;
  QString m_autoplayError;
  void appendPlaylistTracks(const QString &playlistId,
                            const QVariantList &tracks);
  void cancelQueueLoading();
  void startTrack(const QVariantMap &track, bool replaceQueue = false,
                  double initialPosition = 0.0, bool startPaused = false);
  void updateSystemMedia();
  void applyMasterVolume();
  void updateIntegrations();
  void receiveStream(quint64 id, const QJsonValue &result);
  void ensurePlaybackBackend();
  void wirePlayer(QMediaPlayer *source);
  void applyPendingResume();
  void fail(const QString &message);
  void trackYouTubeHistory(double position);
  void reportYouTubeHistory(bool final);
  void rememberStreamTracking(const QString &trackId, const QJsonObject &stream);
  // playback_local.cpp: files on this computer open straight in the player.
  void startLocalFile();
  void openPreparedLocal();
  void noteLocalMetadata(QMediaPlayer *source);
  // Shared by local files and downloads: open a file on disk in the current player.
  void openFile(const QString &path, int bitrate);
  void openPreparedFile(const QString &path, int bitrate);
  // Starts a validated stream (YouTube or provider) on the current player.
  void startResolvedStream(const QJsonObject &stream);
  void openPreparedStream(const QJsonObject &stream);
  // playback_qobuz.cpp: Qobuz first when it is on, YouTube otherwise.
  void attachQobuz();
  void installRangeReaders();
  bool qobuzEligible(const QVariantMap &track) const;
  bool localQobuzActive() const;
  // Local Qobuz, or a Connect peer's when only it is signed in.
  bool qobuzAvailable() const;
  void resolveQobuz(const QVariantMap &track, std::function<void(const QJsonValue &, const QString &)> done);
  RemoteProvider *m_remoteProvider{nullptr};
  // playback_offline.cpp
  QString downloadedFileFor(const QVariantMap &track) const;
  void startDownloadedFile(const QString &path);
  void applyOffline();
  DownloadManager *m_downloads{nullptr};
  ConnectivityMonitor *m_connectivity{nullptr};
  // YouTube has no MAX; Qobuz misses play at High.
  QString youtubeQuality() const;
  void requestStream(bool refreshStream = false, bool tryQobuz = true);
  void requestPreparedStream(bool refreshStream);
  QJsonObject qobuzStream(const QVariantMap &track, const QJsonValue &result) const;
  void applyStreamQuality();
  void reportQobuzStarted(AudioStreamProxy *proxy, double position);
  void reportQobuzEnded(AudioStreamProxy *proxy, QMediaPlayer *player);

  YouTubeProvider *m_provider;
  QobuzService *m_qobuz{nullptr};
  // Invalidates a preload's pending Qobuz lookup; YouTube preloads use m_nextRequest.
  quint64 m_preparedQobuzToken{0};
  bool m_qobuzWasActive{false};
  bool m_qobuzWasConnected{false};
  AuthManager *m_auth;
  HomeController *m_home;
  SystemMediaBridge *m_systemMedia;
  Integrations *m_integrations;
  bool m_switchingTrack = false;
  void prepareNextTrack();
  void clearPreparedTrack();
  void failPreparedTrack(int status);
  void maybeStartCrossfade();
  void finishCrossfade();
  void cancelCrossfadeTransition();
  bool hasCrossfadeTarget() const;
  std::unique_ptr<QMediaPlayer> m_players[2];
  QMediaPlayer *m_player{nullptr};
  QMediaPlayer *m_nextPlayer{nullptr};
  AudioEngineOutput m_audioEngineOutput;
  AdaptiveMixController m_adaptiveMix;
  BestMixController m_bestMix;
  SlopDetector m_slop;
  QString m_scannedTrackId;
  AudioStreamProxy m_proxies[2];
  AudioStreamProxy *m_proxy{&m_proxies[0]};
  AudioStreamProxy *m_nextProxy{&m_proxies[1]};
  bool m_gaplessEnabled{false};
  bool m_exponentialVolumeEnabled{false};
  bool m_crossfadeEnabled{true};
  int m_crossfadeDuration{6};
  bool m_crossfadeActive{false};
  double m_crossfadeProgress{0.0};
  double m_crossfadeOutgoingDisplayDuration{0.0};
  double m_crossfadeIncomingDuration{0.0};
  QVariantMap m_crossfadeTrack;
  int m_crossfadeBitrate{0};
  quint64 m_nextRequest{0};
  QVariantMap m_preparedTrack;
  int m_preparedBitrate{0};
  bool m_preparedRetried{false};
  // "current\nprepared" ids whose preload failed; skipped until the pair changes.
  QString m_failedPreparedPair;
  QVariantMap m_track;
  QVariantList m_history;
  QString m_queueLayout{QStringLiteral("upNext")};
  QVariantList m_queue;
  QVariantList m_bestMixOriginal;
  QVariantList m_bestMixSortedQueue;
  bool m_bestMixSorted{false};
  QString m_error;
  QString m_playlistId;
  QString m_queueContinuation;
  QString m_queueError;
  QSet<QString> m_queuePages;
  quint64 m_queueRequest{0};
  int m_playlistLoaded{0};
  bool m_shuffleEnabled{false};
  QString m_repeatMode{QStringLiteral("off")};
  QVariantList m_orderedQueue;
  QVariantList m_cyclePlayed;
  quint64 m_request{0};
  quint64 m_generation{0};
  QElapsedTimer m_startupTimer;
  double m_lastPublishedPosition{0.0};
  bool m_reportedAudio{false};
  bool m_loading{false};
  bool m_retried{false};
  bool m_prepared{false};
  bool m_suppressHistory{false};
  bool m_persistenceEnabled{true};
  bool m_restoringPlayback{false};
  QTimer m_persistTimer;
  bool m_youtubeHistoryEnabled{true};
  void loadNonMusicSkipMode();
  void pushHistory(const QVariantMap &track);
  void requestNonMusicSegments(const QString &trackId, const QString &videoId,
                               double durationSeconds);
  bool receiveNonMusicSegments(quint64 id, const QJsonValue &result);
  bool failedNonMusicSegments(quint64 id);
  QVariantMap nonMusicSegmentAt(double seconds) const;
  void skipSegment(const QVariantMap &segment);
  void autoSkipNonMusic(double positionSeconds);
  QString m_nonMusicSkipMode{QStringLiteral("button")};
  QHash<QString, QVariantList> m_skipSegments;
  QHash<quint64, QString> m_skipRequests;
  // Track whose audio currently comes from its music video; empty for album audio.
  QString m_videoSourceTrackId;
  void prepareSourceSwitch(const QString &trackId, double position);
  QSet<QString> m_autoSkipped;
  QString m_historyVideoId;
  QString m_historyCpn;
  QJsonObject m_historyTracking;
  QHash<QString, QJsonObject> m_streamTracking;
  QHash<QString, QString> m_playbackVideoIds;
  QSet<quint64> m_historyRequests;
  double m_historyPosition{0.0};
  double m_historyReported{0.0};
  AnimatedArtworkService *m_animatedArtworkService{nullptr};
  QString m_animatedArtworkUrl;
  QString m_lastArtworkTrackId;
  // Track m_animatedArtworkUrl was resolved for. It lags m_track on skips.
  QString m_animatedArtworkTrackId;
  quint64 m_artworkRequestId{0};
};
