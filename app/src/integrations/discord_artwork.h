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

#pragma once

#include <QDateTime>
#include <QHash>
#include <QNetworkAccessManager>
#include <QObject>
#include <QSet>
#include <QString>
#include <QStringList>

#include <functional>
#include <memory>
#include <optional>

class OrchardAccount;

// Turns motion artwork (Apple's MP4 loops) into a hosted animated WebP that
// Discord can show: download, hash, encode with FFmpeg, upload to the account
// worker. One job at a time; a new source replaces the old one.
class DiscordArtwork final : public QObject
{
    Q_OBJECT

public:
    // Output settings, best first. Every rung keeps the full loop; the first
    // one predicted to fit the worker's size cap is used. Discord shows the
    // image at about 120px, so even the 320px floor is over 2x on HiDPI.
    // Frame rate only drops for high-motion loops (moving water, grain) that
    // WebP cannot fit otherwise: WebP frames only share unchanged regions.
    struct Rung {
        int dimension;
        int quality;
        double maxFps;
    };
    static constexpr Rung kLadder[] = {
        {512, 90, 30.0}, {512, 85, 30.0}, {448, 80, 30.0},
        {384, 75, 20.0}, {384, 70, 15.0}, {320, 75, 15.0},
    };
    static constexpr int kMaxDimension = 512;
    // Seconds encoded to predict a rung's full size (within ~3% on real loops).
    static constexpr int kSampleSeconds = 3;
    // Headroom under the cap for prediction error.
    static constexpr double kSampleBudget = 0.9;
    // Mirrors services/account/src/artwork.js.
    static constexpr qint64 kWorkerMaxBytes = 10 * 1024 * 1024;
    static constexpr int kWorkerMaxFrames = 1200;
    static constexpr double kWorkerMaxFps = 60.0;

    struct WebpInfo {
        int width{0};
        int height{0};
        bool animated{false};
        int frames{0};
        qint64 durationMs{0};
    };

    struct SourceInfo {
        QString codec;
        int width{0};
        int height{0};
        double fps{0.0};
        qint64 frames{0};

        // Loop length from the frame count; container durations include
        // Apple's HLS start offset.
        [[nodiscard]] double durationSeconds() const { return fps > 0.0 ? frames / fps : 0.0; }
    };

    explicit DiscordArtwork(OrchardAccount *account, QObject *parent = nullptr);
    ~DiscordArtwork() override;

    // Calls back once, on the event loop, with a public HTTPS URL or an empty
    // string when the artwork could not be prepared.
    void prepare(const QString &sourceUrl, std::function<void(const QString &)> done);
    // Drops the running job and its callbacks, killing FFmpeg if needed.
    void cancel();

    static std::optional<WebpInfo> parseWebp(const QByteArray &bytes);
    static QStringList encodeArguments(const QString &input, const QString &output,
                                       const SourceInfo &source, const Rung &rung,
                                       int sampleSeconds = 0);
    static std::optional<SourceInfo> parseProbe(const QByteArray &json);
    static QString ffmpegProgram();
    static QString ffprobeProgram();

private:
    struct Job;
    struct Hosted {
        QString url;
        QDateTime expiresAt;
    };

    void startJob(std::shared_ptr<Job> job);
    void checkIndex(std::shared_ptr<Job> job);
    void download(std::shared_ptr<Job> job);
    void probe(std::shared_ptr<Job> job);
    void sample(std::shared_ptr<Job> job, int rung);
    void encode(std::shared_ptr<Job> job, int rung);
    void uploadConverted(std::shared_ptr<Job> job);
    void upload(std::shared_ptr<Job> job, const QString &token, int attempt);
    void finish(std::shared_ptr<Job> job, const QString &url);
    void fail(std::shared_ptr<Job> job, const QString &reason, bool permanent = false);
    bool isCurrent(const std::shared_ptr<Job> &job) const;
    std::optional<QString> cachedUrl(const QString &key) const;
    void remember(const std::shared_ptr<Job> &job, const QString &url, const QDateTime &expiresAt);
    void loadHosted();
    void saveHosted() const;

    OrchardAccount *m_account{nullptr};
    QNetworkAccessManager m_network;
    std::shared_ptr<Job> m_job;
    // Keyed by source URL and by source SHA-256, so a new CDN URL for the
    // same bytes still hits.
    QHash<QString, Hosted> m_hosted;
    QString m_hostedPath;
    // Sources the worker or encoder rejected outright. Not retried this run.
    QSet<QString> m_rejected;
    QDateTime m_rateLimitedUntil;
    bool m_loggedSignedOut{false};
};
