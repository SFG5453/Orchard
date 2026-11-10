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

#import <AppKit/AppKit.h>
#import <WebKit/WebKit.h>

// A thin load progress bar along the top of the page. It never takes mouse events.
@interface OrchardProgressLine : NSView
- (void)setFraction:(double)fraction;
@end

// Routes WKWebView, window and toolbar callbacks for one WebKitView, and owns its popups.
// The blocks reach the C++ view; detach drops them before it goes away.
@interface OrchardWebDelegate
    : NSObject <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, NSWindowDelegate, NSToolbarDelegate>
@property(nonatomic, weak) WKWebView *webView;
@property(nonatomic, weak) NSWindow *mainWindow;
@property(nonatomic, readonly) NSToolbarItem *backItem;
@property(nonatomic, copy) void (^changed)(void);
@property(nonatomic, copy) void (^finished)(void);
@property(nonatomic, copy) void (^closeRequested)(void);
@property(nonatomic, copy) void (^messageReceived)(id body);
@property(nonatomic, copy) void (^goBack)(void);
@property(nonatomic, copy) void (^reload)(void);
- (void)hidePopups;
- (void)detach;
@end
