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
#include "apple_music_fairplay.h"
#include "apple_music_playlist.h"
#include "apple_music_requests.h"
#include "integrations/apple_music.h"
#include "playback_controller.h"

#include <QDebug>
#include <QElapsedTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QQuickItem>
#include <QQuickWindow>

#include <algorithm>
#include <cmath>

#import <AVFoundation/AVFoundation.h>
#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>

using apple_music_requests::replyError;

namespace {
constexpr int kSyncMs = 200;
// Past this the picture seeks to the soundtrack; below it the playback rate closes the gap.
constexpr double kSeekSeconds = 0.3;
constexpr double kNudgeSeconds = 0.02;
constexpr double kMaxNudge = 0.04;
// A seek on HLS can take longer than the sync period; do not stack them.
constexpr qint64 kSeekPauseMs = 1500;
} // namespace

// AVContentKeySession calls this on its own queue. Each SPC goes to the main thread,
// where the owner posts it with Qt's network stack and answers the request.
@interface OrchardFairPlayKeys : NSObject <AVContentKeySessionDelegate>
@property(nonatomic, strong) NSData *certificate;
@property(atomic, copy) void (^spcReady)(AVContentKeyRequest *request, NSData *spc, NSString *uri);
@property(atomic, copy) void (^failed)(NSString *error);
@end

@implementation OrchardFairPlayKeys
- (void)report:(NSString *)error {
  __weak OrchardFairPlayKeys *weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    void (^failed)(NSString *) = weakSelf.failed;
    if (failed)
      failed(error);
  });
}

- (void)handle:(AVContentKeyRequest *)request {
  NSString *uri = [request.identifier isKindOfClass:[NSString class]] ? (NSString *)request.identifier : @"";
  NSData *contentId = [uri dataUsingEncoding:NSUTF8StringEncoding];
  __weak OrchardFairPlayKeys *weakSelf = self;
  [request makeStreamingContentKeyRequestDataForApp:self.certificate
                                  contentIdentifier:contentId
                                            options:@{AVContentKeyRequestProtocolVersionsKey : @[ @1 ]}
                                  completionHandler:^(NSData *spc, NSError *error) {
                                    if (!spc) {
                                      [request processContentKeyResponseError:error];
                                      [weakSelf report:error.localizedDescription ?: @"no SPC"];
                                      return;
                                    }
                                    dispatch_async(dispatch_get_main_queue(), ^{
                                      auto ready = weakSelf.spcReady;
                                      if (ready)
                                        ready(request, spc, uri);
                                      else
                                        [request processContentKeyResponseError:nil];
                                    });
                                  }];
}

- (void)contentKeySession:(AVContentKeySession *)session didProvideContentKeyRequest:(AVContentKeyRequest *)request {
  [self handle:request];
}

- (void)contentKeySession:(AVContentKeySession *)session
    didProvideRenewingContentKeyRequest:(AVContentKeyRequest *)request {
  [self handle:request];
}

- (void)contentKeySession:(AVContentKeySession *)session
         contentKeyRequest:(AVContentKeyRequest *)request
          didFailWithError:(NSError *)error {
  [self report:error.localizedDescription ?: @"key request failed"];
}
@end

struct AppleMusicFairPlay::Native {
  AVPlayer *player{nil};
  AVContentKeySession *keys{nil};
  OrchardFairPlayKeys *delegate{nil};
  // Sized to Qt's view and placed just under it; `clip` is the surface rect.
  CALayer *host{nil};
  CALayer *clip{nil};
  AVPlayerLayer *picture{nil};
  bool seeking{false};
  bool obscuredLogged{false};
  QElapsedTimer sinceSeek;
};

