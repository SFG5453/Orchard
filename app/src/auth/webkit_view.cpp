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
#include "webkit_async.h"
#include "webkit_profile.h"


#include <utility>

namespace {

constexpr auto kMessageHandler = "orchard";

QString jsResult(JSCValue *value) {
  if (!value || jsc_value_is_undefined(value) || jsc_value_is_null(value))
    return {};
  gchar *text = jsc_value_is_string(value) ? jsc_value_to_string(value) : jsc_value_to_json(value, 0);
  const QString result = QString::fromUtf8(text);
  g_free(text);
  return result;
}

GtkWidget *headerButton(const char *icon, const char *tooltip) {
  GtkWidget *button = gtk_button_new_from_icon_name(icon, GTK_ICON_SIZE_BUTTON);
  gtk_widget_set_tooltip_text(button, tooltip);
  return button;
}

} // namespace

WebKitView::WebKitView(QObject *parent) : WebKitView(Options{}, parent) {}

WebKitView::WebKitView(const Options &options, QObject *parent) : QObject(parent) {
  WebKitProfile *profile = options.profile ? options.profile : WebKitProfile::named();
  WebKitUserContentManager *content = webkit_user_content_manager_new();
  for (const QString &source : options.startupScripts) {
    WebKitUserScript *script = webkit_user_script_new(
        source.toUtf8().constData(), WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
        WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, nullptr, nullptr);
    webkit_user_content_manager_add_script(content, script);
    webkit_user_script_unref(script);
  }
  g_signal_connect(content, "script-message-received::orchard",
                   G_CALLBACK(+[](WebKitUserContentManager *, WebKitJavascriptResult *result, WebKitView *self) {
    emit self->messageReceived(jsResult(webkit_javascript_result_get_js_value(result)));
  }), this);
  webkit_user_content_manager_register_script_message_handler(content, kMessageHandler);

  m_view = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "web-context", profile->context(),
                                        "user-content-manager", content, nullptr));
  g_object_unref(content);
  g_object_ref_sink(m_view);
  if (!options.userAgent.isEmpty())
    webkit_settings_set_user_agent(webkit_web_view_get_settings(m_view), options.userAgent.toUtf8().constData());

  const auto notify = +[](GObject *, GParamSpec *, WebKitView *self) { self->sync(); };
  for (const char *property : {"notify::uri", "notify::title", "notify::estimated-load-progress", "notify::is-loading"})
    g_signal_connect(m_view, property, G_CALLBACK(notify), this);
  g_signal_connect(m_view, "load-changed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent event, WebKitView *self) {
    self->sync();
    if (event == WEBKIT_LOAD_FINISHED)
      emit self->loadFinished();
  }), this);
  // Sign-in providers open their authorize pages with window.open().
  g_signal_connect(m_view, "create", G_CALLBACK(+[](WebKitWebView *view, WebKitNavigationAction *, WebKitView *self) {
    WebKitWebView *popup = WEBKIT_WEB_VIEW(webkit_web_view_new_with_related_view(view));
    self->openPopup(popup);
    return GTK_WIDGET(popup);
  }), this);
}

WebKitView::~WebKitView() {
  g_signal_handlers_disconnect_by_data(webkit_web_view_get_user_content_manager(m_view), this);
  g_signal_handlers_disconnect_by_data(m_view, this);
  for (GtkWidget *popup : std::exchange(m_popups, {}))
    gtk_widget_destroy(popup);
  if (m_window)
    gtk_widget_destroy(m_window);
  g_object_unref(m_view);
}

void WebKitView::show(const QString &title, const QSize &size, bool navigation) {
  if (m_window) {
    gtk_window_present(GTK_WINDOW(m_window));
    return;
  }
  m_windowTitle = title;
  m_window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
  gtk_window_set_default_size(GTK_WINDOW(m_window), size.width(), size.height());
  g_signal_connect(m_window, "delete-event", G_CALLBACK(+[](GtkWidget *, GdkEvent *, WebKitView *self) {
    emit self->closeRequested();
    return TRUE;
  }), this);

  m_header = gtk_header_bar_new();
  gtk_header_bar_set_show_close_button(GTK_HEADER_BAR(m_header), TRUE);
  if (navigation) {
    m_backButton = headerButton("go-previous-symbolic", "Back");
    GtkWidget *back = m_backButton;
    GtkWidget *reload = headerButton("view-refresh-symbolic", "Reload");
    g_signal_connect_swapped(back, "clicked", G_CALLBACK(webkit_web_view_go_back), m_view);
    g_signal_connect_swapped(reload, "clicked", G_CALLBACK(webkit_web_view_reload), m_view);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(m_header), back);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(m_header), reload);
  }
  gtk_window_set_titlebar(GTK_WINDOW(m_window), m_header);

  GtkWidget *overlay = gtk_overlay_new();
  gtk_container_add(GTK_CONTAINER(overlay), GTK_WIDGET(m_view));
  m_progressBar = gtk_progress_bar_new();
  gtk_style_context_add_class(gtk_widget_get_style_context(m_progressBar), "osd");
  gtk_widget_set_valign(m_progressBar, GTK_ALIGN_START);
  gtk_widget_set_no_show_all(m_progressBar, TRUE);
  gtk_overlay_add_overlay(GTK_OVERLAY(overlay), m_progressBar);
  gtk_container_add(GTK_CONTAINER(m_window), overlay);
  sync();
  gtk_widget_show_all(m_window);
  gtk_widget_grab_focus(GTK_WIDGET(m_view));
}

