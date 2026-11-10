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

#include "appearance/appearance_settings.h"
#include "window_state.h"

#include <QGuiApplication>
#include <QSettings>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>
#include <QWindow>
#include <qpa/qwindowsysteminterface.h>

class ArtworkPauseTest : public QObject {
    Q_OBJECT
    QTemporaryDir directory;

    static void setApplicationState(Qt::ApplicationState state) {
        QWindowSystemInterface::handleApplicationStateChanged(state);
        QWindowSystemInterface::flushWindowSystemEvents();
    }

private slots:
    void initTestCase() {
        QVERIFY(directory.isValid());
        QCoreApplication::setOrganizationName(QStringLiteral("OrchardTests"));
        QCoreApplication::setApplicationName(QStringLiteral("ArtworkPauseTest"));
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, directory.path());
    }
    void cleanup() { QSettings().clear(); }

    void settingPersistsAndResets() {
        AppearanceSettings settings;
        QVERIFY(settings.pauseArtworkInBackground());
        QSignalSpy changes(&settings, &AppearanceSettings::changed);
        settings.setPauseArtworkInBackground(false);
        QCOMPARE(changes.size(), 1);
        QSettings().sync();
        QVERIFY(!AppearanceSettings().pauseArtworkInBackground());
        settings.reset();
        QVERIFY(settings.pauseArtworkInBackground());
    }

    // Issue 248: a visible but unfocused window froze motion artwork with no way to opt out.
    void unfocusedWindowKeepsArtworkWhenOptedOut() {
        QWindow window;
        window.resize(320, 240);
        WindowState state;
        state.setWindow(&window);
        // Hidden pauses artwork in both modes.
        QVERIFY(state.artworkIdle());
        state.setPauseArtworkInBackground(false);
        QVERIFY(state.artworkIdle());

        window.show();
        QVERIFY(QTest::qWaitForWindowExposed(&window));
        setApplicationState(Qt::ApplicationActive);
        QTRY_VERIFY(!state.artworkIdle());

        setApplicationState(Qt::ApplicationInactive);
        QTRY_VERIFY_WITH_TIMEOUT(state.unfocused(), 5000);
        QVERIFY(!state.artworkIdle());
        state.setPauseArtworkInBackground(true);
        QVERIFY(state.artworkIdle());
        setApplicationState(Qt::ApplicationActive);
        QTRY_VERIFY(!state.artworkIdle());
    }
};

int main(int argc, char **argv) {
    QGuiApplication application(argc, argv);
    ArtworkPauseTest test;
    return QTest::qExec(&test, argc, argv);
}
#include "artwork_pause_test.moc"
