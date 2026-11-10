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

#include "occlusion_watch_mac.h"

#include <QPointer>
#include <QWindow>

#import <AppKit/AppKit.h>

OcclusionWatch::OcclusionWatch(QWindow *window, std::function<void(bool)> changed)
{
    QPointer<QWindow> target(window);
    id observer = [NSNotificationCenter.defaultCenter
        addObserverForName:NSWindowDidChangeOcclusionStateNotification
                    object:nil
                     queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *note) {
                  if (!target || !target->handle())
                      return;
                  NSWindow *native = ((__bridge NSView *)reinterpret_cast<void *>(target->winId())).window;
                  if (note.object == native)
                      changed(!(native.occlusionState & NSWindowOcclusionStateVisible));
                }];
    m_observer = (__bridge_retained void *)observer;
}

OcclusionWatch::~OcclusionWatch()
{
    id observer = (__bridge_transfer id)m_observer;
    [NSNotificationCenter.defaultCenter removeObserver:observer];
}
