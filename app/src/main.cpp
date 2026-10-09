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

#include "account/orchard_account.h"
#include "auth/auth_manager.h"
#include "auth/auth_diagnostics.h"
#include "album/album_controller.h"
#include "playlist/playlist_controller.h"
#include "backend_info.h"
#include "connect/connect_service.h"
#include "crash_handler.h"
#include "docs/docs_assistant.h"
#include "docs/docs_embedder.h"
#include "docs/docs_library.h"
#include "appearance/appearance_settings.h"
#include "appearance/animated_artwork_service.h"
#include "home/home_controller.h"
#include "integrations/integrations.h"
#include "integrations/spotify_canvas.h"
#include "instance_coordinator.h"
#include "library/library_actions.h"
#include "local/local_library.h"
#include "lyrics/lyrics_controller.h"
#include "lyrics/translation/lyrics_translator.h"
#include "offline/connectivity_monitor.h"
#include "offline/download_manager.h"
#include "offline/offline_library.h"
#include "playback/sleep_timer.h"
#include "playback/music_video_controller.h"
#include "playback/playback_controller.h"
#include "providers/qobuz/qobuz_service.h"
#include "providers/youtube/catalog/youtube_catalog.h"
#include "providers/youtube/youtube_provider.h"
#include "search/search_controller.h"
#include "support/support_center.h"
#include "system_media_bridge.h"
#include "system_tray.h"
#include "update/bootstrapper_client.h"
#include "window_state.h"

// why does QT flood code bases with Q instead of a regular class name?
// I guess it's easier to type but whatever.
#include <QApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QFont>
#include <QFontDatabase>
#include <QIcon>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMessageBox>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QQuickStyle>
#include <QTextStream>
#include <QTimer>
#include <QUrl>
#include <QVulkanInstance>
#include <chrono>
#include <cstdlib>
#include <memory>
#include <thread>

#ifdef Q_OS_WIN
#include <shobjidl.h>
#endif

#if defined(__GLIBC__)
#include <malloc.h>
#endif

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QQmlNetworkAccessManagerFactory>

class NoHttp2NetworkAccessManager : public QNetworkAccessManager {
public:
  explicit NoHttp2NetworkAccessManager(QObject *parent = nullptr)
      : QNetworkAccessManager(parent) {}

protected:
  QNetworkReply *createRequest(Operation op, const QNetworkRequest &request,
                               QIODevice *outgoingData = nullptr) override {
    QNetworkRequest req(request);
    req.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    return QNetworkAccessManager::createRequest(op, req, outgoingData);
  }
};

class NoHttp2NetworkFactory : public QQmlNetworkAccessManagerFactory {
public:
  QNetworkAccessManager *create(QObject *parent) override {
    return new NoHttp2NetworkAccessManager(parent);
  }
};

// Keep release history in the build so About works even when the update server does not.
static QVariantList bundledReleaseNotes() {
  QFile file(QStringLiteral(":/release-notes/releases.json"));
  if (!file.open(QIODevice::ReadOnly)) {
    qWarning("Could not open bundled release notes");
    return {};
  }
  const QJsonDocument document = QJsonDocument::fromJson(file.readAll());
  if (!document.isArray()) {
    qWarning("Bundled release notes are not a JSON array");
    return {};
  }
  return document.array().toVariantList();
}

// Bounds ~QGuiApplication. Qt's QHostInfo pool joins in-flight getaddrinfo()
// calls when the application object dies, which blocks for the full resolver
// timeout on a bad network. DNS: the only lookup that ghosts you on the way out.
struct ShutdownDeadline {
  int exitCode = 0;
  ~ShutdownDeadline() {
    std::thread([code = exitCode] {
      std::this_thread::sleep_for(std::chrono::seconds(2));
      std::_Exit(code);
    }).detach();
  }
};

