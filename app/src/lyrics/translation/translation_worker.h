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
#include <QStringList>
#include <atomic>
#include <memory>

class MarianModel;
class QTimer;

// Runs a MarianModel on its own low-priority thread. Jobs carry a generation;
// bumping the shared counter cancels whatever is running between decode steps.
class TranslationWorker final : public QObject {
  Q_OBJECT
public:
  explicit TranslationWorker(std::shared_ptr<std::atomic_int> current);
  // libLiteRt beside the executable, or ORCHARD_LITERT_LIBRARY.
  static QString liteRtLibraryPath();
  ~TranslationWorker() override;

public slots:
  void translate(int generation, const QString &packDirectory, const QStringList &texts);

signals:
  void translated(int generation, const QString &text, const QString &translation);
  void finished(int generation, const QString &error);

private:
  void unload();

  std::shared_ptr<std::atomic_int> m_current;
  std::unique_ptr<MarianModel> m_model;
  QString m_loadedDirectory;
  QTimer *m_idle{nullptr};
};
