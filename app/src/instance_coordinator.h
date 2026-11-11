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

#include <QLocalServer>
#include <QLockFile>
#include <QObject>
#include <QStringList>
#include <functional>

// A second launch forwards links and asks the existing process to show its window.
// macOS sends links to the running process as open-URL events instead.
class InstanceCoordinator final : public QObject {
public:
  enum class StartResult { Primary, Forwarded, Failed };
  InstanceCoordinator();

  [[nodiscard]] StartResult start(const QStringList &arguments = {});
  void setActivationHandler(std::function<void()> handler);
  void setLinkHandler(std::function<void(const QString &)> handler);

protected:
  bool eventFilter(QObject *watched, QEvent *event) override;

private:
  [[nodiscard]] bool requestActivation(const QStringList &arguments) const;
  void receiveArguments(const QStringList &arguments);
  void activate();

  QString m_serverName;
  QLockFile m_lock;
  QLocalServer m_server;
  std::function<void()> m_activationHandler;
  std::function<void(const QString &)> m_linkHandler;
  QStringList m_pendingLinks;
  bool m_activationPending = false;
};
