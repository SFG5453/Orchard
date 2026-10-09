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

#include "audio_engine_output.h"
#include "audio_pipeline.h"

#include <QSettings>

#include <algorithm>
#include <array>
#include <cmath>

namespace {
struct Preset {
  const char *name;
  std::array<float, 10> gains;
};

constexpr std::array<Preset, 7> kPresets = {{
    {"flat", {0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
    {"bass", {6, 5, 4, 2, 0, -1, -1, 0, 1, 2}},
    {"electronic", {5, 4, 1, 0, -2, 1, 2, 3, 4, 4}},
    {"rock", {4, 3, 2, 0, -1, 1, 3, 4, 4, 3}},
    {"vocal", {-3, -2, -1, 0, 2, 4, 5, 3, 1, 0}},
    {"acoustic", {2, 2, 1, 0, 2, 3, 3, 2, 2, 1}},
    {"bright", {-2, -1, 0, 0, 1, 2, 3, 4, 5, 5}},
}};

double finiteClamp(double value, double minimum, double maximum, double fallback) {
  return std::isfinite(value) ? std::clamp(value, minimum, maximum) : fallback;
}
} // namespace

QString AudioEngineOutput::activePreset() const {
  for (const auto &preset : kPresets) {
    bool matches = true;
    for (int index = 0; index < 10; ++index) {
      if (std::abs(m_gains[index] - preset.gains[index]) >= 0.05f) {
        matches = false;
        break;
      }
    }
    if (matches)
      return QString::fromLatin1(preset.name);
  }
  return QStringLiteral("custom");
}

void AudioEngineOutput::setEnabled(bool enabled) {
  if (m_enabled == enabled)
    return;
  m_enabled = enabled;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setAutoEqEnabled(bool enabled) {
  if (m_autoEqEnabled == enabled && (!enabled || !m_eqEnabled))
    return;
  m_autoEqEnabled = enabled;
  if (enabled) {
    m_enabled = true;
    m_eqEnabled = false;
  }
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setEqEnabled(bool enabled) {
  if (m_eqEnabled == enabled && (!enabled || !m_autoEqEnabled))
    return;
  m_eqEnabled = enabled;
  if (enabled) {
    m_enabled = true;
    m_autoEqEnabled = false;
  }
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setNormalizationEnabled(bool enabled) {
  if (m_normalizationEnabled == enabled)
    return;
  m_normalizationEnabled = enabled;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setPreampDb(double value) {
  value = finiteClamp(value, -12.0, 6.0, 0.0);
  if (qFuzzyCompare(m_preampDb + 1.0, value + 1.0))
    return;
  m_preampDb = value;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setOutputGainDb(double value) {
  value = finiteClamp(value, -24.0, 6.0, 0.0);
  if (qFuzzyCompare(m_outputGainDb + 25.0, value + 25.0))
    return;
  m_outputGainDb = value;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setQ(double value) {
  value = finiteClamp(value, 0.4, 2.4, 1.1);
  if (qFuzzyCompare(m_q, value))
    return;
  m_q = value;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setBalance(double value) {
  value = finiteClamp(value, -1.0, 1.0, 0.0);
  if (qFuzzyCompare(m_balance + 2.0, value + 2.0))
    return;
  m_balance = value;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setTrackGainDb(double value) {
  if (m_activeTrackId.isEmpty())
    return;
  value = finiteClamp(value, -12.0, 12.0, 0.0);
  if (qFuzzyCompare(m_trackGainDb + 13.0, value + 13.0))
    return;
  m_trackGainDb = value;
  if (std::abs(value) < 0.05)
    m_trackGains.remove(m_activeTrackId);
  else
    m_trackGains.insert(m_activeTrackId, value);
  QSettings().setValue(QStringLiteral("playback/audioEngine/trackGains"), m_trackGains);
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::setOutputDeviceId(const QString &deviceId) {
  const QString target = deviceId.isEmpty() ? QStringLiteral("default") : deviceId;
  if (m_outputDeviceId == target)
    return;
  m_outputDeviceId = target;
  QSettings().setValue(QStringLiteral("playback/audioEngine/outputDeviceId"), target);
  ensureAudioBackend();
  pushDevice();
  emit outputDeviceChanged();
}

void AudioEngineOutput::setBandGain(int index, double gainDb) {
  if (index < 0 || index >= 10)
    return;
  const float value = static_cast<float>(finiteClamp(gainDb, -12.0, 12.0, 0.0));
  if (std::abs(m_gains[index] - value) < 0.001f)
    return;
  m_gains[index] = value;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::applyPreset(const QString &name) {
  const auto it = std::find_if(kPresets.begin(), kPresets.end(), [&name](const Preset &preset) {
    return name == QString::fromLatin1(preset.name);
  });
  if (it == kPresets.end())
    return;
  std::copy(it->gains.begin(), it->gains.end(), m_gains);
  m_enabled = true;
  m_autoEqEnabled = false;
  m_eqEnabled = true;
  persistConfiguration();
  applyConfiguration();
  emit configurationChanged();
}

void AudioEngineOutput::resetEngine() {
  m_enabled = true;
  m_autoEqEnabled = false;
  m_eqEnabled = false;
  m_normalizationEnabled = false;
  std::fill(std::begin(m_gains), std::end(m_gains), 0.0f);
  m_preampDb = 0.0;
  m_outputGainDb = 0.0;
  m_q = 1.1;
  m_balance = 0.0;
  m_trackGains.clear();
  m_trackGainDb = 0.0;
  QSettings settings;
  settings.remove(QStringLiteral("playback/audioEngine"));
  m_outputDeviceId = QStringLiteral("default");
  persistConfiguration();
  pushDevice();
  applyConfiguration();
  flush();
  emit outputDeviceChanged();
  emit configurationChanged();
}

void AudioEngineOutput::loadSettings() {
  QSettings settings;
  settings.beginGroup(QStringLiteral("playback/audioEngine"));
  m_enabled = settings.value(QStringLiteral("enabled"), true).toBool();
  m_autoEqEnabled = settings.value(QStringLiteral("autoEqEnabled"), false).toBool();
  m_eqEnabled = settings.value(QStringLiteral("eqEnabled"), false).toBool();
  if (m_autoEqEnabled)
    m_eqEnabled = false;
  const QVariantList storedGains = settings.value(QStringLiteral("gains")).toList();
  for (int index = 0; index < 10; ++index)
    m_gains[index] = static_cast<float>(finiteClamp(storedGains.value(index, 0.0).toDouble(), -12.0, 12.0, 0.0));
  m_preampDb = finiteClamp(settings.value(QStringLiteral("preampDb"), 0.0).toDouble(), -12.0, 6.0, 0.0);
  m_outputGainDb = finiteClamp(settings.value(QStringLiteral("outputGainDb"), 0.0).toDouble(), -24.0, 6.0, 0.0);
  m_q = finiteClamp(settings.value(QStringLiteral("q"), 1.1).toDouble(), 0.4, 2.4, 1.1);
  m_balance = finiteClamp(settings.value(QStringLiteral("balance"), 0.0).toDouble(), -1.0, 1.0, 0.0);
  m_normalizationEnabled = settings.value(QStringLiteral("normalizationEnabled"), false).toBool();
  m_outputDeviceId = settings.value(QStringLiteral("outputDeviceId"), QStringLiteral("default")).toString();
  m_trackGains = settings.value(QStringLiteral("trackGains")).toMap();
  settings.endGroup();
}

void AudioEngineOutput::persistConfiguration() {
  QSettings settings;
  settings.beginGroup(QStringLiteral("playback/audioEngine"));
  settings.setValue(QStringLiteral("enabled"), m_enabled);
  settings.setValue(QStringLiteral("autoEqEnabled"), m_autoEqEnabled);
  settings.setValue(QStringLiteral("eqEnabled"), m_eqEnabled);
  settings.setValue(QStringLiteral("gains"), gains());
  settings.setValue(QStringLiteral("preampDb"), m_preampDb);
  settings.setValue(QStringLiteral("outputGainDb"), m_outputGainDb);
  settings.setValue(QStringLiteral("q"), m_q);
  settings.setValue(QStringLiteral("balance"), m_balance);
  settings.setValue(QStringLiteral("normalizationEnabled"), m_normalizationEnabled);
  settings.setValue(QStringLiteral("outputDeviceId"), m_outputDeviceId);
  settings.setValue(QStringLiteral("trackGains"), m_trackGains);
  settings.endGroup();
}

OrchardAudioEngineConfig AudioEngineOutput::engineConfiguration() const {
  OrchardAudioEngineConfig config{};
  config.enabled = m_enabled;
  config.auto_eq_enabled = m_autoEqEnabled;
  config.eq_enabled = m_eqEnabled;
  config.normalization_enabled = m_normalizationEnabled;
  std::copy(std::begin(m_gains), std::end(m_gains), config.gains_db);
  config.preamp_db = static_cast<float>(m_preampDb);
  config.output_gain_db = static_cast<float>(m_outputGainDb);
  config.q = static_cast<float>(m_q);
  config.balance = static_cast<float>(m_balance);
  return config;
}

void AudioEngineOutput::applyConfiguration() {
  // Track gains travel as a map: the pipeline may switch tracks before this side hears of it.
  post([pipeline = m_pipeline, config = engineConfiguration(), gains = m_trackGains] {
    pipeline->configure(config, gains);
  });
}
