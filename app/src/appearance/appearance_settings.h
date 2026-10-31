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
#include <QColor>
#include <QObject>
#include <QSettings>

class AppearanceSettings final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool immersiveBackground READ immersiveBackground WRITE setImmersiveBackground NOTIFY changed)
    Q_PROPERTY(bool showBitrate READ showBitrate WRITE setShowBitrate NOTIFY changed)
    Q_PROPERTY(bool welcomeSeen READ welcomeSeen WRITE setWelcomeSeen NOTIFY changed)
    Q_PROPERTY(QString lastSeenRelease READ lastSeenRelease WRITE setLastSeenRelease NOTIFY changed)
    Q_PROPERTY(QString layoutStyle READ layoutStyle WRITE setLayoutStyle NOTIFY changed)
    Q_PROPERTY(double uiScale READ uiScale WRITE setUiScale NOTIFY changed)
    Q_PROPERTY(bool uiScaleRestartRequired READ uiScaleRestartRequired NOTIFY changed)
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY changed)
    Q_PROPERTY(double intensity READ intensity WRITE setIntensity NOTIFY changed)
    Q_PROPERTY(double saturation READ saturation WRITE setSaturation NOTIFY changed)
    Q_PROPERTY(double brightness READ brightness WRITE setBrightness NOTIFY changed)
    Q_PROPERTY(bool fullscreenWindowed READ fullscreenWindowed WRITE setFullscreenWindowed NOTIFY changed)
    Q_PROPERTY(bool fullscreenAutoHide READ fullscreenAutoHide WRITE setFullscreenAutoHide NOTIFY changed)
    Q_PROPERTY(bool fullscreenPulse READ fullscreenPulse WRITE setFullscreenPulse NOTIFY changed)
    Q_PROPERTY(bool fullscreenCanvasStage READ fullscreenCanvasStage WRITE setFullscreenCanvasStage NOTIFY changed)
    Q_PROPERTY(bool lyricsFocusBlur READ lyricsFocusBlur WRITE setLyricsFocusBlur NOTIFY changed)
    Q_PROPERTY(bool lyricsDistanceFade READ lyricsDistanceFade WRITE setLyricsDistanceFade NOTIFY changed)
    Q_PROPERTY(bool lyricsStaggeredScroll READ lyricsStaggeredScroll WRITE setLyricsStaggeredScroll NOTIFY changed)
    Q_PROPERTY(bool artistVideoBackdrop READ artistVideoBackdrop WRITE setArtistVideoBackdrop NOTIFY changed)
    Q_PROPERTY(bool animatedArtworkEnabled READ animatedArtworkEnabled WRITE setAnimatedArtworkEnabled NOTIFY changed)
    Q_PROPERTY(bool animatedCollageEnabled READ animatedCollageEnabled WRITE setAnimatedCollageEnabled NOTIFY changed)
    Q_PROPERTY(QString artworkSource READ artworkSource WRITE setArtworkSource NOTIFY changed)
    Q_PROPERTY(QStringList mirrorOrder READ mirrorOrder WRITE setMirrorOrder NOTIFY changed)
    Q_PROPERTY(QVariantList availableSources READ availableSources CONSTANT)
    Q_PROPERTY(QVariantList availableMirrors READ availableMirrors NOTIFY changed)
    Q_PROPERTY(QString accentMode READ accentMode WRITE setAccentMode NOTIFY themeChanged)
    Q_PROPERTY(QColor accentColor READ accentColor WRITE setAccentColor NOTIFY themeChanged)
    Q_PROPERTY(QColor artworkAccent READ artworkAccent WRITE setArtworkAccent NOTIFY themeChanged)
    Q_PROPERTY(QColor accent READ accent NOTIFY themeChanged)
    Q_PROPERTY(QColor accentStrong READ accentStrong NOTIFY themeChanged)
    Q_PROPERTY(QColor onAccent READ onAccent NOTIFY themeChanged)
    Q_PROPERTY(QColor popupColor READ popupColor WRITE setPopupColor NOTIFY themeChanged)
    Q_PROPERTY(double popupTranslucency READ popupTranslucency WRITE setPopupTranslucency NOTIFY themeChanged)
    Q_PROPERTY(QVariantList accentPresets READ accentPresets CONSTANT)
    Q_PROPERTY(QVariantList popupPresets READ popupPresets CONSTANT)
