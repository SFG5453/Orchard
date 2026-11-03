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

// Apple Music for a Connect target: the controller sends catalog ids only when this desktop
// advertises a session, and an Apple song switches the music source so it can play.

#include "connect_service.h"

#include "auth/auth_manager.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"
#include "playback/playback_helpers.h"
#include "providers/qobuz/qobuz_service.h"

void ConnectService::setAppleMusic(AppleMusic *appleMusic, CatalogSource *source) {
  m_appleMusic = appleMusic;
  m_catalogSource = source;
  connect(appleMusic, &AppleMusic::changed, this, &ConnectService::refreshDevice);
  refreshDevice();
}

QJsonObject ConnectService::providerSessions() const {
  return {{QStringLiteral("youtube"), m_auth && m_auth->isSignedIn()},
          {QStringLiteral("qobuz"), m_qobuz && m_qobuz->connected()},
          {QStringLiteral("apple_music"), m_appleMusic && m_appleMusic->connected()}};
}

void ConnectService::preferAppleSource(const QVariantMap &track) {
  if (isAppleTrack(track) && m_appleMusic && m_appleMusic->connected() && m_catalogSource &&
      !m_catalogSource->apple())
    m_catalogSource->setSource(QStringLiteral("apple_music"));
}
