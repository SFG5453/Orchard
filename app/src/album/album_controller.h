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

#include "audiodb_artist.h"

#include <QByteArray>
#include <QCache>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QVariantMap>

class AuthManager;
class YouTubeCatalog;
class QNetworkReply;
class AnimatedArtworkService;
class QobuzService;
class AppleMusic;
class CatalogSource;

class AlbumController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool loading READ loading NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  Q_PROPERTY(QVariantMap detail READ detail NOTIFY stateChanged)
  Q_PROPERTY(QVariantMap palette READ palette NOTIFY stateChanged)
  Q_PROPERTY(bool artworkLoading READ artworkLoading NOTIFY stateChanged)
  Q_PROPERTY(QString animatedArtworkUrl READ animatedArtworkUrl NOTIFY stateChanged)
  // {tier: "hires"|"lossless", bitDepth, sampleRate} from Qobuz, or empty.
  Q_PROPERTY(QVariantMap streamQuality READ streamQuality NOTIFY streamQualityChanged)
  Q_PROPERTY(QVariantMap expandedSection READ expandedSection NOTIFY sectionChanged)
  Q_PROPERTY(bool sectionLoading READ sectionLoading NOTIFY sectionChanged)
  Q_PROPERTY(QString sectionError READ sectionError NOTIFY sectionChanged)

public:
  // Artist instances share request isolation and artwork sampling with albums.
  explicit AlbumController(YouTubeCatalog *catalog, AuthManager *auth,
                           QObject *parent = nullptr, bool artistMode = false,
                           AnimatedArtworkService *animatedArtwork = nullptr,
                           QobuzService *qobuz = nullptr);

  [[nodiscard]] bool loading() const { return m_loading; }
  [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
  [[nodiscard]] QVariantMap detail() const { return m_detail; }
  [[nodiscard]] QVariantMap palette() const { return m_palette; }
  [[nodiscard]] bool artworkLoading() const { return m_artworkLoading; }
  [[nodiscard]] QString animatedArtworkUrl() const { return m_animatedArtworkUrl; }
  [[nodiscard]] QVariantMap streamQuality() const { return m_streamQuality; }
  [[nodiscard]] QVariantMap expandedSection() const { return m_expandedSection; }
  [[nodiscard]] bool sectionLoading() const { return m_sectionLoading; }
  [[nodiscard]] QString sectionError() const { return m_sectionError; }

  Q_INVOKABLE void openAlbum(const QVariantMap &item);
  Q_INVOKABLE void openSection(const QVariantMap &section);
  Q_INVOKABLE void closeSection();
  Q_INVOKABLE void retrySection();
  // Opens am.* pages, and artist pages too while the listener is signed in to Apple Music.
  void setAppleMusic(AppleMusic *appleMusic, CatalogSource *source);
  Q_INVOKABLE void retry();
  Q_INVOKABLE void clear();

signals:
  void stateChanged();
  void streamQualityChanged();
  void sectionChanged();

private:
  void updateAnimatedArtwork();
  void updateStreamQuality();
  void receiveAlbum(quint64 requestId, const QJsonObject &album);
  void receiveFailure(quint64 requestId, const QString &message);
  void receiveSection(quint64 requestId, const QJsonObject &page, const QString &error = {});
  // Loads [appleId], or the Apple artist named [name].
  void openFromAppleMusic(const QString &appleId, const QString &name);
  void fail(const QString &message);
  void requestArtwork(const QString &url);
  void requestArtistArtwork();
  void applyAudioDbArtist(const AudioDbArtist &artist);
  void sampleArtwork(QByteArray bytes);
  void cancelArtwork();
  static QVariantMap defaultPalette();

  const bool m_artistMode;
  YouTubeCatalog *m_catalog;
  AuthManager *m_auth;
  QNetworkAccessManager m_network;
  QNetworkReply *m_artworkReply{nullptr};
  QNetworkReply *m_artistArtworkReply{nullptr};
  QCache<QString, AudioDbArtist> m_artistArtworkCache{64};
  QVariantMap m_pendingItem;
  QVariantMap m_detail;
  QVariantMap m_palette;
  QString m_errorMessage;
  quint64 m_requestId{0};
  quint64 m_artworkGeneration{0};
  bool m_loading{false};
  bool m_artworkLoading{false};
  AnimatedArtworkService *m_animatedArtworkService{nullptr};
  QString m_animatedArtworkUrl;
  quint64 m_animatedArtworkRequestId{0};
  QobuzService *m_qobuz{nullptr};
  AppleMusic *m_appleMusic{nullptr};
  CatalogSource *m_source{nullptr};
  quint64 m_appleRequests{0};
  QVariantMap m_streamQuality;
  quint64 m_streamQualityRequest{0};
  QVariantMap m_expandedSection;
  quint64 m_sectionRequest{0};
  bool m_sectionLoading{false};
  QString m_sectionError;
};
