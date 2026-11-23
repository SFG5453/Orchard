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

#include "webkit_view.h"
#include "webkit_mac.h"
#include "webkit_view_mac_delegate.h"

#include <QPointer>
#include <utility>

using webkit_mac::ns;

namespace {

constexpr auto kMessageHandler = @"orchard";
NSArray<NSString *> *const kObservedKeys = @[ @"URL", @"title", @"estimatedProgress", @"loading", @"canGoBack" ];

// Google refuses sign-in from WebKit user agents that do not name Safari.
NSString *safariUserAgentSuffix() {
  NSBundle *safari = [NSBundle bundleWithPath:@"/Applications/Safari.app"];
  NSString *version = [safari objectForInfoDictionaryKey:@"CFBundleShortVersionString"];
  return [NSString stringWithFormat:@"Version/%@ Safari/605.1.15", version.length ? version : @"18.0"];
}

// WKWebView only receives copy, paste and undo through Edit menu key equivalents.
void installEditMenu() {
  NSMenu *main = NSApp.mainMenu;
  if (!main) {
    main = [[NSMenu alloc] init];
    NSApp.mainMenu = main;
  }
  NSString *title = ns(QObject::tr("Edit"));
  if ([main indexOfItemWithTitle:title] >= 0)
    return;
  NSMenu *edit = [[NSMenu alloc] initWithTitle:title];
  [edit addItemWithTitle:ns(QObject::tr("Undo")) action:@selector(undo:) keyEquivalent:@"z"];
  NSMenuItem *redo = [edit addItemWithTitle:ns(QObject::tr("Redo")) action:@selector(redo:) keyEquivalent:@"z"];
  redo.keyEquivalentModifierMask = NSEventModifierFlagCommand | NSEventModifierFlagShift;
  [edit addItem:NSMenuItem.separatorItem];
  [edit addItemWithTitle:ns(QObject::tr("Cut")) action:@selector(cut:) keyEquivalent:@"x"];
  [edit addItemWithTitle:ns(QObject::tr("Copy")) action:@selector(copy:) keyEquivalent:@"c"];
  [edit addItemWithTitle:ns(QObject::tr("Paste")) action:@selector(paste:) keyEquivalent:@"v"];
  [edit addItemWithTitle:ns(QObject::tr("Select All")) action:@selector(selectAll:) keyEquivalent:@"a"];
  NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title action:nil keyEquivalent:@""];
  item.submenu = edit;
  [main addItem:item];
}

} // namespace

struct WebKitView::Native {
  WKWebView *view{nil};
  OrchardWebDelegate *delegate{nil};
  NSWindow *window{nil};
  OrchardProgressLine *progress{nil};
};

WebKitView::WebKitView(QObject *parent) : WebKitView(Options{}, parent) {}

WebKitView::WebKitView(const Options &options, QObject *parent)
    : QObject(parent), m_native(std::make_unique<Native>()) {
  WebKitProfile *profile = options.profile ? options.profile : WebKitProfile::named();
  OrchardWebDelegate *delegate = [[OrchardWebDelegate alloc] init];
  m_native->delegate = delegate;

  WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
  configuration.websiteDataStore = profile->native()->store;
  configuration.preferences.javaScriptCanOpenWindowsAutomatically = options.allowAutomaticPopups;
  configuration.applicationNameForUserAgent = safariUserAgentSuffix();
  WKUserContentController *content = configuration.userContentController;
  for (const QString &source : options.startupScripts) {
    [content addUserScript:[[WKUserScript alloc] initWithSource:ns(source)
                                                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                forMainFrameOnly:YES]];
  }
  [content addScriptMessageHandler:delegate name:kMessageHandler];

  WKWebView *view = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 980, 720) configuration:configuration];
  m_native->view = view;
  if (!options.userAgent.isEmpty())
    view.customUserAgent = ns(options.userAgent);
  view.navigationDelegate = delegate;
  view.UIDelegate = delegate;
  delegate.webView = view;

  const QPointer<WebKitView> self(this);
  delegate.changed = ^{
    if (self)
      self->sync();
  };
  delegate.finished = ^{
    if (!self)
      return;
    self->sync();
    emit self->loadFinished();
  };
  delegate.closeRequested = ^{
    if (self)
      emit self->closeRequested();
  };
  delegate.messageReceived = ^(id body) {
    if (self)
      emit self->messageReceived(webkit_mac::jsResult(body));
  };
  delegate.goBack = ^{
    if (self)
      self->goBack();
  };
  delegate.reload = ^{
    if (self)
      self->reload();
  };
  for (NSString *key in kObservedKeys)
    [view addObserver:delegate forKeyPath:key options:0 context:nullptr];
}

