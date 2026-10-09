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

#include "translation_worker.h"
#include "translation/litert_api.h"
#include "translation/marian_model.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QTimer>

namespace {
// Two threads keep a song under a few seconds without taking cores from games or playback.
constexpr int kThreads = 2;
// About 75 MB resident while loaded; songs in a row reuse it, idle gives it back.
constexpr int kUnloadAfterMs = 90 * 1000;
} // namespace

QString TranslationWorker::liteRtLibraryPath() {
  const QString override = qEnvironmentVariable("ORCHARD_LITERT_LIBRARY");
  if (!override.isEmpty())
    return override;
#if defined(Q_OS_WIN)
  const QString name = QStringLiteral("libLiteRt.dll");
#elif defined(Q_OS_MACOS)
  const QString name = QStringLiteral("libLiteRt.dylib");
#else
  const QString name = QStringLiteral("libLiteRt.so");
#endif
  return QDir(QCoreApplication::applicationDirPath()).filePath(name);
}

TranslationWorker::TranslationWorker(std::shared_ptr<std::atomic_int> current) : m_current(std::move(current)) {}

TranslationWorker::~TranslationWorker() = default;

void TranslationWorker::unload() {
  m_model.reset();
  m_loadedDirectory.clear();
}

void TranslationWorker::translate(int generation, const QString &packDirectory, const QStringList &texts) {
  const auto cancelled = [this, generation] { return m_current->load(std::memory_order_relaxed) != generation; };
  if (cancelled())
    return;
  if (!m_idle) {
    // Created here so the timer lives on the worker thread.
    m_idle = new QTimer(this);
    m_idle->setSingleShot(true);
    m_idle->setInterval(kUnloadAfterMs);
    connect(m_idle, &QTimer::timeout, this, &TranslationWorker::unload);
  }
  m_idle->stop();
  if (!m_model || m_loadedDirectory != packDirectory) {
    unload();
    std::string error;
    const LiteRtApi *api = LiteRtApi::get(QFile::encodeName(liteRtLibraryPath()).toStdString(), &error);
    auto model = std::make_unique<MarianModel>();
    if (!api || !model->load(*api, QFile::encodeName(packDirectory).toStdString(), kThreads, &error)) {
      emit finished(generation, QString::fromStdString(error));
      return;
    }
    m_model = std::move(model);
    m_loadedDirectory = packDirectory;
  }
  for (const QString &text : texts) {
    if (cancelled())
      break;
    const QString out = QString::fromStdString(m_model->translate(text.toStdString(), cancelled));
    if (!out.isEmpty())
      emit translated(generation, text, out);
  }
  m_idle->start();
  emit finished(generation, QString());
}