public:
    explicit AppearanceSettings(QObject *parent = nullptr);
    bool immersiveBackground() const { return m_immersive; }
    bool showBitrate() const { return m_showBitrate; }
    bool welcomeSeen() const { return m_welcomeSeen; }
    QString lastSeenRelease() const { return m_lastSeenRelease; }
    QString layoutStyle() const { return m_layoutStyle; }
    double uiScale() const { return m_uiScale; }
    bool uiScaleRestartRequired() const { return m_uiScale != m_initialUiScale; }
    double speed() const { return m_speed; }
    double intensity() const { return m_intensity; }
    double saturation() const { return m_saturation; }
    double brightness() const { return m_brightness; }
    bool fullscreenWindowed() const { return m_fullscreenWindowed; }
    bool fullscreenAutoHide() const { return m_fullscreenAutoHide; }
    bool fullscreenPulse() const { return m_fullscreenPulse; }
    bool fullscreenCanvasStage() const { return m_fullscreenCanvasStage; }
    bool lyricsFocusBlur() const { return m_lyricsFocusBlur; }
    bool lyricsDistanceFade() const { return m_lyricsDistanceFade; }
    bool lyricsStaggeredScroll() const { return m_lyricsStaggeredScroll; }
    bool artistVideoBackdrop() const { return m_artistVideoBackdrop; }
    bool animatedArtworkEnabled() const { return m_animatedArtwork; }
    bool animatedCollageEnabled() const { return m_animatedCollage; }
    QString artworkSource() const { return m_artworkSource; }
    QStringList mirrorOrder() const { return m_mirrorOrder; }
    QVariantList availableSources() const;
    QVariantList availableMirrors() const;
    QString accentMode() const { return m_accentMode; }
    QColor accentColor() const { return m_accentColor; }
    QColor artworkAccent() const { return m_artworkAccent; }
    QColor accent() const;
    QColor accentStrong() const;
    QColor onAccent() const;
    QColor popupColor() const { return m_popupColor; }
    double popupTranslucency() const { return m_popupTranslucency; }
    QVariantList accentPresets() const;
    QVariantList popupPresets() const;

    void setImmersiveBackground(bool value);
    void setShowBitrate(bool value);
    void setWelcomeSeen(bool value);
    void setLastSeenRelease(const QString &value);
    void setLayoutStyle(const QString &value);
    void setUiScale(double value);
    void setSpeed(double value);
    void setIntensity(double value);
    void setSaturation(double value);
    void setBrightness(double value);
    void setFullscreenWindowed(bool value);
    void setFullscreenAutoHide(bool value);
    void setFullscreenPulse(bool value);
    void setFullscreenCanvasStage(bool value);
    void setLyricsFocusBlur(bool value);
    void setLyricsDistanceFade(bool value);
    void setLyricsStaggeredScroll(bool value);
    void setArtistVideoBackdrop(bool value);
    void setAnimatedArtworkEnabled(bool value);
    void setAnimatedCollageEnabled(bool value);
    void setArtworkSource(const QString &value);
    void setMirrorOrder(const QStringList &value);
    void setAccentMode(const QString &value);
    void setAccentColor(const QColor &value);
    void setArtworkAccent(const QColor &value);
    void setPopupColor(const QColor &value);
    void setPopupTranslucency(double value);
    Q_INVOKABLE void moveMirrorUp(int index);
    Q_INVOKABLE void moveMirrorDown(int index);
    Q_INVOKABLE void resetMirrorOrder();
    Q_INVOKABLE void reset();
    Q_INVOKABLE void resetColors();
signals:
    void changed();
    void themeChanged();
private:
    void setFlag(const QString &key, bool value, bool &field);
    void setNumber(const QString &key, double value, double &field, double low, double high);
    QStringList sanitizeMirrorOrder(const QStringList &order) const;

    QSettings m_settings;
    bool m_immersive{false};
    // Because sometimes you just need proof that those 128 kbps Opus bytes are truly exquisite.
    bool m_showBitrate{false};
    // Set once the post-login welcome has been shown, so it greets people exactly once.
    bool m_welcomeSeen{false};
    // Newest bundled release the person has opened; drives the "New" marks in release notes.
    QString m_lastSeenRelease;
    // "glade" floats the player at the bottom, "canopy" docks it beside search at the top.
    QString m_layoutStyle{QStringLiteral("glade")};
    double m_uiScale{1.0};
    double m_initialUiScale{1.0};
    double m_speed{1.38};
    double m_intensity{0.92};
    double m_saturation{1.24};
    // Sunglasses optional: 0 is a power outage, 2 is staring directly into the album art.
    double m_brightness{1.0};
    bool m_fullscreenWindowed{false};
    bool m_fullscreenAutoHide{true};
    bool m_fullscreenPulse{true};
    bool m_fullscreenCanvasStage{false};
    bool m_lyricsFocusBlur{true};
    bool m_lyricsDistanceFade{true};
    bool m_lyricsStaggeredScroll{true};
    // Plays one of the artist's music videos, muted, behind the artist page hero.
    bool m_artistVideoBackdrop{false};
    // Moving album art: when a still image simply isn't consuming enough of your monthly data plan.
    bool m_animatedArtwork{true};
    // Four videos at once: off by default, because your GPU didn't sign up for a lava lamp.
    bool m_animatedCollage{false};
    QString m_artworkSource{QStringLiteral("apple_music")};
    // Mirror order: because if one mirror stumbles, the other one better catch the falling MP4.
    QStringList m_mirrorOrder{QStringLiteral("m8tec"), QStringLiteral("boidu"), QStringLiteral("spotify")};
    // "custom" uses m_accentColor; "artwork" follows the playing cover and falls back to it.
    QString m_accentMode{QStringLiteral("custom")};
    QColor m_accentColor{0x8c, 0xc4, 0xa0};
    // Pushed by the player bar from the cover palette; never saved.
    QColor m_artworkAccent;
    QColor m_popupColor{0x1b, 0x20, 0x1c};
    // Only frosted popups use this; solid menus stay opaque so text over them stays readable.
    double m_popupTranslucency{0.6};
};
