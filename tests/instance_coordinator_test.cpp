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

#include "instance_coordinator.h"

#include <QCoreApplication>
#include <QProcess>
#include <QTest>

class InstanceCoordinatorTest final : public QObject {
  Q_OBJECT

private slots:
  void secondLaunchActivatesFirst() {
    InstanceCoordinator first;
    QCOMPARE(first.start(), InstanceCoordinator::StartResult::Primary);

    int activations = 0;
    first.setActivationHandler([&activations] { ++activations; });

    QProcess second;
    second.start(QCoreApplication::applicationFilePath(),
                 {QStringLiteral("--activate"), QCoreApplication::applicationName()});
    // Keep this side's event loop running: on Windows the second launch only exits once we read its pipe.
    QTRY_VERIFY_WITH_TIMEOUT(second.state() == QProcess::NotRunning, 5000);
    QCOMPARE(second.exitStatus(), QProcess::NormalExit);
    QCOMPARE(second.exitCode(), 0);
    QTRY_COMPARE(activations, 1);
  }

  void startupLinksWaitForHandler() {
    InstanceCoordinator first;
    const QString uri = QStringLiteral("https://youtu.be/abcdefghijk?si=share&list=PL123");
    QCOMPARE(first.start({QStringLiteral("--from-manifest"), uri}), InstanceCoordinator::StartResult::Primary);
    int activations = 0;
    first.setActivationHandler([&activations] { ++activations; });
    QCOMPARE(activations, 1);
    QStringList received;
    first.setLinkHandler([&received](const QString &link) { received.append(link); });
    QCOMPARE(received, QStringList{uri});
    first.setLinkHandler([&received](const QString &link) { received.append(link); });
    QCOMPARE(received.size(), 1);
  }

  void forwardedLinksWaitForWindow() {
    InstanceCoordinator first;
    QCOMPARE(first.start(), InstanceCoordinator::StartResult::Primary);
    const QStringList links{QStringLiteral("https://music.youtube.com/watch?v=abcdefghijk&si=share"),
                            QStringLiteral("orchard://playlist?list=PL123")};
    QProcess second;
    second.start(QCoreApplication::applicationFilePath(),
                 QStringList{QStringLiteral("--activate"), QCoreApplication::applicationName()} + links);
    // Keep this side's event loop running: on Windows the second launch only exits once we read its pipe.
    QTRY_VERIFY_WITH_TIMEOUT(second.state() == QProcess::NotRunning, 5000);
    QCOMPARE(second.exitCode(), 0);
    QCoreApplication::processEvents();
    QStringList received;
    first.setLinkHandler([&received](const QString &uri) { received.append(uri); });
    int activations = 0;
    first.setActivationHandler([&activations] { ++activations; });
    QTRY_COMPARE(received, links);
    QTRY_COMPARE(activations, 1);
  }
};

int main(int argc, char **argv) {
  QCoreApplication app(argc, argv);
  QCoreApplication::setOrganizationName(QStringLiteral("SFG545Test"));
  if (app.arguments().size() > 1 && app.arguments().at(1) == QStringLiteral("--activate")) {
    QCoreApplication::setApplicationName(app.arguments().value(2));
    InstanceCoordinator second;
    return second.start(app.arguments().mid(3)) == InstanceCoordinator::StartResult::Forwarded ? 0 : 1;
  }
  // Give this run its own lock; parallel test jobs are not roommates.
  QCoreApplication::setApplicationName(QStringLiteral("OrchardInstanceTest-%1").arg(app.applicationPid()));
  InstanceCoordinatorTest test;
  return QTest::qExec(&test, argc, argv);
}

#include "instance_coordinator_test.moc"