void WebKitView::showOffscreen(const QSize &size) {
  if (m_window)
    return;
  m_window = gtk_offscreen_window_new();
  gtk_window_set_default_size(GTK_WINDOW(m_window), size.width(), size.height());
  gtk_container_add(GTK_CONTAINER(m_window), GTK_WIDGET(m_view));
  gtk_widget_show_all(m_window);
}

void WebKitView::hide() {
  if (m_window)
    gtk_widget_hide(m_window);
  for (GtkWidget *popup : m_popups)
    gtk_widget_hide(popup);
}

void WebKitView::setUrl(const QUrl &url) {
  if (url == m_url)
    return;
  webkit_web_view_load_uri(m_view, url.toEncoded().constData());
}

void WebKitView::loadHtml(const QString &html, const QUrl &baseUrl) {
  webkit_web_view_load_html(m_view, html.toUtf8().constData(), baseUrl.toEncoded().constData());
}

void WebKitView::goBack() {
  webkit_web_view_go_back(m_view);
}

void WebKitView::reload() {
  webkit_web_view_reload(m_view);
}

void WebKitView::evaluate(const QString &script, std::function<void(const QString &)> done) {
  const QByteArray source = script.toUtf8();
  webkit_web_view_evaluate_javascript(m_view, source.constData(), source.size(), nullptr, nullptr, nullptr,
                                      webkit_async::finished,
                                      webkit_async::pending(this, [done](GObject *view, GAsyncResult *result) {
    JSCValue *value = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(view), result, nullptr);
    const QString text = jsResult(value);
    if (value)
      g_object_unref(value);
    if (done)
      done(text);
  }));
}

void WebKitView::runJavaScript(const QString &script, const QJSValue &callback) {
  if (!callback.isCallable()) {
    evaluate(script);
    return;
  }
  evaluate(script, [callback](const QString &result) mutable { callback.call({QJSValue(result)}); });
}

void WebKitView::sync() {
  const char *uri = webkit_web_view_get_uri(m_view);
  const char *title = webkit_web_view_get_title(m_view);
  m_title = title ? QString::fromUtf8(title) : QString();
  m_loading = webkit_web_view_is_loading(m_view);
  m_progress = qRound(webkit_web_view_get_estimated_load_progress(m_view) * 100.0);
  m_canGoBack = webkit_web_view_can_go_back(m_view);
  const QUrl previous = std::exchange(m_url, uri ? QUrl(QString::fromUtf8(uri)) : QUrl());
  if (m_header)
    gtk_header_bar_set_title(GTK_HEADER_BAR(m_header),
                             (m_title.isEmpty() ? m_windowTitle : m_title).toUtf8().constData());
  if (m_backButton)
    gtk_widget_set_sensitive(m_backButton, m_canGoBack);
  if (m_progressBar) {
    gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(m_progressBar), m_progress / 100.0);
    gtk_widget_set_visible(m_progressBar, m_loading);
  }
  emit changed();
  if (m_url != previous)
    emit urlChanged();
}

void WebKitView::openPopup(WebKitWebView *popup) {
  GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
  m_popups.append(window);
  if (m_window && GTK_IS_WINDOW(m_window) && gtk_widget_get_mapped(m_window))
    gtk_window_set_transient_for(GTK_WINDOW(window), GTK_WINDOW(m_window));
  gtk_window_set_default_size(GTK_WINDOW(window), 500, 680);
  gtk_container_add(GTK_CONTAINER(window), GTK_WIDGET(popup));
  g_object_bind_property(popup, "title", window, "title", G_BINDING_SYNC_CREATE);
  g_signal_connect(window, "destroy", G_CALLBACK(+[](GtkWidget *closed, WebKitView *self) {
    self->m_popups.removeOne(closed);
  }), this);
  g_signal_connect_swapped(popup, "ready-to-show", G_CALLBACK(gtk_widget_show_all), window);
  g_signal_connect_swapped(popup, "close", G_CALLBACK(gtk_widget_destroy), window);
  g_signal_connect(popup, "create", G_CALLBACK(+[](WebKitWebView *view, WebKitNavigationAction *, WebKitView *self) {
    WebKitWebView *next = WEBKIT_WEB_VIEW(webkit_web_view_new_with_related_view(view));
    self->openPopup(next);
    return GTK_WIDGET(next);
  }), this);
}
