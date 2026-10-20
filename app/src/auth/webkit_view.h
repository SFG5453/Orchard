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

#include <QJSValue>
#include <QList>
#include <QObject>
#include <QSize>
#include <QString>
#include <QStringList>
#include <QUrl>

#include <functional>

class WebKitProfile;
typedef struct _GtkWidget GtkWidget;
typedef struct _WebKitWebView WebKitWebView;

// One WebKitGTK page. It runs without a window until show() gives it a GTK one with
// a header bar, since a GTK widget cannot live inside a Qt Quick scene. Pages post
// strings to the helper with window.webkit.messageHandlers.orchard.postMessage().
class WebKitView : public QObject {
  Q_OBJECT
  Q_PROPERTY(QUrl url READ url WRITE setUrl NOTIFY urlChanged)
  Q_PROPERTY(QString title READ title NOTIFY changed)
  Q_PROPERTY(bool loading READ loading NOTIFY changed)
  Q_PROPERTY(int loadProgress READ loadProgress NOTIFY changed)
  Q_PROPERTY(bool canGoBack READ canGoBack NOTIFY changed)

public:
  struct Options {
    // The shared sign-in profile when null.
    WebKitProfile *profile{nullptr};
    QString userAgent;
    // Injected into the top frame at document start, before page scripts.
    QStringList startupScripts;
  };

  explicit WebKitView(QObject *parent = nullptr);
  explicit WebKitView(const Options &options, QObject *parent = nullptr);
  ~WebKitView() override;

  // `title` stands in until the page has one; `navigation` adds back and reload.
  void show(const QString &title, const QSize &size, bool navigation);
  // Lays the page out at `size` in a window that is never mapped.
  void showOffscreen(const QSize &size);
  void hide();

  QUrl url() const { return m_url; }
  void setUrl(const QUrl &url);
  QString title() const { return m_title; }
  bool loading() const { return m_loading; }
  int loadProgress() const { return m_progress; }
  bool canGoBack() const { return m_canGoBack; }

  // Serves `html` at `baseUrl` so the page gets that origin.
  void loadHtml(const QString &html, const QUrl &baseUrl);
  Q_INVOKABLE void goBack();
  Q_INVOKABLE void reload();
  // `done` gets a string result as is, anything else as JSON, and an empty string on error.
  void evaluate(const QString &script, std::function<void(const QString &)> done = {});
  Q_INVOKABLE void runJavaScript(const QString &script, const QJSValue &callback = QJSValue());

signals:
  void changed();
  void urlChanged();
  void loadFinished();
  void closeRequested();
  void messageReceived(const QString &message);

private:
  void sync();
  void openPopup(WebKitWebView *popup);

  WebKitWebView *m_view{nullptr};
  GtkWidget *m_window{nullptr};
  GtkWidget *m_header{nullptr};
  GtkWidget *m_backButton{nullptr};
  GtkWidget *m_progressBar{nullptr};
  QList<GtkWidget *> m_popups;
  QString m_windowTitle;
  QUrl m_url;
  QString m_title;
  bool m_loading{false};
  int m_progress{0};
  bool m_canGoBack{false};
};
