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

#include "appearance_settings.h"
#include <algorithm>
#include <cmath>

namespace {
double readNumber(QSettings &settings, const QString &key, double fallback, double low, double high) {
    bool ok = false;
    const double value = settings.value(key, fallback).toDouble(&ok);
    return ok && std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}
}
AppearanceSettings::AppearanceSettings(QObject *parent) : QObject(parent) {
    m_settings.beginGroup(QStringLiteral("appearance"));
    m_immersive = m_settings.value(QStringLiteral("immersiveBackground"), false).toBool();
    m_showBitrate = m_settings.value(QStringLiteral("showBitrate"), false).toBool();
    m_welcomeSeen = m_settings.value(QStringLiteral("welcomeSeen"), false).toBool();
    const QString layout = m_settings.value(QStringLiteral("layoutStyle")).toString();
    if (layout == QLatin1String("canopy")) m_layoutStyle = layout;
    m_speed = readNumber(m_settings, QStringLiteral("speed"), 1.38, 0.0, 5.0);
    m_intensity = readNumber(m_settings, QStringLiteral("intensity"), 0.92, 0.0, 1.0);
    m_saturation = readNumber(m_settings, QStringLiteral("saturation"), 1.24, 0.0, 3.0);
    m_brightness = readNumber(m_settings, QStringLiteral("brightness"), 1.0, 0.0, 2.0);
    m_fullscreenAutoHide = m_settings.value(QStringLiteral("fullscreenAutoHide"), true).toBool();
    m_fullscreenPulse = m_settings.value(QStringLiteral("fullscreenPulse"), true).toBool();
    m_animatedArtwork = m_settings.value(QStringLiteral("animatedArtworkEnabled"), true).toBool();
    m_animatedCollage = m_settings.value(QStringLiteral("animatedCollageEnabled"), false).toBool();
    m_artworkSource = m_settings.value(QStringLiteral("artworkSource"), QStringLiteral("apple_music")).toString();
    const QStringList savedMirrors = m_settings.value(QStringLiteral("mirrorOrder")).toStringList();
    m_mirrorOrder = sanitizeMirrorOrder(savedMirrors.isEmpty() ? QStringList{QStringLiteral("m8tec"), QStringLiteral("boidu"), QStringLiteral("spotify")} : savedMirrors);
}
void AppearanceSettings::setImmersiveBackground(bool value) {
    if (value == m_immersive) return;
    m_immersive = value;
    m_settings.setValue(QStringLiteral("immersiveBackground"), value);
    emit changed();
}
void AppearanceSettings::setShowBitrate(bool value) {
    if (value == m_showBitrate) return;
    m_showBitrate = value;
    m_settings.setValue(QStringLiteral("showBitrate"), value);
    emit changed();
}
void AppearanceSettings::setWelcomeSeen(bool value) {
    if (value == m_welcomeSeen) return;
    m_welcomeSeen = value;
    m_settings.setValue(QStringLiteral("welcomeSeen"), value);
    emit changed();
}
void AppearanceSettings::setLayoutStyle(const QString &value) {
    if ((value != QLatin1String("glade") && value != QLatin1String("canopy")) || value == m_layoutStyle) return;
    m_layoutStyle = value;
    m_settings.setValue(QStringLiteral("layoutStyle"), value);
    emit changed();
}
void AppearanceSettings::setNumber(const QString &key, double value, double &field, double low, double high) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, low, high);
    if (value == field) return;
    field = value;
    m_settings.setValue(key, value);
    emit changed();
}
void AppearanceSettings::setSpeed(double value) { setNumber(QStringLiteral("speed"), value, m_speed, 0.0, 5.0); }
void AppearanceSettings::setIntensity(double value) { setNumber(QStringLiteral("intensity"), value, m_intensity, 0.0, 1.0); }
void AppearanceSettings::setSaturation(double value) { setNumber(QStringLiteral("saturation"), value, m_saturation, 0.0, 3.0); }
void AppearanceSettings::setBrightness(double value) { setNumber(QStringLiteral("brightness"), value, m_brightness, 0.0, 2.0); }

void AppearanceSettings::setFlag(const QString &key, bool value, bool &field) {
    if (value == field) return;
    field = value;
    m_settings.setValue(key, value);
    emit changed();
}
void AppearanceSettings::setFullscreenAutoHide(bool value) { setFlag(QStringLiteral("fullscreenAutoHide"), value, m_fullscreenAutoHide); }
void AppearanceSettings::setFullscreenPulse(bool value) { setFlag(QStringLiteral("fullscreenPulse"), value, m_fullscreenPulse); }

void AppearanceSettings::setAnimatedArtworkEnabled(bool value) {
    if (value == m_animatedArtwork) return;
    m_animatedArtwork = value;
    m_settings.setValue(QStringLiteral("animatedArtworkEnabled"), value);
    emit changed();
}

