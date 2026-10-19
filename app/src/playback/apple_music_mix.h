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

#include "audio_stream_proxy.h"

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QVariantMap>

class AdaptiveMixController;
class AuthManager;
class YouTubeProvider;

// Adaptive mix for a pair of Apple Music songs. Apple's audio stays in the web player, so
// the pair is planned and rendered from the songs' YouTube Music uploads; the page plays
// that render between its two Apple players.
class AppleMusicMix final : public QObject {
  Q_OBJECT

public:
  enum class State { Idle, Working, Ready, Standard, Boundary, NoMatch };

  AppleMusicMix(YouTubeProvider *provider, AuthManager *auth, AdaptiveMixController *mix,
                QObject *parent = nullptr);

  // Starts or continues work on this pair; a different pair drops the previous one. Matching
  // starts at once; streams are resolved and analysed once `analyseNow` is set.
  void prepare(const QVariantMap &current, const QVariantMap &next, double position, int fadeSeconds,
               bool albumSequential, bool analyseNow);
  void clear();
  State state() const;
  // Render, timing and reference clips for the page; empty unless Ready.
  QJsonObject plan() const;
  // The planner request for the page's own scan, for pairs without a match.
  QJsonObject scanRequest() const;

signals:
  // state() moved on.
  void changed();

private:
  static QString pairKey(const QVariantMap &current, const QVariantMap &next);
  void match(int side);
  void resolve();
  void analyse();
  void receive(quint64 request, const QJsonValue &result);
  void requestFailed(quint64 request, const QString &message);
  void giveUp(const QString &message, bool noMatch);

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  AdaptiveMixController *m_mix;
  // Apple song id -> YouTube video id; empty when nothing matched.
  QHash<QString, QString> m_matches;
  QString m_pair;
  QVariantMap m_songs[2];
  QString m_videos[2];
  QJsonObject m_streams[2];
  quint64 m_searches[2]{0, 0};
  quint64 m_resolves[2]{0, 0};
  AudioStreamProxy m_proxies[2];
  double m_position{0.0};
  int m_fadeSeconds{6};
  bool m_albumSequential{false};
  bool m_analyse{false};
  bool m_noMatch{false};
  bool m_failed{false};
};