AppleMusicFairPlay::AppleMusicFairPlay(AppleMusic *account, PlaybackController *playback, QObject *parent)
    : QObject(parent), m_account(account), m_playback(playback), m_native(std::make_unique<Native>()) {
  Native &n = *m_native;
  n.player = [AVPlayer new];
  n.player.muted = YES;
  n.player.preventsDisplaySleepDuringVideoPlayback = YES;
  n.host = [CALayer layer];
  n.host.anchorPoint = CGPointZero;
  n.clip = [CALayer layer];
  n.clip.anchorPoint = CGPointZero;
  n.clip.masksToBounds = YES;
  n.clip.backgroundColor = NSColor.blackColor.CGColor;
  n.clip.hidden = YES;
  n.picture = [AVPlayerLayer playerLayerWithPlayer:n.player];
  n.picture.anchorPoint = CGPointZero;
  n.picture.videoGravity = AVLayerVideoGravityResizeAspect;
  [n.clip addSublayer:n.picture];
  [n.host addSublayer:n.clip];
  m_syncTimer.setInterval(kSyncMs);
  connect(&m_syncTimer, &QTimer::timeout, this, &AppleMusicFairPlay::sync);
}

AppleMusicFairPlay::~AppleMusicFairPlay() {
  stop();
  setSurface(nullptr);
  [m_native->host removeFromSuperlayer];
}

void AppleMusicFairPlay::start(const QString &appleId, int maxHeight) {
  stop();
  m_appleId = appleId;
  m_maxHeight = maxHeight;
  const quint64 session = m_session;
  if (!m_account || !m_account->connected()) {
    fail(session, {}, tr("Connect Apple Music under Settings, General to watch this video."));
    return;
  }
  m_account->playbackToken([this, session](const QString &token, const QString &error) {
    if (session != m_session)
      return;
    if (token.isEmpty())
      fail(session, {}, error.isEmpty() ? tr("Apple Music could not start this video.") : error);
    else
      requestPlayback(session, token);
  });
}

void AppleMusicFairPlay::stop() {
  ++m_session;
  m_syncTimer.stop();
  m_ready = false;
  m_height = 0;
  m_heights.clear();
  Native &n = *m_native;
  n.delegate.spcReady = nil;
  n.delegate.failed = nil;
  n.delegate = nil;
  n.keys = nil;
  n.seeking = false;
  n.obscuredLogged = false;
  [n.player pause];
  [n.player replaceCurrentItemWithPlayerItem:nil];
  place();
}

void AppleMusicFairPlay::requestPlayback(quint64 session, const QString &token) {
  QNetworkReply *reply = m_network.post(
      apple_music_requests::request(apple_music_requests::kWebPlaybackUrl, token, m_account->userToken()),
      apple_music_requests::webPlaybackBody(m_appleId));
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, token] {
    reply->deleteLater();
    if (session != m_session)
      return;
    const QJsonObject song =
        QJsonDocument::fromJson(reply->readAll()).object().value(QStringLiteral("songList")).toArray().first().toObject();
    const QUrl master(song.value(QStringLiteral("hls-playlist-url")).toString());
    const QUrl certificate(song.value(QStringLiteral("hls-key-cert-url")).toString());
    const QUrl license(song.value(QStringLiteral("hls-key-server-url")).toString());
    if (reply->error() != QNetworkReply::NoError || master.scheme() != QStringLiteral("https") ||
        certificate.scheme() != QStringLiteral("https") || license.scheme() != QStringLiteral("https")) {
      fail(session, replyError(reply), tr("Apple Music did not offer this video for playback."));
      return;
    }
    loadCertificate(session, token, master, certificate, license);
  });
}

void AppleMusicFairPlay::loadCertificate(quint64 session, const QString &token, const QUrl &master,
                                         const QUrl &certificate, const QUrl &license) {
  // The master only lists the heights for the quality menu; AVFoundation reads it again itself.
  QNetworkReply *listing = m_network.get(QNetworkRequest(master));
  connect(listing, &QNetworkReply::finished, this, [this, listing, session] {
    listing->deleteLater();
    if (session != m_session || listing->error() != QNetworkReply::NoError)
      return;
    apple_music_playlist::Master parsed;
    if (!apple_music_playlist::parseMaster(listing->url(), listing->readAll(), parsed).isEmpty())
      return;
    QList<int> heights;
    for (const auto &variant : std::as_const(parsed.variants))
      if (!heights.contains(variant.height))
        heights.append(variant.height);
    std::sort(heights.begin(), heights.end(), std::greater<>());
    m_heights.clear();
    for (int height : heights)
      m_heights.append(height);
    emit heightChanged();
  });
  QNetworkReply *reply = m_network.get(QNetworkRequest(certificate));
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, token, master, license] {
    reply->deleteLater();
    if (session != m_session)
      return;
    const QByteArray bundle = reply->readAll();
    if (reply->error() != QNetworkReply::NoError || bundle.isEmpty()) {
      fail(session, replyError(reply));
      return;
    }
    play(session, token, master, bundle, license);
  });
}

