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

#include "window_occlusion.h"

#include <QGuiApplication>
#include <QWindow>

#if defined(Q_OS_LINUX) && defined(ORCHARD_HAVE_XCB)
#include <xcb/xcb.h>
#include <cstdlib>
#elif defined(Q_OS_WIN)
#include <windows.h>
#include <dwmapi.h>
#elif defined(Q_OS_MACOS)
#include "occlusion_watch_mac.h"
#endif

#if defined(Q_OS_WIN)
namespace {
constexpr int kPollMs = 500;

bool isCloaked(HWND hwnd)
{
    BOOL cloaked = FALSE;
    return SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof(cloaked))) && cloaked;
}

// The visible frame excludes the invisible resize borders of top-level windows.
bool frameRect(HWND hwnd, RECT *rect)
{
    if (SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, rect, sizeof(*rect))))
        return true;
    return GetWindowRect(hwnd, rect) != FALSE;
}

bool isOpaqueCover(HWND hwnd)
{
    if (!IsWindowVisible(hwnd) || IsIconic(hwnd) || isCloaked(hwnd))
        return false;
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == GetCurrentProcessId())
        return false;
    const LONG_PTR ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    if (ex & WS_EX_TRANSPARENT)
        return false;
    if (ex & WS_EX_LAYERED) {
        BYTE alpha = 0;
        DWORD flags = 0;
        // Per-pixel alpha windows fail this call and may show what is behind them.
        if (!GetLayeredWindowAttributes(hwnd, nullptr, &alpha, &flags) || (flags & LWA_COLORKEY)
            || ((flags & LWA_ALPHA) && alpha < 255))
            return false;
    }
    return true;
}

bool isCovered(HWND hwnd)
{
    if (isCloaked(hwnd))
        return true;
    RECT self;
    if (IsIconic(hwnd) || !frameRect(hwnd, &self))
        return false;
    HRGN remaining = CreateRectRgnIndirect(&self);
    bool covered = false;
    for (HWND above = GetWindow(hwnd, GW_HWNDPREV); above && !covered; above = GetWindow(above, GW_HWNDPREV)) {
        RECT rect;
        if (!isOpaqueCover(above) || !frameRect(above, &rect))
            continue;
        HRGN other = CreateRectRgnIndirect(&rect);
        covered = CombineRgn(remaining, remaining, other, RGN_DIFF) == NULLREGION;
        DeleteObject(other);
    }
    DeleteObject(remaining);
    return covered;
}
}
#endif

WindowOcclusion::WindowOcclusion(QObject *parent) : QObject(parent)
{
#if defined(Q_OS_WIN)
    m_timer.setInterval(kPollMs);
    connect(&m_timer, &QTimer::timeout, this, &WindowOcclusion::pollWindows);
#endif
}

WindowOcclusion::~WindowOcclusion()
{
    if (m_filterInstalled && qGuiApp)
        qGuiApp->removeNativeEventFilter(this);
}

void WindowOcclusion::setWindow(QWindow *window)
{
    m_window = window;
    if (!window)
        return;
#if defined(Q_OS_LINUX)
    watchX11();
#elif defined(Q_OS_WIN)
    m_timer.start();
    pollWindows();
#elif defined(Q_OS_MACOS)
    m_macWatch = std::make_unique<OcclusionWatch>(window, [this](bool occluded) { setOccluded(occluded); });
#endif
}

void WindowOcclusion::setOccluded(bool value)
{
    if (value == m_occluded)
        return;
    m_occluded = value;
    emit occludedChanged();
}

void WindowOcclusion::watchX11()
{
#if defined(Q_OS_LINUX) && defined(ORCHARD_HAVE_XCB)
    auto *x11 = qGuiApp->nativeInterface<QNativeInterface::QX11Application>();
    if (!x11 || !x11->connection())
        return;
    xcb_connection_t *conn = x11->connection();
    const auto id = static_cast<xcb_window_t>(m_window->winId());
    // Qt owns the window's event mask; add to it rather than replace it.
    xcb_get_window_attributes_reply_t *attrs =
        xcb_get_window_attributes_reply(conn, xcb_get_window_attributes(conn, id), nullptr);
    if (!attrs)
        return;
    const uint32_t mask = attrs->your_event_mask | XCB_EVENT_MASK_VISIBILITY_CHANGE;
    std::free(attrs);
    xcb_change_window_attributes(conn, id, XCB_CW_EVENT_MASK, &mask);
    xcb_flush(conn);
    if (!m_filterInstalled) {
        qGuiApp->installNativeEventFilter(this);
        m_filterInstalled = true;
    }
#endif
}

bool WindowOcclusion::nativeEventFilter(const QByteArray &eventType, void *message, qintptr *)
{
#if defined(Q_OS_LINUX) && defined(ORCHARD_HAVE_XCB)
    if (eventType == "xcb_generic_event_t" && m_window) {
        const auto *event = static_cast<xcb_generic_event_t *>(message);
        if ((event->response_type & 0x7f) == XCB_VISIBILITY_NOTIFY) {
            const auto *visibility = reinterpret_cast<const xcb_visibility_notify_event_t *>(event);
            if (visibility->window == static_cast<xcb_window_t>(m_window->winId()))
                setOccluded(visibility->state == XCB_VISIBILITY_FULLY_OBSCURED);
        }
    }
#else
    Q_UNUSED(eventType)
    Q_UNUSED(message)
#endif
    return false;
}

void WindowOcclusion::pollWindows()
{
#if defined(Q_OS_WIN)
    if (m_window)
        setOccluded(isCovered(reinterpret_cast<HWND>(m_window->winId())));
#endif
}
