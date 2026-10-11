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

#include "screen_wake_lock.h"

#if defined(Q_OS_LINUX)
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#elif defined(Q_OS_WIN)
#include <windows.h>
#elif defined(Q_OS_MACOS)
#include <IOKit/pwr_mgt/IOPMLib.h>
#endif

namespace {
const QString kReason = QStringLiteral("Playing music in the fullscreen player");

#if defined(Q_OS_LINUX)
// KDE and most desktops implement the freedesktop service; GNOME only the session manager.
QDBusMessage inhibitCall(bool gnome)
{
    if (gnome) {
        QDBusMessage call = QDBusMessage::createMethodCall(QStringLiteral("org.gnome.SessionManager"),
            QStringLiteral("/org/gnome/SessionManager"), QStringLiteral("org.gnome.SessionManager"),
            QStringLiteral("Inhibit"));
        // Flag 8 inhibits marking the session idle, which is what blanks the screen.
        call << QStringLiteral("Orchard") << quint32(0) << kReason << quint32(8);
        return call;
    }
    QDBusMessage call = QDBusMessage::createMethodCall(QStringLiteral("org.freedesktop.ScreenSaver"),
        QStringLiteral("/org/freedesktop/ScreenSaver"), QStringLiteral("org.freedesktop.ScreenSaver"),
        QStringLiteral("Inhibit"));
    call << QStringLiteral("Orchard") << kReason;
    return call;
}

void uninhibit(quint32 cookie, bool gnome)
{
    QDBusMessage call = gnome
        ? QDBusMessage::createMethodCall(QStringLiteral("org.gnome.SessionManager"),
              QStringLiteral("/org/gnome/SessionManager"), QStringLiteral("org.gnome.SessionManager"),
              QStringLiteral("Uninhibit"))
        : QDBusMessage::createMethodCall(QStringLiteral("org.freedesktop.ScreenSaver"),
              QStringLiteral("/org/freedesktop/ScreenSaver"), QStringLiteral("org.freedesktop.ScreenSaver"),
              QStringLiteral("UnInhibit"));
    call << cookie;
    QDBusConnection::sessionBus().call(call, QDBus::NoBlock);
}
#endif
} // namespace

ScreenWakeLock::ScreenWakeLock(QObject *parent) : QObject(parent) {}

ScreenWakeLock::~ScreenWakeLock()
{
    if (m_active)
        release();
}

void ScreenWakeLock::setActive(bool active)
{
    if (active == m_active)
        return;
    m_active = active;
    ++m_generation;
    if (active)
        acquire();
    else
        release();
    emit activeChanged();
}

void ScreenWakeLock::acquire()
{
#if defined(Q_OS_LINUX)
    inhibit(false);
#elif defined(Q_OS_WIN)
    SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED);
#elif defined(Q_OS_MACOS)
    const CFStringRef reason = kReason.toCFString();
    IOPMAssertionID assertion = kIOPMNullAssertionID;
    if (IOPMAssertionCreateWithName(kIOPMAssertionTypeNoDisplaySleep, kIOPMAssertionLevelOn, reason, &assertion)
        == kIOReturnSuccess)
        m_cookie = assertion;
    CFRelease(reason);
#endif
}

void ScreenWakeLock::release()
{
#if defined(Q_OS_LINUX)
    if (m_cookie != 0)
        uninhibit(m_cookie, m_gnome);
    m_cookie = 0;
#elif defined(Q_OS_WIN)
    SetThreadExecutionState(ES_CONTINUOUS);
#elif defined(Q_OS_MACOS)
    if (m_cookie != kIOPMNullAssertionID)
        IOPMAssertionRelease(m_cookie);
    m_cookie = kIOPMNullAssertionID;
#endif
}

#if defined(Q_OS_LINUX)
void ScreenWakeLock::inhibit(bool gnome)
{
    const quint64 generation = m_generation;
    auto *watcher = new QDBusPendingCallWatcher(QDBusConnection::sessionBus().asyncCall(inhibitCall(gnome)), this);
    connect(watcher, &QDBusPendingCallWatcher::finished, this, [this, generation, gnome](QDBusPendingCallWatcher *call) {
        call->deleteLater();
        const QDBusPendingReply<quint32> reply = *call;
        if (reply.isError()) {
            if (!gnome && generation == m_generation)
                inhibit(true);
            return;
        }
        if (generation != m_generation) {
            uninhibit(reply.value(), gnome);
            return;
        }
        m_cookie = reply.value();
        m_gnome = gnome;
    });
}
#endif
