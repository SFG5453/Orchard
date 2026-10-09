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

#include "animated_artwork_store.h"

#include <QHash>
#include <QList>
#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <QStringList>
#include <functional>
#include <memory>

class AppearanceSettings;
class QNetworkReply;
class SpotifyCanvas;

// Fetches animated cover artwork across configurable mirrors with automatic fallback.
// Because staring at a static JPEG in 2026 feels like using a dial-up modem.
class AnimatedArtworkService final : public QObject {
    Q_OBJECT

public:
    explicit AnimatedArtworkService(AppearanceSettings *settings, QObject *parent = nullptr);
    ~AnimatedArtworkService() override = default;

    // Resolves animated cover artwork for a track.
    void resolveTrackArtwork(const QString &title, const QString &artist, const QString &album,
                             quint64 requestId, std::function<void(quint64, const QString &)> callback);

    // Resolves animated cover artwork for an album.
    void resolveAlbumArtwork(const QString &title, const QString &artist,
                             quint64 requestId, std::function<void(quint64, const QString &)> callback);

    // QML entry point; answers through albumArtworkResolved, synchronously on a cache hit.
    Q_INVOKABLE void requestAlbumArtwork(const QString &title, const QString &artist);

    // Called when a resolved video fails to load, so the next play looks it up again.
    Q_INVOKABLE void forgetUrl(const QString &url);
    // Canvas loops for tracks the other mirrors miss. Connecting it retries old misses.
    void setSpotifyCanvas(SpotifyCanvas *spotify);
    void forgetMisses();

signals:
    // Empty url means no loop exists for that album.
    void albumArtworkResolved(const QString &title, const QString &artist, const QString &url);

public:
    static QString directMp4FromHlsUrl(const QString &url);
    // Master playlist beside a stored variant mp4; empty when the path has no Apple asset id.
    static QString masterManifestUrl(const QString &mp4Url);
    // Smallest H.264 variant at least 480 px wide, as a direct mp4. Empty when none qualifies.
    static QString smallVariantMp4(const QString &manifestText, const QString &manifestUrl);
    static QString normalizeText(const QString &value);
    static bool looseMatches(const QString &left, const QString &right);
    // Drops edition tags ("(Deluxe)", "- Single", "[Remastered]") before normalizing.
    static QString stripEdition(const QString &value);
    // Exact match after stripping edition tags, so "Album" never passes for "Album 2".
    static bool editionlessMatches(const QString &left, const QString &right);

private:
    struct LookupState;

    void executeLookup(std::shared_ptr<LookupState> state);
    void queryNextMirror(std::shared_ptr<LookupState> state);
    void fetchM8tec(std::shared_ptr<LookupState> state);
    void fetchSpotify(std::shared_ptr<LookupState> state);
    // byAlbum searches with the album name; only an iTunes-verified album match is accepted then.
    void fetchBoidu(std::shared_ptr<LookupState> state, bool byAlbum);
    void verifyBoiduAlbum(const QString &albumId, std::shared_ptr<LookupState> state,
                          bool requireVerified, std::function<void()> onAccepted);
    void mirrorFailed(std::shared_ptr<LookupState> state);
    bool cachedResult(const QString &key, QString *result);
    void acceptMotionUrl(const QString &rawMotion, std::shared_ptr<LookupState> state);
    void resolveHlsVariant(const QString &manifestUrl, std::shared_ptr<LookupState> state,
                           std::function<void(const QString &)> onResolved);
    // Swaps a full-size mp4 for a small variant sized for card art; falls back to the input.
    void resolveSmallVariant(const QString &fullUrl, std::function<void(const QString &)> done);
    void completeLookup(std::shared_ptr<LookupState> state, const QString &result);

    QString cacheKey(const QString &title, const QString &artist, const QString &album) const;

    AppearanceSettings *m_settings{nullptr};
    SpotifyCanvas *m_spotify{nullptr};
    QNetworkAccessManager m_network;
    // Session cache: fetch once per song/album, reuse for the rest of eternity (or app restart).
    QHash<QString, QString> m_cache;
    // Full mp4 url to small variant url, for hover previews.
    QHash<QString, QString> m_smallVariants;
    // Empty results caused by a mirror error expire, so a brief outage doesn't stick all session.
    QHash<QString, qint64> m_retryAfterMs;
    // Disk tier under m_cache. Mirror-failure results never reach it.
    AnimatedArtworkStore m_store;
    // In-flight coalescing: because multiple requests for the same song shouldn't play network tug-of-war.
    QHash<QString, QList<std::function<void(const QString &)>>> m_pendingRequests;
};
