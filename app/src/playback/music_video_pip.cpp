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

#include "music_video_controller.h"

#include <QGuiApplication>
#include <QScreen>
#include <QSettings>
#include <QVideoSink>

namespace {

const QString kPipGeometryKey = QStringLiteral("musicVideo/pipGeometry");
const QString kMiniCornerKey = QStringLiteral("musicVideo/miniCorner");
constexpr QSize kPipDefaultSize(384, 216);
constexpr QSize kPipMinimumSize(240, 135);
constexpr int kPipMargin = 24;

} // namespace

void MusicVideoController::setPip(bool enabled) {
  enabled = enabled && m_open;
  if (enabled == m_pip)
    return;
  m_pip = enabled;
  emit changed();
}

void MusicVideoController::loadPipSettings() {
  m_miniCorner = qBound(0, QSettings().value(kMiniCornerKey, m_miniCorner).toInt(), 3);
}

void MusicVideoController::setMini(bool enabled) {
  enabled = enabled && m_open;
  if (enabled == m_mini)
    return;
  m_mini = enabled;
  emit changed();
}

void MusicVideoController::setMiniCorner(int corner) {
  corner = qBound(0, corner, 3);
  if (corner == m_miniCorner)
    return;
  m_miniCorner = corner;
  QSettings().setValue(kMiniCornerKey, corner);
  emit changed();
}

void MusicVideoController::releaseFrames(QObject *sink) {
  if (sink && sink == m_sink)
    watchFrames(nullptr);
}

QRect MusicVideoController::pipGeometry() const {
  const QRect saved = QSettings().value(kPipGeometryKey).toRect();
  QScreen *screen = saved.isValid() ? QGuiApplication::screenAt(saved.center()) : nullptr;
  if (!screen)
    screen = QGuiApplication::primaryScreen();
  if (!screen)
    return {QPoint(), kPipDefaultSize};
  const QRect area = screen->availableGeometry();
  QSize size = saved.isValid() ? saved.size() : kPipDefaultSize;
  size = size.expandedTo(kPipMinimumSize).boundedTo(area.size());
  // A saved spot off every screen, e.g. after unplugging a monitor, falls back to the bottom right corner.
  QPoint at = QGuiApplication::screenAt(saved.center()) ? saved.topLeft()
                                                         : area.bottomRight() - QPoint(size.width(), size.height()) -
                                                               QPoint(kPipMargin, kPipMargin);
  at.setX(qBound(area.left(), at.x(), area.right() - size.width() + 1));
  at.setY(qBound(area.top(), at.y(), area.bottom() - size.height() + 1));
  return {at, size};
}

void MusicVideoController::savePipGeometry(const QRect &geometry) {
  if (geometry.width() >= kPipMinimumSize.width() && geometry.height() >= kPipMinimumSize.height())
    QSettings().setValue(kPipGeometryKey, geometry);
}