int main(int argc, char *argv[]) {
  installCrashHandler();

#ifdef Q_OS_WIN
  // Tell Windows who owns the media flyout before Qt creates any windows.
  // The Start Menu shortcut must use this same ID; otherwise Windows plays shy.
  SetCurrentProcessExplicitAppUserModelID(L"dev.sfg.orchard");
#endif

#if defined(__GLIBC__)
  // Qt, FFmpeg and the graphics stack create many worker threads. Glibc's
  // default per-thread arena policy keeps tens of MiB of otherwise idle heap
  // resident in a long-lived GUI process.
  mallopt(M_ARENA_MAX, 2);
#endif

  QCoreApplication::setOrganizationDomain(QStringLiteral("sfg.dev"));
  QCoreApplication::setOrganizationName(QStringLiteral("SFG545"));
  QCoreApplication::setApplicationName(QStringLiteral("Orchard"));

  // QApplication for QSystemTrayIcon, whose menus are still widgets in 2026.
  QApplication application(argc, argv);
  // The running binary is the source of truth; About should not time travel to 0.2.0.
  QCoreApplication::setApplicationVersion(QStringLiteral(ORCHARD_VERSION));
  // Declared before every other local so it arms only once they are all destroyed.
  ShutdownDeadline shutdownDeadline;
  InstanceCoordinator instance;
  const auto instanceResult = instance.start();
  if (instanceResult == InstanceCoordinator::StartResult::Forwarded)
    return 0;
  if (instanceResult == InstanceCoordinator::StartResult::Failed) {
    QMessageBox::critical(nullptr, QStringLiteral("Orchard"),
                          QStringLiteral("Orchard could not prepare its startup channel. Please try again."));
    shutdownDeadline.exitCode = 1;
    return 1;
  }

#ifdef Q_OS_WIN
  // Analysis workers inherit this; Windows has no ffmpeg on PATH to fall back to.
  // 165 MB of ffmpeg.exe, and we use it to read audio. Very reasonable.
  const QString bundledFfmpeg =
      QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("ffmpeg.exe"));
  if (!qEnvironmentVariableIsSet("ORCHARD_FFMPEG") && QFileInfo::exists(bundledFfmpeg))
    qputenv("ORCHARD_FFMPEG", QDir::toNativeSeparators(bundledFfmpeg).toUtf8());
#endif

#if defined(Q_OS_LINUX) && !defined(Q_OS_ANDROID)
  if (!qEnvironmentVariableIsSet("QSG_RHI_BACKEND") &&
      !qEnvironmentVariableIsSet("QT_QUICK_BACKEND")) {
    QVulkanInstance vulkanProbe;
    if (vulkanProbe.create())
      QQuickWindow::setGraphicsApi(QSGRendererInterface::Vulkan);
  }
