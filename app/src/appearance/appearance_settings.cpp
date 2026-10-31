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
#include "ui_scaling.h"
#include <algorithm>
#include <cmath>

namespace {
double readNumber(QSettings &settings, const QString &key, double fallback, double low, double high) {
    bool ok = false;
    const double value = settings.value(key, fallback).toDouble(&ok);
    return ok && std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}

QColor readColor(QSettings &settings, const QString &key, const QColor &fallback) {
    const QColor color(settings.value(key).toString());
    return color.isValid() ? QColor(color.red(), color.green(), color.blue()) : fallback;
}

QColor mix(const QColor &a, const QColor &b, double t) {
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                            a.blueF() + (b.blueF() - a.blueF()) * t);
}

double luminance(const QColor &color) {
    return 0.2126 * color.redF() + 0.7152 * color.greenF() + 0.0722 * color.blueF();
}

const QColor defaultAccent{0x8c, 0xc4, 0xa0};
const QColor defaultPopup{0x1b, 0x20, 0x1c};

QVariantMap preset(const QString &name, const char *hex) {
    return {{QStringLiteral("name"), name}, {QStringLiteral("color"), QColor(QLatin1String(hex))}};
}
}
AppearanceSettings::AppearanceSettings(QObject *parent) : QObject(parent) {
    m_initialUiScale = m_uiScale = UiScaling::savedScale();
    m_settings.beginGroup(QStringLiteral("appearance"));
    m_immersive = m_settings.value(QStringLiteral("immersiveBackground"), false).toBool();
    m_showBitrate = m_settings.value(QStringLiteral("showBitrate"), false).toBool();
    m_welcomeSeen = m_settings.value(QStringLiteral("welcomeSeen"), false).toBool();
    m_lastSeenRelease = m_settings.value(QStringLiteral("lastSeenRelease")).toString();
    const QString layout = m_settings.value(QStringLiteral("layoutStyle")).toString();
    if (layout == QLatin1String("canopy")) m_layoutStyle = layout;
    m_speed = readNumber(m_settings, QStringLiteral("speed"), 1.38, 0.0, 5.0);
    m_intensity = readNumber(m_settings, QStringLiteral("intensity"), 0.92, 0.0, 1.0);
    m_saturation = readNumber(m_settings, QStringLiteral("saturation"), 1.24, 0.0, 3.0);
    m_brightness = readNumber(m_settings, QStringLiteral("brightness"), 1.0, 0.0, 2.0);
    m_fullscreenWindowed = m_settings.value(QStringLiteral("fullscreenWindowed"), false).toBool();
    m_fullscreenAutoHide = m_settings.value(QStringLiteral("fullscreenAutoHide"), true).toBool();
    m_fullscreenPulse = m_settings.value(QStringLiteral("fullscreenPulse"), true).toBool();
    m_fullscreenCanvasStage = m_settings.value(QStringLiteral("fullscreenCanvasStage"), false).toBool();
    m_lyricsFocusBlur = m_settings.value(QStringLiteral("lyricsFocusBlur"), true).toBool();
    m_lyricsDistanceFade = m_settings.value(QStringLiteral("lyricsDistanceFade"), true).toBool();
    m_lyricsStaggeredScroll = m_settings.value(QStringLiteral("lyricsStaggeredScroll"), true).toBool();
    m_artistVideoBackdrop = m_settings.value(QStringLiteral("artistVideoBackdrop"), false).toBool();
    m_animatedArtwork = m_settings.value(QStringLiteral("animatedArtworkEnabled"), true).toBool();
    m_animatedCollage = m_settings.value(QStringLiteral("animatedCollageEnabled"), false).toBool();
    m_artworkSource = m_settings.value(QStringLiteral("artworkSource"), QStringLiteral("apple_music")).toString();
    const QStringList savedMirrors = m_settings.value(QStringLiteral("mirrorOrder")).toStringList();
    if (m_settings.value(QStringLiteral("accentMode")).toString() == QLatin1String("artwork"))
        m_accentMode = QStringLiteral("artwork");
    m_accentColor = readColor(m_settings, QStringLiteral("accentColor"), defaultAccent);
    m_popupColor = readColor(m_settings, QStringLiteral("popupColor"), defaultPopup);
    m_popupTranslucency = readNumber(m_settings, QStringLiteral("popupTranslucency"), 0.6, 0.0, 0.8);
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
void AppearanceSettings::setLastSeenRelease(const QString &value) {
    if (value == m_lastSeenRelease) return;
    m_lastSeenRelease = value;
    m_settings.setValue(QStringLiteral("lastSeenRelease"), value);
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
void AppearanceSettings::setUiScale(double value) {
    if (!std::isfinite(value)) return;
    value = std::round(std::clamp(value, UiScaling::minimum, UiScaling::maximum) * 100) / 100;
    setNumber(QStringLiteral("uiScale"), value, m_uiScale, UiScaling::minimum, UiScaling::maximum);
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
void AppearanceSettings::setFullscreenWindowed(bool value) { setFlag(QStringLiteral("fullscreenWindowed"), value, m_fullscreenWindowed); }
void AppearanceSettings::setFullscreenAutoHide(bool value) { setFlag(QStringLiteral("fullscreenAutoHide"), value, m_fullscreenAutoHide); }
void AppearanceSettings::setFullscreenPulse(bool value) { setFlag(QStringLiteral("fullscreenPulse"), value, m_fullscreenPulse); }
void AppearanceSettings::setFullscreenCanvasStage(bool value) { setFlag(QStringLiteral("fullscreenCanvasStage"), value, m_fullscreenCanvasStage); }
void AppearanceSettings::setLyricsFocusBlur(bool value) { setFlag(QStringLiteral("lyricsFocusBlur"), value, m_lyricsFocusBlur); }
void AppearanceSettings::setLyricsDistanceFade(bool value) { setFlag(QStringLiteral("lyricsDistanceFade"), value, m_lyricsDistanceFade); }
void AppearanceSettings::setLyricsStaggeredScroll(bool value) { setFlag(QStringLiteral("lyricsStaggeredScroll"), value, m_lyricsStaggeredScroll); }
void AppearanceSettings::setArtistVideoBackdrop(bool value) { setFlag(QStringLiteral("artistVideoBackdrop"), value, m_artistVideoBackdrop); }

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

QColor AppearanceSettings::accent() const {
    return m_accentMode == QLatin1String("artwork") && m_artworkAccent.alpha() > 0 ? m_artworkAccent : m_accentColor;
}

// Lifted toward white so focus rings and accent text stay legible on the dark surfaces.
QColor AppearanceSettings::accentStrong() const { return mix(accent(), Qt::white, 0.35); }

QColor AppearanceSettings::onAccent() const {
    return luminance(accent()) > 0.45 ? QColor(0x10, 0x13, 0x10) : QColor(Qt::white);
}

void AppearanceSettings::setAccentMode(const QString &value) {
    if ((value != QLatin1String("custom") && value != QLatin1String("artwork")) || value == m_accentMode) return;
    m_accentMode = value;
    m_settings.setValue(QStringLiteral("accentMode"), value);
    emit themeChanged();
}

void AppearanceSettings::setAccentColor(const QColor &value) {
    if (!value.isValid()) return;
    const QColor opaque(value.red(), value.green(), value.blue());
    if (opaque == m_accentColor) return;
    m_accentColor = opaque;
    m_settings.setValue(QStringLiteral("accentColor"), opaque.name());
    emit themeChanged();
}

void AppearanceSettings::setArtworkAccent(const QColor &value) {
    if (value == m_artworkAccent) return;
    m_artworkAccent = value;
    if (m_accentMode == QLatin1String("artwork")) emit themeChanged();
}

void AppearanceSettings::setPopupColor(const QColor &value) {
    if (!value.isValid()) return;
    const QColor opaque(value.red(), value.green(), value.blue());
    if (opaque == m_popupColor) return;
    m_popupColor = opaque;
    m_settings.setValue(QStringLiteral("popupColor"), opaque.name());
    emit themeChanged();
}

void AppearanceSettings::setPopupTranslucency(double value) {
    if (!std::isfinite(value)) return;
    value = std::clamp(value, 0.0, 0.8);
    if (value == m_popupTranslucency) return;
    m_popupTranslucency = value;
    m_settings.setValue(QStringLiteral("popupTranslucency"), value);
    emit themeChanged();
}

QVariantList AppearanceSettings::accentPresets() const {
    return {preset(tr("Sage"), "#8cc4a0"),  preset(tr("Sky"), "#7fb6f0"),   preset(tr("Teal"), "#5fcfc4"),
            preset(tr("Lime"), "#a8d672"),  preset(tr("Sun"), "#e9c46a"),   preset(tr("Peach"), "#f0a35e"),
            preset(tr("Coral"), "#ef7b6b"), preset(tr("Rose"), "#f08fb5"),  preset(tr("Lilac"), "#b79cff"),
            preset(tr("Silver"), "#c3c8c4")};
}

QVariantList AppearanceSettings::popupPresets() const {
    return {preset(tr("Graphite"), "#1b201c"), preset(tr("Charcoal"), "#1b1b1d"), preset(tr("Forest"), "#1f3027"),
            preset(tr("Midnight"), "#1d2538"), preset(tr("Plum"), "#2d2134"),     preset(tr("Mocha"), "#32281f")};
}

void AppearanceSettings::reset() {
    setLayoutStyle(QStringLiteral("glade"));
    setSpeed(1.38);
    setIntensity(0.92);
    setSaturation(1.24);
    setBrightness(1.0);
    setFullscreenWindowed(false);
    setFullscreenAutoHide(true);
    setFullscreenPulse(true);
    setFullscreenCanvasStage(false);
    setLyricsFocusBlur(true);
    setLyricsDistanceFade(true);
    setLyricsStaggeredScroll(true);
    setArtistVideoBackdrop(false);
    setAnimatedArtworkEnabled(true);
    setAnimatedCollageEnabled(false);
    setArtworkSource(QStringLiteral("apple_music"));
    resetMirrorOrder();
}

void AppearanceSettings::resetColors() {
    setAccentMode(QStringLiteral("custom"));
    setAccentColor(defaultAccent);
    setPopupColor(defaultPopup);
    setPopupTranslucency(0.6);
}

