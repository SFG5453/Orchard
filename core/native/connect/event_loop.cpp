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

#include "connect/event_loop.h"

namespace orchard::connect {

EventLoop::EventLoop() : m_thread([this] { run(); }) {
  // Published before any task can observe it; run() waits on the mutex first.
  std::lock_guard lock(m_mutex);
  m_threadId = m_thread.get_id();
}

EventLoop::~EventLoop() { stop(); }

void EventLoop::post(Task task) {
  {
    std::lock_guard lock(m_mutex);
    if (m_stopping)
      return;
    m_tasks.push_back(std::move(task));
  }
  m_wake.notify_one();
}

EventLoop::TimerId EventLoop::after(std::chrono::milliseconds delay, Task task) {
  TimerId id = 0;
  {
    std::lock_guard lock(m_mutex);
    if (m_stopping)
      return 0;
    id = m_nextTimer++;
    m_timers.emplace(std::chrono::steady_clock::now() + delay, std::make_pair(id, std::move(task)));
  }
  m_wake.notify_one();
  return id;
}

void EventLoop::cancel(TimerId id) {
  if (id == 0)
    return;
  std::lock_guard lock(m_mutex);
  for (auto it = m_timers.begin(); it != m_timers.end(); ++it) {
    if (it->second.first == id) {
      m_timers.erase(it);
      return;
    }
  }
}

void EventLoop::stop() {
  {
    std::lock_guard lock(m_mutex);
    m_stopping = true;
  }
  m_wake.notify_one();
  if (!m_thread.joinable())
    return;
  // Owners stop the loop from outside; detaching beats std::terminate if one does not.
  if (inLoop())
    m_thread.detach();
  else
    m_thread.join();
}

void EventLoop::run() {
  std::unique_lock lock(m_mutex);
  while (true) {
    if (!m_tasks.empty()) {
      Task task = std::move(m_tasks.front());
      m_tasks.pop_front();
      lock.unlock();
      task();
      lock.lock();
      continue;
    }
    if (m_stopping)
      break;
    const auto now = std::chrono::steady_clock::now();
    if (!m_timers.empty() && m_timers.begin()->first <= now) {
      Task task = std::move(m_timers.begin()->second.second);
      m_timers.erase(m_timers.begin());
      lock.unlock();
      task();
      lock.lock();
      continue;
    }
    if (m_timers.empty())
      m_wake.wait(lock);
    else
      m_wake.wait_until(lock, m_timers.begin()->first);
  }
  m_timers.clear();
}

} // namespace orchard::connect