void AppleMusicFairPlay::play(quint64 session, const QString &token, const QUrl &master,
                              const QByteArray &certificate, const QUrl &license) {
  m_token = token;
  m_licenseUrl = license;
  Native &n = *m_native;
  n.delegate = [OrchardFairPlayKeys new];
  n.delegate.certificate = certificate.toNSData();
  QPointer<AppleMusicFairPlay> self(this);
  n.delegate.spcReady = ^(AVContentKeyRequest *request, NSData *spc, NSString *uri) {
    if (!self) {
      [request processContentKeyResponseError:nil];
      return;
    }
    self->license(session, QByteArray::fromNSData(spc), QString::fromNSString(uri), [request](const QByteArray &ckc) {
      if (ckc.isEmpty())
        [request processContentKeyResponseError:nil];
      else
        [request processContentKeyResponse:[AVContentKeyResponse
                                               contentKeyResponseWithFairPlayStreamingKeyResponseData:ckc.toNSData()]];
    });
  };
  n.delegate.failed = ^(NSString *error) {
    if (self)
      self->fail(session, QString::fromNSString(error));
  };
  n.keys = [AVContentKeySession contentKeySessionWithKeySystem:AVContentKeySystemFairPlayStreaming];
  [n.keys setDelegate:n.delegate queue:dispatch_queue_create("dev.sfg.orchard.fairplay", DISPATCH_QUEUE_SERIAL)];
  AVURLAsset *asset = [AVURLAsset URLAssetWithURL:master.toNSURL() options:nil];
  [n.keys addContentKeyRecipient:asset];
  AVPlayerItem *item = [AVPlayerItem playerItemWithAsset:asset];
  // Wide enough for any aspect; only the height caps the rendition.
  if (m_maxHeight > 0)
    item.preferredMaximumResolution = CGSizeMake(m_maxHeight * 4.0, m_maxHeight);
  [n.player replaceCurrentItemWithPlayerItem:item];
  m_syncTimer.start();
}

void AppleMusicFairPlay::license(quint64 session, const QByteArray &spc, const QString &keyUri,
                                 std::function<void(const QByteArray &)> done) {
  if (session != m_session) {
    done({});
    return;
  }
  QNetworkReply *reply = m_network.post(
      apple_music_requests::request(m_licenseUrl, m_token, m_account->userToken()),
      apple_music_requests::licenseBody(spc, keyUri, m_appleId, QStringLiteral("com.apple.fps")));
  connect(reply, &QNetworkReply::finished, this, [this, reply, session, done = std::move(done)] {
    reply->deleteLater();
    QString error;
    const QByteArray ckc = apple_music_requests::license(reply, error);
    done(session == m_session ? ckc : QByteArray());
    if (!error.isEmpty())
      fail(session, error);
  });
}