#endif
  authDiagnostic(QStringLiteral("startup diagnostic-build=3 built=" __DATE__ " " __TIME__));
  QObject::connect(&application, &QCoreApplication::aboutToQuit, &application, [] {
    authDiagnostic(QStringLiteral("application-quitting"));
  });
  application.setWindowIcon(QIcon(QStringLiteral(":/qt/qml/Orchard/app/qml/assets/orchard-logo.png")));
  // Custom control backgrounds, content items, and handles need a non-native style.
  QQuickStyle::setStyle(QStringLiteral("Basic"));

  const QStringList fontFiles = {
      QStringLiteral(":/qt/qml/Orchard/app/qml/assets/fonts/inter_regular.ttf"),
      QStringLiteral(":/qt/qml/Orchard/app/qml/assets/fonts/inter_medium.ttf"),
      QStringLiteral(
          ":/qt/qml/Orchard/app/qml/assets/fonts/inter_semibold.ttf"),
      QStringLiteral(":/qt/qml/Orchard/app/qml/assets/fonts/inter_bold.ttf"),
  };
  for (const auto &fontFile : fontFiles) {
    QFontDatabase::addApplicationFont(fontFile);
  }

  QFont defaultFont(QStringLiteral("Inter"));
  defaultFont.setStyleHint(QFont::SansSerif);
  application.setFont(defaultFont);
  AppearanceSettings appearance;
  // Spotify lends its looping videos when the cover mirrors come up empty.
  SpotifyCanvas spotifyCanvas;
  AnimatedArtworkService animatedArtwork(&appearance);
  animatedArtwork.setSpotifyCanvas(&spotifyCanvas);
  BackendInfo backend;
  // The manual nobody reads, with a copy button for the robots that will.
  DocsLibrary docs;
  // The manual answers back, which is more than most manuals manage.
  DocsEmbedder docsEmbedder(DocsEmbedder::defaultProgram(), DocsEmbedder::defaultModels());
  DocsAssistant docsAssistant(&docs, &docsEmbedder, BertTokenizer());
  SystemMediaBridge systemMedia;
  YouTubeProvider youtubeProvider;
  AuthManager authManager(&youtubeProvider);
  OrchardAccount orchardAccount;
  // Bug reports with receipts: GitHub tells us who answered, we tell you.
  SupportCenter support(&orchardAccount);
  YouTubeCatalog youtubeCatalog(&youtubeProvider);
  // YouTube picks the songs; Qobuz, when invited, brings the good speakers.
  QobuzService qobuz;
  SearchController searchController(&youtubeCatalog, &authManager);
  auto homeController = std::make_unique<HomeController>(&youtubeCatalog, &authManager);
  auto artistController = std::make_unique<AlbumController>(&youtubeCatalog, &authManager, nullptr, true);
  auto albumController = std::make_unique<AlbumController>(&youtubeCatalog, &authManager, nullptr, false, &animatedArtwork, &qobuz);
  // Songs and playlists that live on disk instead of YouTube Music: no buffering, no ads, no excuses.
  LocalLibrary localLibrary;
  // Declared before the controllers that read them, so they outlive those controllers.
  ConnectivityMonitor connectivity;
  DownloadManager downloads(&youtubeProvider, &authManager);
  downloads.setConnectivity(&connectivity);
  downloads.setAnimatedArtworkService(&animatedArtwork);
  OfflineLibrary offlineLibrary(&downloads, &localLibrary, &connectivity);
  // A failed request is a hint that the network died; the monitor verifies it.
  QObject::connect(&youtubeProvider, &YouTubeProvider::requestFailed, &connectivity,
                   [&connectivity] { connectivity.reportFailure(); });
  searchController.setOfflineLibrary(&offlineLibrary);
  auto playlistController = std::make_unique<PlaylistController>(&youtubeCatalog, &authManager, &localLibrary);
  playlistController->setOfflineLibrary(&offlineLibrary);
  auto integrations = std::make_unique<Integrations>(&orchardAccount, &youtubeCatalog, &authManager);
  auto playback = std::make_unique<PlaybackController>(&youtubeProvider, &authManager, homeController.get(), &systemMedia, integrations.get(), &animatedArtwork, &qobuz);
  QObject::connect(&application, &QCoreApplication::aboutToQuit,
                   playback.get(), &PlaybackController::savePlaybackState);
  playback->setOfflineSources(&downloads, &connectivity);
  auto lyrics = std::make_unique<LyricsController>(&youtubeProvider, &authManager, playback.get());
  auto *discord = integrations->discord();
  // One lyric clock for the karaoke window and Discord; no second tiny conductor.
  QObject::connect(lyrics.get(), &LyricsController::activeLineChanged,
                   discord, &Discord::updateLyric);
  const auto syncDiscordLyrics = [lyrics = lyrics.get(), discord] {
    lyrics->setDiscordLyricsEnabled(discord->enabled() && discord->showSyncedLyrics());
  };
  QObject::connect(discord, &Discord::enabledChanged, lyrics.get(), syncDiscordLyrics);
  QObject::connect(discord, &Discord::showSyncedLyricsChanged, lyrics.get(), syncDiscordLyrics);
  syncDiscordLyrics();
  SleepTimer sleepTimer(playback.get());
  lyrics->setLocalLibrary(&localLibrary);
  // Lyrics in English, courtesy of a 20 MB model that has never heard a song in its life.
  LyricsTranslator lyricsTranslation(lyrics.get());
  MusicVideoController musicVideo(&youtubeProvider, &authManager, playback.get());
  // Local imports announce themselves through the same pill as copied links.
  QObject::connect(&localLibrary, &LocalLibrary::noticeRequested, &backend, &BackendInfo::noticeRequested);
  QObject::connect(&downloads, &DownloadManager::noticeRequested, &backend, &BackendInfo::noticeRequested);
  auto libraryActions = std::make_unique<LibraryActions>(&youtubeProvider, &authManager, playback.get(), homeController.get());
  QObject::connect(libraryActions.get(), &LibraryActions::removedFromPlaylist,
                   playlistController.get(), &PlaylistController::removeTrack);
  // Phone in one hand, desktop in the other, and only one of them gets to be the DJ.
  ConnectService connectService(&orchardAccount, playback.get(), homeController.get(), &authManager, &qobuz);
  connectService.setCatalog(&youtubeCatalog);
  connectService.setArtwork(&animatedArtwork);
  SystemTray tray(playback.get(), &authManager);
  WindowState windowState;
  // Talks to the bootstrapper that launched us; inert in development builds.
  BootstrapperClient updates;

  QQmlApplicationEngine engine;
  QObject::connect(&engine, &QQmlEngine::warnings, &application,
                   [](const QList<QQmlError> &warnings) {
                     for (const auto &warning : warnings)
                       QTextStream(stderr) << warning.toString() << '\n';
                   });
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAppearance"), &appearance);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAnimatedArtwork"), &animatedArtwork);
  NoHttp2NetworkFactory networkFactory;
  engine.setNetworkAccessManagerFactory(&networkFactory);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardBackend"),
                                           &backend);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardDocs"), &docs);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardDocsAssistant"), &docsAssistant);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardSystemMedia"),
                                           &systemMedia);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAuth"),
                                           &authManager);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAccount"),
                                           &orchardAccount);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardSupport"), &support);
  // The engine owns the provider; support outlives the engine.
  engine.addImageProvider(QStringLiteral("support"), new SupportImageProvider(&support));
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardHome"),
                                           homeController.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardSearch"),
                                           &searchController);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardArtist"), artistController.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAlbum"),
                                           albumController.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardPlaylist"),
                                           playlistController.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardPlayback"), playback.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardConnect"), &connectService);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardIntegrations"), integrations.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardQobuz"), &qobuz);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardSpotify"), &spotifyCanvas);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardLyrics"), lyrics.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardTranslation"), &lyricsTranslation);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardSleep"), &sleepTimer);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardMusicVideo"), &musicVideo);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardLibrary"), libraryActions.get());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardLocal"), &localLibrary);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardNetwork"), &connectivity);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardDownloads"), &downloads);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardOffline"), &offlineLibrary);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardTray"), &tray);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardWindowState"), &windowState);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardUpdates"), &updates);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardReleaseNotes"),
                                           bundledReleaseNotes());

  connectivity.start();

  // Keep the QML folders intact; relative imports are breadcrumbs, not confetti.
  engine.load(QUrl(QStringLiteral("qrc:/qt/qml/Orchard/app/qml/Main.qml")));
  if (engine.rootObjects().isEmpty()) {
    QTextStream(stderr) << "Orchard could not load its QML entry point.\n";
    shutdownDeadline.exitCode = 1;
    return 1;
  }

#if defined(__GLIBC__)
  QTimer::singleShot(8000, &application, [] { malloc_trim(0); });
#endif

  if (auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst())) {
    tray.setWindow(window);
    windowState.setWindow(window);
#ifdef Q_OS_WIN
    systemMedia.start(window->winId());
#else
    if (!systemMedia.attached()) {
      systemMedia.start(window->winId());
    }
#endif
  }
  // A launch during QML loading waits here for a real window to receive it.
  instance.setActivationHandler([&tray] { tray.showWindow(); });

  QObject::connect(&systemMedia, &SystemMediaBridge::commandReceived,
                   [&tray](const QString &kind, const QVariant &) {
    if (kind == QStringLiteral("raise")) {
      tray.showWindow();
    } else if (kind == QStringLiteral("quit")) {
      QGuiApplication::quit();
    }
  });

  shutdownDeadline.exitCode = application.exec();
  return shutdownDeadline.exitCode;
}
