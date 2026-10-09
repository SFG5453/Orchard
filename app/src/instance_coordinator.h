/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <QLocalServer>
#include <QLockFile>
#include <QObject>
#include <functional>

// A second launch asks the existing process to show its window before it exits.
class InstanceCoordinator final : public QObject {
public:
  enum class StartResult { Primary, Forwarded, Failed };
  InstanceCoordinator();

  [[nodiscard]] StartResult start();
  void setActivationHandler(std::function<void()> handler);

private:
  [[nodiscard]] bool requestActivation() const;
  void activate();

  QString m_serverName;
  QLockFile m_lock;
  QLocalServer m_server;
  std::function<void()> m_activationHandler;
  bool m_activationPending = false;
};
