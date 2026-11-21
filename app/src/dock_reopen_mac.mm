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

#include "dock_reopen_mac.h"

#import <AppKit/AppKit.h>

@interface OrchardReopenHandler : NSObject
@property(nonatomic, copy) void (^reopen)(void);
- (void)install;
@end

@implementation OrchardReopenHandler

- (void)install {
  [NSAppleEventManager.sharedAppleEventManager setEventHandler:self
                                                   andSelector:@selector(handleReopen:withReply:)
                                                 forEventClass:kCoreEventClass
                                                    andEventID:kAEReopenApplication];
}

- (void)handleReopen:(NSAppleEventDescriptor *)event withReply:(NSAppleEventDescriptor *)reply {
  if (self.reopen)
    self.reopen();
}

@end

DockReopen::DockReopen(std::function<void()> reopen)
{
  OrchardReopenHandler *handler = [[OrchardReopenHandler alloc] init];
  handler.reopen = ^{
    reopen();
  };
  [handler install];
  // AppKit can install its own reopen handler while it finishes launching; take it back after.
  id observer = [NSNotificationCenter.defaultCenter addObserverForName:NSApplicationDidFinishLaunchingNotification
                                                                object:nil
                                                                 queue:NSOperationQueue.mainQueue
                                                            usingBlock:^(NSNotification *) {
                                                              [handler install];
                                                            }];
  // The event manager does not retain its handlers.
  m_handler = (__bridge_retained void *)handler;
  m_launchObserver = (__bridge_retained void *)observer;
}

DockReopen::~DockReopen()
{
  id observer = (__bridge_transfer id)m_launchObserver;
  [NSNotificationCenter.defaultCenter removeObserver:observer];
  [NSAppleEventManager.sharedAppleEventManager removeEventHandlerForEventClass:kCoreEventClass
                                                                    andEventID:kAEReopenApplication];
  OrchardReopenHandler *handler = (__bridge_transfer OrchardReopenHandler *)m_handler;
  handler.reopen = nil;
}
