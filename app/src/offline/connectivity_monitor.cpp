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

#include "connectivity_monitor.h"

#include <QNetworkAccessManager>
#include <QNetworkInformation>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QStringList>
#include <QUrl>

namespace {

// Any HTTP status proves the path works; the endpoints return 204 and no body, the shortest conversation online.
const QStringList kProbeUrls = {
    QStringLiteral("https://www.gstatic.com/generate_204"),
    QStringLiteral("https://www.youtube.com/generate_204"),
};

constexpr int kProbeTimeoutMs = 5000;

} // namespace

ConnectivityMonitor::ConnectivityMonitor(QObject *parent) : ConnectivityMonitor(Probe{}, Timing{}, parent) {}

ConnectivityMonitor::ConnectivityMonitor(Probe probe, Timing timing, QObject *parent)
    : QObject(parent), m_probe(std::move(probe)), m_timing(timing) {
  m_pollTimer.setInterval(m_timing.pollMs);
  m_graceTimer.setSingleShot(true);
  m_graceTimer.setInterval(m_timing.graceMs);
  m_tickTimer.setInterval(m_timing.tickMs);
  connect(&m_pollTimer, &QTimer::timeout, this, &ConnectivityMonitor::poll);
  connect(&m_graceTimer, &QTimer::timeout, this, &ConnectivityMonitor::endGrace);
  connect(&m_tickTimer, &QTimer::timeout, this, [this] {
    const int left = retryIn();
    if (left == m_shownRetryIn)
      return;
    m_shownRetryIn = left;
    emit retryInChanged();
  });
}

ConnectivityMonitor::~ConnectivityMonitor() = default;

QString ConnectivityMonitor::stateName() const {
  switch (m_state) {
  case State::Online:
    return QStringLiteral("online");
  case State::Retrying:
    return QStringLiteral("retrying");
  case State::Offline:
    return QStringLiteral("offline");
  }
  return QStringLiteral("online");
}

int ConnectivityMonitor::retryIn() const {
  if (m_state != State::Retrying)
    return 0;
  const qint64 left = m_timing.graceMs - m_graceClock.elapsed();
  return left <= 0 ? 0 : static_cast<int>((left + 999) / 1000);
}

void ConnectivityMonitor::start() {
  if (m_started)
    return;
  m_started = true;
  // The OS flag is only a hint (VPNs and bare Linux setups lie); the probe decides.
  if (m_timing.systemHints && QNetworkInformation::loadDefaultBackend()) {
    if (auto *info = QNetworkInformation::instance()) {
      connect(info, &QNetworkInformation::reachabilityChanged, this,
              [this](QNetworkInformation::Reachability reachability) {
                if (reachability == QNetworkInformation::Reachability::Disconnected ||
                    reachability == QNetworkInformation::Reachability::Local)
                  poll();
              });
    }
  }
  m_pollTimer.start();
  poll();
}

void ConnectivityMonitor::probe(std::function<void(bool)> done) {
  const quint64 id = ++m_probeId;
  m_checking = true;
  emit checkingChanged();
  m_lastCheck.start();
  const auto finish = [this, id, done = std::move(done)](bool ok) {
    if (id != m_probeId)
      return;
    m_checking = false;
    emit checkingChanged();
    done(ok);
  };
  if (m_probe) {
    m_probe(finish);
    return;
  }
  httpProbe(0, finish);
}

void ConnectivityMonitor::httpProbe(int index, std::function<void(bool)> done) {
  if (index >= kProbeUrls.size()) {
    done(false);
    return;
  }
  if (!m_network)
    m_network = new QNetworkAccessManager(this);
  QNetworkRequest request{QUrl(kProbeUrls.at(index))};
  request.setTransferTimeout(kProbeTimeoutMs);
  request.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
  QNetworkReply *reply = m_network->get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, index, done = std::move(done)]() mutable {
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    reply->deleteLater();
    if (status > 0)
      done(true);
    else
      httpProbe(index + 1, std::move(done));
  });
}

void ConnectivityMonitor::poll() {
  if (m_state != State::Online || m_checking)
    return;
  probe([this](bool ok) {
    if (m_state != State::Online)
      return;
    if (!ok)
      beginGrace();
  });
}

void ConnectivityMonitor::reportFailure() {
  if (m_state != State::Online || m_checking)
    return;
  if (m_lastCheck.isValid() && m_lastCheck.elapsed() < m_timing.reportDebounceMs)
    return;
  poll();
}

void ConnectivityMonitor::beginGrace() {
  m_pollTimer.stop();
  m_graceClock.start();
  setState(State::Retrying);
  m_shownRetryIn = retryIn();
  emit retryInChanged();
  m_graceTimer.start();
  m_tickTimer.start();
}

// The grace window is over: one more probe decides between online and offline.
void ConnectivityMonitor::endGrace() {
  m_graceTimer.stop();
  m_tickTimer.stop();
  probe([this](bool ok) { settle(ok); });
}

void ConnectivityMonitor::settle(bool ok) {
  const bool wasOffline = m_state == State::Offline;
  if (ok) {
    setState(State::Online);
    m_pollTimer.start();
    if (wasOffline)
      emit restored();
    return;
  }
  setState(State::Offline);
  if (wasOffline)
    emit retryFailed();
  else
    emit lost();
}

void ConnectivityMonitor::retry() {
  if (m_state == State::Online || m_checking)
    return;
  // Offline stays parked after a failure; only another call here leaves it.
  if (m_state == State::Retrying) {
    m_graceTimer.stop();
    m_tickTimer.stop();
  }
  probe([this](bool ok) { settle(ok); });
}

void ConnectivityMonitor::setState(State state) {
  if (m_state == state)
    return;
  m_state = state;
  if (state != State::Retrying && m_shownRetryIn != 0) {
    m_shownRetryIn = 0;
    emit retryInChanged();
  }
  emit stateChanged();
}
