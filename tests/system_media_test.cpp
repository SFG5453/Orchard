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

#include "system_media_bridge.h"

#include <QDBusConnection>
#include <QDBusMessage>
#include <QKeyEvent>
#include <QWindow>
#include <QtTest>

class SystemMediaTest : public QObject {
  Q_OBJECT

private slots:
  void mediaKeys_data() {
    QTest::addColumn<int>("key");
    QTest::addColumn<QString>("command");
    QTest::newRow("play") << int(Qt::Key_MediaPlay) << QString("play-pause");
    QTest::newRow("toggle") << int(Qt::Key_MediaTogglePlayPause) << QString("play-pause");
    QTest::newRow("pause") << int(Qt::Key_MediaPause) << QString("pause");
    QTest::newRow("stop") << int(Qt::Key_MediaStop) << QString("stop");
    QTest::newRow("next") << int(Qt::Key_MediaNext) << QString("next");
    QTest::newRow("previous") << int(Qt::Key_MediaPrevious) << QString("previous");
  }

  void mediaKeys() {
    QFETCH(int, key);
    QFETCH(QString, command);
    SystemMediaBridge bridge;
    QSignalSpy commands(&bridge, &SystemMediaBridge::commandReceived);
    QWindow window;

    QKeyEvent shortcut(QEvent::ShortcutOverride, key, Qt::NoModifier);
    shortcut.ignore();
    QCoreApplication::sendEvent(&window, &shortcut);
    QVERIFY(shortcut.isAccepted());
    QVERIFY(commands.isEmpty());

    QKeyEvent press(QEvent::KeyPress, key, Qt::NoModifier);
    QCoreApplication::sendEvent(&window, &press);
    QCOMPARE(commands.size(), 1);
    QCOMPARE(commands.first().first().toString(), command);

    for (const auto type : {QEvent::KeyPress, QEvent::KeyRelease}) {
      QKeyEvent repeat(type, key, Qt::NoModifier, {}, true);
      QCoreApplication::sendEvent(&window, &repeat);
    }
    QKeyEvent release(QEvent::KeyRelease, key, Qt::NoModifier);
    QCoreApplication::sendEvent(&window, &release);
    QCoreApplication::processEvents();
    QCOMPARE(commands.size(), 1);
  }

  void leavesOtherKeysAlone() {
    SystemMediaBridge bridge;
    QSignalSpy commands(&bridge, &SystemMediaBridge::commandReceived);
    QWindow window;
    for (const auto key : {Qt::Key_Space, Qt::Key_Left, Qt::Key_Right, Qt::Key_VolumeUp}) {
      QKeyEvent shortcut(QEvent::ShortcutOverride, key, Qt::NoModifier);
      shortcut.ignore();
      QCoreApplication::sendEvent(&window, &shortcut);
      QVERIFY(!shortcut.isAccepted());
      QTest::keyClick(&window, key);
    }
    QVERIFY(commands.isEmpty());
  }

  void stopAndRestart() {
    SystemMediaBridge bridge;
    QSignalSpy commands(&bridge, &SystemMediaBridge::commandReceived);
    QWindow window;
    bridge.stop();
    QTest::keyClick(&window, Qt::Key_MediaNext);
    QVERIFY(commands.isEmpty());
    bridge.start();
    bridge.start();
    QTest::keyClick(&window, Qt::Key_MediaNext);
    QCOMPARE(commands.size(), 1);
  }

  void mediaKeysWithoutRegistration() {
    auto bus = QDBusConnection::sessionBus();
    const QString service = QStringLiteral("org.mpris.MediaPlayer2.Orchard");
    QVERIFY(bus.registerService(service));
    {
      SystemMediaBridge bridge;
      QSignalSpy commands(&bridge, &SystemMediaBridge::commandReceived);
      QWindow window;
      const bool attached = bridge.attached();
      QTest::keyClick(&window, Qt::Key_MediaNext);
      QVERIFY(bus.unregisterService(service));
      QVERIFY(!attached);
      QCOMPARE(commands.size(), 1);
      QCOMPARE(commands.first().first().toString(), QString("next"));
    }
  }

  void systemCommands() {
    SystemMediaBridge bridge;
    QVERIFY2(bridge.attached(), qPrintable(bridge.lastError()));
    QVERIFY(bridge.publish({
        {"track", QVariantMap{{"id", "test-track"}, {"title", "Test track"}}},
        {"isPlaying", true}, {"canGoNext", true}, {"canGoPrevious", true}}));
    QSignalSpy commands(&bridge, &SystemMediaBridge::commandReceived);

    // Shells send these commands even while another application has focus.
    for (const auto &[method, command] : {
             std::pair{"PlayPause", "play-pause"}, {"Next", "next"},
             {"Previous", "previous"}}) {
      const auto message = QDBusMessage::createMethodCall(
          QStringLiteral("org.mpris.MediaPlayer2.Orchard"),
          QStringLiteral("/org/mpris/MediaPlayer2"),
          QStringLiteral("org.mpris.MediaPlayer2.Player"), QString::fromLatin1(method));
      const auto reply = QDBusConnection::sessionBus().call(message, QDBus::Block, 3000);
      QVERIFY2(reply.type() != QDBusMessage::ErrorMessage, qPrintable(reply.errorMessage()));
      QTRY_COMPARE(commands.size(), 1);
      QCOMPARE(commands.takeFirst().first().toString(), QString::fromLatin1(command));
    }
  }
};

QTEST_MAIN(SystemMediaTest)
#include "system_media_test.moc"
