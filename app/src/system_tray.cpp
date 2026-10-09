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

#include "system_tray.h"

#include "auth/auth_manager.h"
#include "playback/playback_controller.h"

#include <QAction>
#include <QApplication>
#include <QIcon>
#include <QMenu>
#include <QSystemTrayIcon>
#include <QWindow>

namespace {
QString menuText(QString text)
{
    // Menus treat '&' as a mnemonic marker; "Simon & Garfunkel" deserves both names.
    return text.replace(QLatin1Char('&'), QStringLiteral("&&"));
}
}

SystemTray::SystemTray(PlaybackController *playback, AuthManager *auth, QObject *parent)
    : QObject(parent), m_playback(playback), m_auth(auth)
{
    // INI escapes a "general" group to [%General] and reads it back as "General", so avoid it.
    // Qt's config format: where "general" is a reserved word and nobody tells you.
    const QVariant stranded = m_settings.value(QStringLiteral("General/closeToTray"));
    m_settings.beginGroup(QStringLiteral("tray"));
    m_closeToTray = m_settings.value(QStringLiteral("closeToTray"), stranded.isValid() ? stranded : false).toBool();

    if (!QSystemTrayIcon::isSystemTrayAvailable())
        return;

    // Quit is always explicit, so a hidden main window never ends the session.
    QApplication::setQuitOnLastWindowClosed(false);

    m_menu = new QMenu;
    m_nowPlaying = m_menu->addAction(QString());
    m_nowPlaying->setEnabled(false);
    m_menu->addSeparator();
    m_toggle = m_menu->addAction(tr("Play"), m_playback, &PlaybackController::toggle);
    m_previous = m_menu->addAction(tr("Previous"), m_playback, &PlaybackController::previous);
    m_next = m_menu->addAction(tr("Next"), m_playback, &PlaybackController::next);
    m_menu->addSeparator();
    m_showHide = m_menu->addAction(tr("Show Orchard"), this, &SystemTray::toggleWindow);
    m_menu->addAction(tr("Quit Orchard"), qApp, &QCoreApplication::quit);

    m_icon = new QSystemTrayIcon(
        QIcon(QStringLiteral(":/qt/qml/Orchard/app/qml/assets/orchard-logo.png")), this);
    m_icon->setContextMenu(m_menu);
    connect(m_icon, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger)
            toggleWindow();
    });
    connect(m_playback, &PlaybackController::stateChanged, this, &SystemTray::refresh);
    connect(m_auth, &AuthManager::statusChanged, this, &SystemTray::refresh);

    refresh();
    m_icon->show();
}

SystemTray::~SystemTray()
{
    delete m_menu;
}

bool SystemTray::available() const
{
    return m_icon != nullptr;
}

void SystemTray::setCloseToTray(bool value)
{
    if (value == m_closeToTray)
        return;
    m_closeToTray = value;
    m_settings.setValue(QStringLiteral("closeToTray"), value);
    emit closeToTrayChanged();
    refresh();
}

void SystemTray::setWindow(QWindow *window)
{
    if (m_window)
        disconnect(m_window, nullptr, this, nullptr);
    m_window = window;
    if (m_window)
        connect(m_window, &QWindow::visibleChanged, this, &SystemTray::refresh);
    refresh();
}

void SystemTray::showWindow()
{
    if (!m_window)
        return;
    m_window->setWindowStates(m_window->windowStates() & ~Qt::WindowMinimized);
    m_window->show();
    m_window->raise();
    m_window->requestActivate();
}

void SystemTray::toggleWindow()
{
    if (!m_window)
        return;
    if (m_closeToTray && m_auth->isSignedIn() && m_window->isVisible()
        && !(m_window->windowStates() & Qt::WindowMinimized))
        m_window->hide();
    else
        showWindow();
}

void SystemTray::refresh()
{
    if (!m_icon)
        return;

    // stateChanged fires on every position tick; QAction setters no-op on equal values.
    const QVariantMap track = m_playback->shownTrack();
    const QString title = track.value(QStringLiteral("title")).toString();
    const QString artist = track.value(QStringLiteral("artist")).toString();
    const bool hasTrack = !title.isEmpty();
    const QString summary = artist.isEmpty() ? title : title + QStringLiteral(" · ") + artist;

    m_nowPlaying->setText(menuText(summary));
    m_nowPlaying->setVisible(hasTrack);
    m_toggle->setText(m_playback->shownPlaying() ? tr("Pause") : tr("Play"));
    m_toggle->setEnabled(hasTrack);
    m_previous->setEnabled(m_playback->shownCanGoPrevious());
    m_next->setEnabled(m_playback->shownCanGoNext());
    const bool canHide = m_closeToTray && m_auth->isSignedIn();
    m_showHide->setText(canHide && m_window && m_window->isVisible()
                            ? tr("Hide Orchard") : tr("Show Orchard"));

    const QString toolTip = hasTrack ? summary : QStringLiteral("Orchard");
    if (toolTip != m_toolTip) {
        m_toolTip = toolTip;
        m_icon->setToolTip(toolTip);
    }
}
