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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include "connect/event_loop.h"

#include <cstddef>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

namespace orchard::connect {

// An ordered, reliable message pipe. Sessions see only this, so the protocol
// above it is identical over LAN WebSockets and WebRTC data channels.
class ConnectTransport {
public:
  struct Callbacks {
    std::function<void()> opened;
    std::function<void(std::string)> text;
    std::function<void(std::string)> binary;
    std::function<void(std::string reason)> closed;
    // WebRTC only: a description or candidate for the hub to relay.
    std::function<void(std::string json)> signal;
  };

  virtual ~ConnectTransport() = default;

  [[nodiscard]] virtual const char *kind() const = 0; // "lan" or "webrtc"
  [[nodiscard]] virtual bool isOpen() const = 0;
  virtual bool sendText(const std::string &text) = 0;
  virtual bool sendBinary(const std::string &bytes) = 0;
  [[nodiscard]] virtual std::size_t bufferedAmount() const = 0;
  virtual void close() = 0;

  // Callbacks run on `loop`, never on a library thread. Replaces earlier callbacks.
  void bind(std::weak_ptr<EventLoop> loop, Callbacks callbacks) {
    std::lock_guard lock(m_bindMutex);
    m_loop = std::move(loop);
    m_callbacks = std::make_shared<Callbacks>(std::move(callbacks));
  }

protected:
  template <typename Fn> void dispatch(Fn &&fn) {
    std::shared_ptr<EventLoop> loop;
    std::weak_ptr<Callbacks> weak;
    {
      std::lock_guard lock(m_bindMutex);
      loop = m_loop.lock();
      weak = m_callbacks;
    }
    if (!loop)
      return;
    loop->post([weak, fn = std::forward<Fn>(fn)]() mutable {
      if (auto callbacks = weak.lock())
        fn(*callbacks);
    });
  }

  void emitOpened() {
    dispatch([](Callbacks &c) {
      if (c.opened)
        c.opened();
    });
  }
  void emitText(std::string text) {
    dispatch([text = std::move(text)](Callbacks &c) mutable {
      if (c.text)
        c.text(std::move(text));
    });
  }
  void emitBinary(std::string bytes) {
    dispatch([bytes = std::move(bytes)](Callbacks &c) mutable {
      if (c.binary)
        c.binary(std::move(bytes));
    });
  }
  void emitClosed(std::string reason) {
    dispatch([reason = std::move(reason)](Callbacks &c) mutable {
      if (c.closed)
        c.closed(std::move(reason));
    });
  }
  void emitSignal(std::string json) {
    dispatch([json = std::move(json)](Callbacks &c) mutable {
      if (c.signal)
        c.signal(std::move(json));
    });
  }
  // Drops pending deliveries; called when the owner lets go of the transport.
  void unbind() {
    std::lock_guard lock(m_bindMutex);
    m_callbacks.reset();
  }

private:
  // Library threads dispatch while the loop thread binds.
  std::mutex m_bindMutex;
  std::weak_ptr<EventLoop> m_loop;
  std::shared_ptr<Callbacks> m_callbacks;
};

} // namespace orchard::connect
