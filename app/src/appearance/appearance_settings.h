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
#include <QObject>
#include <QSettings>

class AppearanceSettings final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool immersiveBackground READ immersiveBackground WRITE setImmersiveBackground NOTIFY changed)
    Q_PROPERTY(bool showBitrate READ showBitrate WRITE setShowBitrate NOTIFY changed)
    Q_PROPERTY(bool welcomeSeen READ welcomeSeen WRITE setWelcomeSeen NOTIFY changed)
    Q_PROPERTY(QString layoutStyle READ layoutStyle WRITE setLayoutStyle NOTIFY changed)
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY changed)
    Q_PROPERTY(double intensity READ intensity WRITE setIntensity NOTIFY changed)
    Q_PROPERTY(double saturation READ saturation WRITE setSaturation NOTIFY changed)
    Q_PROPERTY(double brightness READ brightness WRITE setBrightness NOTIFY changed)
    Q_PROPERTY(bool animatedArtworkEnabled READ animatedArtworkEnabled WRITE setAnimatedArtworkEnabled NOTIFY changed)
    Q_PROPERTY(bool animatedCollageEnabled READ animatedCollageEnabled WRITE setAnimatedCollageEnabled NOTIFY changed)
    Q_PROPERTY(QString artworkSource READ artworkSource WRITE setArtworkSource NOTIFY changed)
    Q_PROPERTY(QStringList mirrorOrder READ mirrorOrder WRITE setMirrorOrder NOTIFY changed)
    Q_PROPERTY(QVariantList availableSources READ availableSources CONSTANT)
    Q_PROPERTY(QVariantList availableMirrors READ availableMirrors NOTIFY changed)
public:
    explicit AppearanceSettings(QObject *parent = nullptr);
    bool immersiveBackground() const { return m_immersive; }
    bool showBitrate() const { return m_showBitrate; }
    bool welcomeSeen() const { return m_welcomeSeen; }
    QString layoutStyle() const { return m_layoutStyle; }
    double speed() const { return m_speed; }
    double intensity() const { return m_intensity; }
    double saturation() const { return m_saturation; }
    double brightness() const { return m_brightness; }
    bool animatedArtworkEnabled() const { return m_animatedArtwork; }
    bool animatedCollageEnabled() const { return m_animatedCollage; }
    QString artworkSource() const { return m_artworkSource; }
    QStringList mirrorOrder() const { return m_mirrorOrder; }
    QVariantList availableSources() const;
    QVariantList availableMirrors() const;

    void setImmersiveBackground(bool value);
    void setShowBitrate(bool value);
    void setWelcomeSeen(bool value);
    void setLayoutStyle(const QString &value);
    void setSpeed(double value);
    void setIntensity(double value);
    void setSaturation(double value);
    void setBrightness(double value);
    void setAnimatedArtworkEnabled(bool value);
    void setAnimatedCollageEnabled(bool value);
    void setArtworkSource(const QString &value);
    void setMirrorOrder(const QStringList &value);
    Q_INVOKABLE void moveMirrorUp(int index);
    Q_INVOKABLE void moveMirrorDown(int index);
    Q_INVOKABLE void resetMirrorOrder();
    Q_INVOKABLE void reset();
signals:
    void changed();
private:
    void setNumber(const QString &key, double value, double &field, double low, double high);
    QStringList sanitizeMirrorOrder(const QStringList &order) const;

    QSettings m_settings;
    bool m_immersive{false};
    // Because sometimes you just need proof that those 128 kbps Opus bytes are truly exquisite.
    bool m_showBitrate{false};
    // Set once the post-login welcome has been shown, so it greets people exactly once.
    bool m_welcomeSeen{false};
    // "glade" floats the player at the bottom, "canopy" docks it beside search at the top.
    QString m_layoutStyle{QStringLiteral("glade")};
    double m_speed{1.38};
    double m_intensity{0.92};
    double m_saturation{1.24};
    // Sunglasses optional: 0 is a power outage, 2 is staring directly into the album art.
    double m_brightness{1.0};
    // Moving album art: when a still image simply isn't consuming enough of your monthly data plan.
    bool m_animatedArtwork{true};
    // Four videos at once: off by default, because your GPU didn't sign up for a lava lamp.
    bool m_animatedCollage{false};
    QString m_artworkSource{QStringLiteral("apple_music")};
    // Mirror order: because if one mirror stumbles, the other one better catch the falling MP4.
    QStringList m_mirrorOrder{QStringLiteral("m8tec"), QStringLiteral("boidu"), QStringLiteral("spotify")};
};
