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

#include "mac_menus.h"

#include "playback/playback_controller.h"

#include <QAction>
#include <QCoreApplication>
#include <QKeySequence>
#include <QMenu>
#include <QMenuBar>
#include <QWindow>

namespace {
QString text(const char *source)
{
    return QCoreApplication::translate("MacMenus", source);
}
}

MacMenus::MacMenus(PlaybackController *playback, QObject *parent)
    : QObject(parent), m_playback(playback)
{
    // A parentless menu bar becomes the application's global one.
    m_menuBar = new QMenuBar(nullptr);

    QMenu *app = m_menuBar->addMenu(text("Orchard"));
    QAction *settings = app->addAction(text("Settings…"), this, [this] { callWindow("openSettings"); });
    settings->setMenuRole(QAction::PreferencesRole);
    settings->setShortcut(QKeySequence::Preferences);
    QAction *quit = app->addAction(text("Quit Orchard"), qApp, &QCoreApplication::quit);
    quit->setMenuRole(QAction::QuitRole);
    quit->setShortcut(QKeySequence::Quit);

    // Space and Cmd+arrows stay with text fields, so playback items carry no shortcuts.
    QMenu *controls = m_menuBar->addMenu(text("Controls"));
    m_dock = new QMenu;
    m_nowPlaying = m_dock->addAction(QString());
    m_nowPlaying->setEnabled(false);
    m_dock->addSeparator();
    for (QMenu *menu : {controls, m_dock}) {
        m_toggles.append(menu->addAction(text("Play"), m_playback, [this] { m_playback->toggle(); }));
        m_next.append(menu->addAction(text("Next"), m_playback, [this] { m_playback->next(); }));
        m_previous.append(menu->addAction(text("Previous"), m_playback, [this] { m_playback->previous(); }));
    }
    m_dock->setAsDockMenu();

    QMenu *window = m_menuBar->addMenu(text("Window"));
    window->addAction(text("Minimize"), QKeySequence(QStringLiteral("Ctrl+M")), this, [this] {
        if (m_window)
            m_window->showMinimized();
    });
    window->addAction(text("Zoom"), this, [this] { callWindow("toggleMaximized"); });
    window->addSeparator();
    // Qt's Meta is the Control key on macOS and Ctrl is Command.
    window->addAction(text("Toggle Full Screen"), QKeySequence(QStringLiteral("Meta+Ctrl+F")), this,
                      [this] { callWindow("toggleFullScreen"); });
    window->addSeparator();
    window->addAction(text("Close Window"), QKeySequence::Close, this, [this] {
        if (m_window)
            m_window->close();
    });

    connect(m_playback, &PlaybackController::stateChanged, this, &MacMenus::refresh);
    refresh();
}

MacMenus::~MacMenus()
{
    delete m_dock;
    delete m_menuBar;
}

void MacMenus::setWindow(QWindow *window)
{
    m_window = window;
}

void MacMenus::callWindow(const char *method)
{
    if (!m_window)
        return;
    m_window->show();
    m_window->raise();
    m_window->requestActivate();
    QMetaObject::invokeMethod(m_window, method);
}

void MacMenus::refresh()
{
    // stateChanged fires on every position tick; QAction setters no-op on equal values.
    const QVariantMap track = m_playback->shownTrack();
    const QString title = track.value(QStringLiteral("title")).toString();
    const QString artist = track.value(QStringLiteral("artist")).toString();
    const QString summary = artist.isEmpty() ? title : title + QStringLiteral(" · ") + artist;
    m_nowPlaying->setText(QString(summary).replace(QLatin1Char('&'), QStringLiteral("&&")));
    m_nowPlaying->setVisible(!title.isEmpty());
    for (QAction *action : std::as_const(m_toggles)) {
        action->setText(m_playback->shownPlaying() ? text("Pause") : text("Play"));
        action->setEnabled(!title.isEmpty());
    }
    for (QAction *action : std::as_const(m_next))
        action->setEnabled(m_playback->shownCanGoNext());
    for (QAction *action : std::as_const(m_previous))
        action->setEnabled(m_playback->shownCanGoPrevious());
}