void AppleMusicFairPlay::sync() {
  Native &n = *m_native;
  AVPlayerItem *item = n.player.currentItem;
  if (!item)
    return;
  if (item.status == AVPlayerItemStatusFailed) {
    fail(m_session, QString::fromNSString(item.error.localizedDescription ?: @"player item failed"));
    return;
  }
  if (item.status != AVPlayerItemStatusReadyToPlay)
    return;
  if (!m_ready) {
    m_ready = true;
    place();
    emit started();
  }
  const int height = int(std::lround(item.presentationSize.height));
  if (height != m_height) {
    m_height = height;
    qInfo().nospace() << "AppleMusic: FairPlay picture " << int(std::lround(item.presentationSize.width)) << "x"
                      << height;
    emit heightChanged();
  }
  if (n.player.outputObscuredDueToInsufficientExternalProtection && !n.obscuredLogged) {
    n.obscuredLogged = true;
    qWarning() << "AppleMusic: FairPlay picture hidden, the display lacks the protection this rendition needs";
  }
  if (n.seeking)
    return;
  const bool playing = m_playback->playing();
  const double audible = m_playback->audiblePosition();
  const double drift = CMTimeGetSeconds(n.player.currentTime) - audible;
  if (std::abs(drift) > kSeekSeconds && (!n.sinceSeek.isValid() || n.sinceSeek.elapsed() > kSeekPauseMs)) {
    n.seeking = true;
    n.sinceSeek.start();
    QPointer<AppleMusicFairPlay> self(this);
    const quint64 session = m_session;
    [n.player seekToTime:CMTimeMakeWithSeconds(std::max(0.0, audible), 600)
          toleranceBefore:kCMTimeZero
           toleranceAfter:kCMTimeZero
        completionHandler:^(BOOL) {
          dispatch_async(dispatch_get_main_queue(), ^{
            if (self && session == self->m_session)
              self->m_native->seeking = false;
          });
        }];
  }
  if (!playing) {
    if (n.player.rate != 0.0f)
      [n.player pause];
    return;
  }
  // The engine's position moves in decoder-sized steps, so only a lasting gap bends the rate.
  const double nudge = std::abs(drift) > kNudgeSeconds ? std::clamp(drift * 0.5, -kMaxNudge, kMaxNudge) : 0.0;
  const float rate = float(1.0 - nudge);
  if (std::abs(n.player.rate - rate) > 0.001f)
    n.player.rate = rate;
}

void AppleMusicFairPlay::setSurface(QQuickItem *item) {
  if (item == m_surface)
    return;
  if (m_surface)
    disconnect(m_surface, nullptr, this, nullptr);
  m_surface = item;
  if (item)
    connect(item, &QQuickItem::windowChanged, this, &AppleMusicFairPlay::watchWindow);
  watchWindow(item ? item->window() : nullptr);
}

void AppleMusicFairPlay::watchWindow(QQuickWindow *window) {
  if (window != m_window) {
    disconnect(m_frameConnection);
    m_window = window;
    // Every frame, so the picture follows the item through drags, resizes and animations.
    if (window)
      m_frameConnection = connect(window, &QQuickWindow::afterAnimating, this, &AppleMusicFairPlay::place);
  }
  place();
}

void AppleMusicFairPlay::place() {
  Native &n = *m_native;
  NSView *view = m_window && m_window->handle() ? (__bridge NSView *)reinterpret_cast<void *>(m_window->winId()) : nil;
  CALayer *viewLayer = view.layer;
  const bool shown = m_ready && m_surface && m_surface->isVisible() && viewLayer.superlayer;
  [CATransaction begin];
  [CATransaction setDisableActions:YES];
  n.clip.hidden = !shown;
  if (shown) {
    CALayer *parent = viewLayer.superlayer;
    const NSUInteger below = [parent.sublayers indexOfObject:viewLayer];
    if (n.host.superlayer != parent || [parent.sublayers indexOfObject:n.host] + 1 != below) {
      [n.host removeFromSuperlayer];
      [parent insertSublayer:n.host below:viewLayer];
    }
    n.host.frame = viewLayer.frame;
    const QRectF window(0, 0, m_window->width(), m_window->height());
    const QRectF rect = m_surface->mapRectToScene(QRectF(0, 0, m_surface->width(), m_surface->height())) & window;
    // Qt's coordinates start top left; an unflipped layer starts bottom left.
    const double y = n.host.contentsAreFlipped ? rect.y() : n.host.bounds.size.height - rect.bottom();
    n.clip.frame = CGRectMake(rect.x(), y, rect.width(), rect.height());
    n.picture.frame = n.clip.bounds;
  }
  [CATransaction commit];
}

void AppleMusicFairPlay::fail(quint64 session, const QString &error, const QString &message) {
  if (session != m_session)
    return;
  if (!error.isEmpty())
    qWarning().noquote() << "AppleMusic: FairPlay picture failed |" << error;
  stop();
  emit failed(message.isEmpty() ? tr("This video could not be shown.") : message);
}
