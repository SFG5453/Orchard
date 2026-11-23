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

#pragma once

#include <QObject>
#include <QPointer>

class PlaybackController;
class QAction;
class QMenu;
class QMenuBar;
class QWindow;

// The macOS menu bar and Dock menu. The window's QML handles Settings and fullscreen.
class MacMenus final : public QObject
{
public:
    explicit MacMenus(PlaybackController *playback, QObject *parent = nullptr);
    ~MacMenus() override;

    void setWindow(QWindow *window);

private:
    void refresh();
    void callWindow(const char *method);

    PlaybackController *m_playback = nullptr;
    QPointer<QWindow> m_window;
    QMenuBar *m_menuBar = nullptr;
    QMenu *m_dock = nullptr;
    QAction *m_nowPlaying = nullptr;
    QList<QAction *> m_toggles;
    QList<QAction *> m_previous;
    QList<QAction *> m_next;
};
