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
#include <QPointer>

// GLib names struct members `signals`, which Qt defines as a keyword.
#pragma push_macro("signals")
#undef signals
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#pragma pop_macro("signals")

#include <functional>
#include <memory>

// WebKitGTK headers for Qt sources, and GAsyncReadyCallback glue: `finish` runs with the async result unless `owner`
// was destroyed first.
namespace webkit_async {

struct Pending {
  QPointer<QObject> owner;
  std::function<void(GObject *, GAsyncResult *)> finish;
};

inline void finished(GObject *source, GAsyncResult *result, gpointer data) {
  std::unique_ptr<Pending> pending(static_cast<Pending *>(data));
  if (pending->owner)
    pending->finish(source, result);
}

inline gpointer pending(QObject *owner, std::function<void(GObject *, GAsyncResult *)> finish) {
  return new Pending{owner, std::move(finish)};
}

} // namespace webkit_async
