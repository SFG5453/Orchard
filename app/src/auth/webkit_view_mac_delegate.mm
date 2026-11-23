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

#import "webkit_view_mac_delegate.h"

static NSToolbarItemIdentifier const kBackItem = @"OrchardBack";
static NSToolbarItemIdentifier const kReloadItem = @"OrchardReload";

@implementation OrchardProgressLine {
  double _fraction;
}

- (NSView *)hitTest:(NSPoint)point {
  return nil;
}

- (void)setFraction:(double)fraction {
  if (fraction == _fraction)
    return;
  _fraction = fraction;
  self.needsDisplay = YES;
}

- (void)drawRect:(NSRect)dirtyRect {
  if (_fraction <= 0.0 || _fraction >= 1.0)
    return;
  [[NSColor controlAccentColor] setFill];
  NSRect bar = self.bounds;
  bar.size.width *= _fraction;
  NSRectFill(bar);
}

@end

@implementation OrchardWebDelegate {
  NSMutableArray<NSWindow *> *_popups;
  NSToolbarItem *_backItem;
}

- (instancetype)init {
  if ((self = [super init]))
    _popups = [NSMutableArray array];
  return self;
}

- (NSToolbarItem *)backItem {
  return _backItem;
}

- (void)detach {
  self.changed = nil;
  self.finished = nil;
  self.closeRequested = nil;
  self.messageReceived = nil;
  self.goBack = nil;
  self.reload = nil;
  for (NSWindow *popup in [_popups copy])
    [popup close];
}

- (void)hidePopups {
  for (NSWindow *popup in _popups)
    [popup orderOut:nil];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context {
  if (object == self.webView) {
    if (self.changed)
      self.changed();
    return;
  }
  WKWebView *popup = object;
  popup.window.title = popup.title ?: @"";
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
  if (webView == self.webView && self.finished)
    self.finished();
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
  [self webView:webView didFinishNavigation:navigation];
}

- (void)webView:(WKWebView *)webView
    didFailProvisionalNavigation:(WKNavigation *)navigation
                       withError:(NSError *)error {
  [self webView:webView didFinishNavigation:navigation];
}

- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
  if (message.frameInfo.isMainFrame && self.messageReceived)
    self.messageReceived(message.body);
}

// Sign-in providers open their authorize pages with window.open().
- (WKWebView *)webView:(WKWebView *)webView
    createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
               forNavigationAction:(WKNavigationAction *)navigationAction
                    windowFeatures:(WKWindowFeatures *)windowFeatures {
  WKWebView *popup = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 500, 680) configuration:configuration];
  popup.UIDelegate = self;
  popup.navigationDelegate = self;
  popup.customUserAgent = webView.customUserAgent;
  [popup addObserver:self forKeyPath:@"title" options:0 context:nullptr];

  NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 500, 680)
                                                 styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                                                           NSWindowStyleMaskResizable
                                                   backing:NSBackingStoreBuffered
                                                     defer:NO];
  window.releasedWhenClosed = NO;
  window.appearance = self.mainWindow.appearance;
  window.contentView = popup;
  window.delegate = self;
  [_popups addObject:window];
  NSWindow *parent = self.mainWindow;
  if (parent.visible) {
    NSRect frame = window.frame;
    frame.origin.x = NSMidX(parent.frame) - frame.size.width / 2;
    frame.origin.y = NSMidY(parent.frame) - frame.size.height / 2;
    [window setFrame:frame display:NO];
  } else {
    [window center];
  }
  [window makeKeyAndOrderFront:nil];
  return popup;
}

- (void)webViewDidClose:(WKWebView *)webView {
  if (webView != self.webView)
    [webView.window close];
}

- (BOOL)windowShouldClose:(NSWindow *)sender {
  if (sender != self.mainWindow)
    return YES;
  if (self.closeRequested)
    self.closeRequested();
  return NO;
}

- (void)windowWillClose:(NSNotification *)notification {
  NSWindow *window = notification.object;
  if (![_popups containsObject:window])
    return;
  WKWebView *popup = (WKWebView *)window.contentView;
  [popup removeObserver:self forKeyPath:@"title"];
  [popup stopLoading];
  popup.UIDelegate = nil;
  popup.navigationDelegate = nil;
  window.delegate = nil;
  [_popups removeObject:window];
}

- (NSArray<NSToolbarItemIdentifier> *)toolbarAllowedItemIdentifiers:(NSToolbar *)toolbar {
  return @[ kBackItem, kReloadItem ];
}

- (NSArray<NSToolbarItemIdentifier> *)toolbarDefaultItemIdentifiers:(NSToolbar *)toolbar {
  return @[ kBackItem, kReloadItem ];
}

- (NSToolbarItem *)toolbar:(NSToolbar *)toolbar
        itemForItemIdentifier:(NSToolbarItemIdentifier)identifier
    willBeInsertedIntoToolbar:(BOOL)flag {
  const BOOL back = [identifier isEqualToString:kBackItem];
  NSToolbarItem *item = [[NSToolbarItem alloc] initWithItemIdentifier:identifier];
  item.label = back ? @"Back" : @"Reload";
  item.toolTip = item.label;
  item.image = [NSImage imageWithSystemSymbolName:back ? @"chevron.backward" : @"arrow.clockwise"
                         accessibilityDescription:item.label];
  item.bordered = YES;
  item.target = self;
  item.action = back ? @selector(backClicked:) : @selector(reloadClicked:);
  if (back) {
    item.autovalidates = NO;
    item.enabled = self.webView.canGoBack;
    _backItem = item;
  }
  return item;
}

- (void)backClicked:(id)sender {
  if (self.goBack)
    self.goBack();
}

- (void)reloadClicked:(id)sender {
  if (self.reload)
    self.reload();
}

@end