void AppearanceSettings::setAnimatedCollageEnabled(bool value) {
    if (value == m_animatedCollage) return;
    m_animatedCollage = value;
    m_settings.setValue(QStringLiteral("animatedCollageEnabled"), value);
    emit changed();
}

void AppearanceSettings::setArtworkSource(const QString &value) {
    const QString cleaned = value.trimmed().isEmpty() ? QStringLiteral("apple_music") : value.trimmed();
    if (cleaned == m_artworkSource) return;
    m_artworkSource = cleaned;
    m_settings.setValue(QStringLiteral("artworkSource"), cleaned);
    emit changed();
}

void AppearanceSettings::setMirrorOrder(const QStringList &value) {
    const QStringList sanitized = sanitizeMirrorOrder(value);
    if (sanitized == m_mirrorOrder) return;
    m_mirrorOrder = sanitized;
    m_settings.setValue(QStringLiteral("mirrorOrder"), sanitized);
    emit changed();
}

void AppearanceSettings::moveMirrorUp(int index) {
    if (index <= 0 || index >= m_mirrorOrder.size()) return;
    m_mirrorOrder.swapItemsAt(index, index - 1);
    m_settings.setValue(QStringLiteral("mirrorOrder"), m_mirrorOrder);
    emit changed();
}

void AppearanceSettings::moveMirrorDown(int index) {
    if (index < 0 || index >= m_mirrorOrder.size() - 1) return;
    m_mirrorOrder.swapItemsAt(index, index + 1);
    m_settings.setValue(QStringLiteral("mirrorOrder"), m_mirrorOrder);
    emit changed();
}

void AppearanceSettings::resetMirrorOrder() {
    setMirrorOrder({QStringLiteral("m8tec"), QStringLiteral("boidu"), QStringLiteral("spotify")});
}

QStringList AppearanceSettings::sanitizeMirrorOrder(const QStringList &order) const {
    const QStringList validMirrors = {QStringLiteral("m8tec"), QStringLiteral("boidu"), QStringLiteral("spotify")};
    QStringList sanitized;
    for (const QString &mirror : order) {
        const QString trimmed = mirror.trimmed().toLower();
        if (validMirrors.contains(trimmed) && !sanitized.contains(trimmed)) {
            sanitized.append(trimmed);
        }
    }
    // Make sure every valid mirror is invited to the party. If someone deleted a mirror from config, invite it back.
    for (const QString &mirror : validMirrors) {
        if (!sanitized.contains(mirror)) {
            sanitized.append(mirror);
        }
    }
    return sanitized;
}

QVariantList AppearanceSettings::availableSources() const {
    return QVariantList{
        QVariantMap{
            {QStringLiteral("id"), QStringLiteral("apple_music")},
            {QStringLiteral("name"), QStringLiteral("Apple Music")},
            {QStringLiteral("description"), QStringLiteral("High-resolution animated cover artwork from Apple Music")}
        }
    };
}

QVariantList AppearanceSettings::availableMirrors() const {
    QVariantList list;
    for (int i = 0; i < m_mirrorOrder.size(); ++i) {
        const QString id = m_mirrorOrder.at(i);
        QVariantMap map;
        map.insert(QStringLiteral("id"), id);
        map.insert(QStringLiteral("index"), i);
        if (id == QStringLiteral("m8tec")) {
            map.insert(QStringLiteral("name"), QStringLiteral("m8tec"));
            map.insert(QStringLiteral("host"), QStringLiteral("artwork.m8tec.top"));
            map.insert(QStringLiteral("description"), QStringLiteral("Primary community animated artwork mirror"));
        } else if (id == QStringLiteral("boidu")) {
            map.insert(QStringLiteral("name"), QStringLiteral("boidu"));
            map.insert(QStringLiteral("host"), QStringLiteral("artwork.boidu.dev"));
            map.insert(QStringLiteral("description"), QStringLiteral("Boidu fast-response motion artwork mirror"));
        } else if (id == QStringLiteral("spotify")) {
            map.insert(QStringLiteral("name"), QStringLiteral("Spotify Canvas"));
            map.insert(QStringLiteral("host"), QStringLiteral("spclient.wg.spotify.com"));
            map.insert(QStringLiteral("description"), QStringLiteral("Looping song videos; needs Spotify connected in Integrations"));
        } else {
            map.insert(QStringLiteral("name"), id);
            map.insert(QStringLiteral("host"), id);
            map.insert(QStringLiteral("description"), QStringLiteral("Custom artwork mirror"));
        }
        list.append(map);
    }
    return list;
}

void AppearanceSettings::reset() {
    setLayoutStyle(QStringLiteral("glade"));
    setSpeed(1.38);
    setIntensity(0.92);
    setSaturation(1.24);
    setBrightness(1.0);
    setFullscreenAutoHide(true);
    setFullscreenPulse(true);
    setAnimatedArtworkEnabled(true);
    setAnimatedCollageEnabled(false);
    setArtworkSource(QStringLiteral("apple_music"));
    resetMirrorOrder();
}

