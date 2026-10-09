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

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

namespace orchard::connect {

// One thread owns all Connect state. libdatachannel and host calls post here,
// so sessions never need their own locks.
class EventLoop : public std::enable_shared_from_this<EventLoop> {
public:
  using Task = std::function<void()>;
  using TimerId = std::uint64_t;

  EventLoop();
  ~EventLoop();
  EventLoop(const EventLoop &) = delete;
  EventLoop &operator=(const EventLoop &) = delete;

  // Safe from any thread. Tasks posted after stop() are dropped.
  void post(Task task);
  TimerId after(std::chrono::milliseconds delay, Task task);
  void cancel(TimerId id);
  // Runs pending work up to now, then joins. Must not be called from the loop thread.
  void stop();
  [[nodiscard]] bool inLoop() const { return std::this_thread::get_id() == m_threadId; }

private:
  void run();

  std::mutex m_mutex;
  std::condition_variable m_wake;
  std::deque<Task> m_tasks;
  std::multimap<std::chrono::steady_clock::time_point, std::pair<TimerId, Task>> m_timers;
  TimerId m_nextTimer = 1;
  bool m_stopping = false;
  std::thread m_thread;
  std::thread::id m_threadId;
};

} // namespace orchard::connect