WebKitView::~WebKitView() {
  WKWebView *view = m_native->view;
  OrchardWebDelegate *delegate = m_native->delegate;
  for (NSString *key in kObservedKeys)
    [view removeObserver:delegate forKeyPath:key];
  [view.configuration.userContentController removeScriptMessageHandlerForName:kMessageHandler];
  [view stopLoading];
  view.navigationDelegate = nil;
  view.UIDelegate = nil;
  [delegate detach];
  if (m_native->window) {
    m_native->window.delegate = nil;
    [m_native->window close];
  }
}

void WebKitView::show(const QString &title, const QSize &size, bool navigation) {
  if (NSWindow *window = m_native->window) {
    [NSApp activate];
    [window makeKeyAndOrderFront:nil];
    return;
  }
  m_windowTitle = title;
  NSWindow *window = [[NSWindow alloc]
      initWithContentRect:NSMakeRect(0, 0, size.width(), size.height())
                styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable |
                          NSWindowStyleMaskResizable
                  backing:NSBackingStoreBuffered
                    defer:NO];
  window.releasedWhenClosed = NO;
  // Matches the dark Qt pages these windows replace.
  window.appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
  window.delegate = m_native->delegate;
  m_native->delegate.mainWindow = window;
  if (navigation) {
    NSToolbar *toolbar = [[NSToolbar alloc] initWithIdentifier:@"OrchardSignIn"];
    toolbar.delegate = m_native->delegate;
    toolbar.displayMode = NSToolbarDisplayModeIconOnly;
    window.toolbar = toolbar;
    window.toolbarStyle = NSWindowToolbarStyleUnifiedCompact;
  }

  NSView *container = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, size.width(), size.height())];
  m_native->view.frame = container.bounds;
  m_native->view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  [container addSubview:m_native->view];
  OrchardProgressLine *progress =
      [[OrchardProgressLine alloc] initWithFrame:NSMakeRect(0, size.height() - 2, size.width(), 2)];
  progress.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
  [container addSubview:progress];
  m_native->progress = progress;
  window.contentView = container;

  [window center];
  sync();
  installEditMenu();
  [NSApp activate];
  [window makeKeyAndOrderFront:nil];
  [window makeFirstResponder:m_native->view];
  m_native->window = window;
}

void WebKitView::showOffscreen(const QSize &size) {
  if (m_native->window)
    return;
  // Keeps the background harvest out of the Dock and the app switcher.
  [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
  // WebKit throttles pages in occluded windows, so this one sits on screen fully transparent.
  NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, size.width(), size.height())
                                                 styleMask:NSWindowStyleMaskBorderless
                                                   backing:NSBackingStoreBuffered
                                                     defer:NO];
  window.releasedWhenClosed = NO;
  window.alphaValue = 0.0;
  window.ignoresMouseEvents = YES;
  window.level = NSNormalWindowLevel - 1;
  window.excludedFromWindowsMenu = YES;
  window.contentView = m_native->view;
  [window orderFrontRegardless];
  m_native->window = window;
}

void WebKitView::hide() {
  [m_native->window orderOut:nil];
  [m_native->delegate hidePopups];
}

void WebKitView::setUrl(const QUrl &url) {
  if (url == m_url)
    return;
  [m_native->view loadRequest:[NSURLRequest requestWithURL:url.toNSURL()]];
}

void WebKitView::loadHtml(const QString &html, const QUrl &baseUrl) {
  [m_native->view loadHTMLString:ns(html) baseURL:baseUrl.toNSURL()];
}

void WebKitView::goBack() {
  [m_native->view goBack];
}

void WebKitView::reload() {
  [m_native->view reload];
}

void WebKitView::evaluate(const QString &script, std::function<void(const QString &)> done) {
  const QPointer<WebKitView> self(this);
  [m_native->view evaluateJavaScript:ns(script)
                   completionHandler:^(id result, NSError *error) {
                     if (self && done)
                       done(error ? QString() : webkit_mac::jsResult(result));
                   }];
}

void WebKitView::runJavaScript(const QString &script, const QJSValue &callback) {
  if (!callback.isCallable()) {
    evaluate(script);
    return;
  }
  evaluate(script, [callback](const QString &result) mutable { callback.call({QJSValue(result)}); });
}

void WebKitView::sync() {
  WKWebView *view = m_native->view;
  m_title = QString::fromNSString(view.title);
  m_loading = view.loading;
  m_progress = qRound(view.estimatedProgress * 100.0);
  m_canGoBack = view.canGoBack;
  const QUrl previous = std::exchange(m_url, view.URL ? QUrl::fromNSURL(view.URL) : QUrl());
  if (NSWindow *window = m_native->delegate.mainWindow)
    window.title = ns(m_title.isEmpty() ? m_windowTitle : m_title);
  m_native->delegate.backItem.enabled = m_canGoBack;
  [m_native->progress setFraction:m_loading ? view.estimatedProgress : 0.0];
  emit changed();
  if (m_url != previous)
    emit urlChanged();
}
