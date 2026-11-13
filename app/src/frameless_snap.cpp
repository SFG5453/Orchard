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

#include "frameless_snap.h"

#include <QGuiApplication>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

void FramelessSnap::attach(QWindow *window)
{
    m_window = window;
    applyStyle();
}

void FramelessSnap::setActive(bool active)
{
    m_active = active;
#ifdef Q_OS_WIN
    if (active && !m_installed) {
        qGuiApp->installNativeEventFilter(this);
        m_installed = true;
    }
#endif
    applyStyle();
}

void FramelessSnap::applyStyle()
{
#ifdef Q_OS_WIN
    if (!m_active || !m_window || !m_window->handle())
        return;
    const auto hwnd = reinterpret_cast<HWND>(m_window->winId());
    const LONG_PTR want = WS_THICKFRAME | WS_CAPTION | WS_MAXIMIZEBOX | WS_MINIMIZEBOX;
    const LONG_PTR style = GetWindowLongPtr(hwnd, GWL_STYLE);
    if ((style & want) == want)
        return;
    SetWindowLongPtr(hwnd, GWL_STYLE, style | want);
    SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
                 SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
#endif
}

bool FramelessSnap::nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result)
{
#ifdef Q_OS_WIN
    if (!m_active || !m_window || eventType != "windows_generic_MSG")
        return false;
    auto *msg = static_cast<MSG *>(message);
    if (msg->hwnd != reinterpret_cast<HWND>(m_window->winId()))
        return false;
    if (msg->message == WM_WINDOWPOSCHANGED) {
        applyStyle();
        return false;
    }
    if (msg->message != WM_NCCALCSIZE || !msg->wParam)
        return false;
    // Client area covers the whole window; a maximized window is trimmed by the frame it overhangs.
    if (IsZoomed(msg->hwnd)) {
        auto *params = reinterpret_cast<NCCALCSIZE_PARAMS *>(msg->lParam);
        const int padX = GetSystemMetrics(SM_CXSIZEFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
        const int padY = GetSystemMetrics(SM_CYSIZEFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
        params->rgrc[0].left += padX;
        params->rgrc[0].right -= padX;
        params->rgrc[0].top += padY;
        params->rgrc[0].bottom -= padY;
    }
    *result = 0;
    return true;
#else
    Q_UNUSED(eventType)
    Q_UNUSED(message)
    Q_UNUSED(result)
    return false;
#endif
}
