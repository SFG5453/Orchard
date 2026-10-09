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

#include <QFutureWatcher>
#include <QObject>
#include <QtConcurrent>

namespace local {

// Runs `work` on the thread pool and hands its result to `done` on the context
// object's thread. The context owns the watcher, so closing the app mid-import
// does not call back into a corpse.
template <typename Result, typename Work, typename Done>
void runAsync(QObject *context, Work work, Done done) {
  auto *watcher = new QFutureWatcher<Result>(context);
  QObject::connect(watcher, &QFutureWatcherBase::finished, context, [watcher, done]() {
    Result result = watcher->result();
    watcher->deleteLater();
    done(std::move(result));
  });
  watcher->setFuture(QtConcurrent::run(std::move(work)));
}

} // namespace local
