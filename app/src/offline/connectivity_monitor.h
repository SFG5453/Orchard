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

#include <QElapsedTimer>
#include <QObject>
#include <QString>
#include <QTimer>
#include <functional>

class QNetworkAccessManager;

// Decides whether the network is usable. A failed check opens one grace window
// and a second failure parks the app offline until the user retries.
class ConnectivityMonitor final : public QObject {
  Q_OBJECT
  // "online", "retrying" or "offline".
  Q_PROPERTY(QString state READ stateName NOTIFY stateChanged)
  Q_PROPERTY(bool offline READ offline NOTIFY stateChanged)
  Q_PROPERTY(bool retrying READ retrying NOTIFY stateChanged)
  // A probe is in flight; drives the spinner on the manual retry button.
  Q_PROPERTY(bool checking READ checking NOTIFY checkingChanged)
  // Whole seconds left in the grace window; 0 outside it.
  Q_PROPERTY(int retryIn READ retryIn NOTIFY retryInChanged)

public:
  enum class State { Online, Retrying, Offline };
  // Calls done(true) when the network answers, exactly once.
  using Probe = std::function<void(std::function<void(bool)> done)>;
  struct Timing {
    int graceMs{10000};
    int pollMs{15000};
    int tickMs{1000};
    // Failure reports closer together than this share one probe.
    int reportDebounceMs{5000};
    // Also check when the OS reports reachability changes.
    bool systemHints{true};
  };

  // Probes well-known reachability endpoints over HTTP.
  explicit ConnectivityMonitor(QObject *parent = nullptr);
  // Tests and tools supply their own probe and timing.
  ConnectivityMonitor(Probe probe, Timing timing, QObject *parent = nullptr);
  ~ConnectivityMonitor() override;

  [[nodiscard]] State state() const { return m_state; }
  [[nodiscard]] QString stateName() const;
  [[nodiscard]] bool offline() const { return m_state == State::Offline; }
  [[nodiscard]] bool retrying() const { return m_state == State::Retrying; }
  [[nodiscard]] bool checking() const { return m_checking; }
  [[nodiscard]] int retryIn() const;

  // First check, polling and the OS reachability hook.
  void start();
  // Manual retry. Skips the rest of the grace window when one is open.
  Q_INVOKABLE void retry();
  // A request failed somewhere; verifies the connection when it is believed up.
  Q_INVOKABLE void reportFailure();

signals:
  void stateChanged();
  void retryInChanged();
  void checkingChanged();
  // Entered offline after the grace retry failed.
  void lost();
  // Back online after having been offline.
  void restored();
  // A manual retry found the network still down.
  void retryFailed();

private:
  void probe(std::function<void(bool)> done);
  void httpProbe(int index, std::function<void(bool)> done);
  void poll();
  void beginGrace();
  void endGrace();
  void settle(bool ok);
  void setState(State state);

  Probe m_probe;
  Timing m_timing;
  QNetworkAccessManager *m_network{nullptr};
  QTimer m_pollTimer;
  QTimer m_graceTimer;
  QTimer m_tickTimer;
  QElapsedTimer m_graceClock;
  QElapsedTimer m_lastCheck;
  State m_state{State::Online};
  // Invalidates probes that finish after a newer one began.
  quint64 m_probeId{0};
  int m_shownRetryIn{0};
  bool m_checking{false};
  bool m_started{false};
};
