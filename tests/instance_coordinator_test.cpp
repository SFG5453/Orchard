/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
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
    QVERIFY(second.waitForFinished(5000));
    QCOMPARE(second.exitStatus(), QProcess::NormalExit);
    QCOMPARE(second.exitCode(), 0);
    QTRY_COMPARE(activations, 1);
  }
};

int main(int argc, char **argv) {
  QCoreApplication app(argc, argv);
  QCoreApplication::setOrganizationName(QStringLiteral("SFG545Test"));
  if (app.arguments().size() > 1 && app.arguments().at(1) == QStringLiteral("--activate")) {
    QCoreApplication::setApplicationName(app.arguments().value(2));
    InstanceCoordinator second;
    return second.start() == InstanceCoordinator::StartResult::Forwarded ? 0 : 1;
  }
  // Give this run its own lock; parallel test jobs are not roommates.
  QCoreApplication::setApplicationName(QStringLiteral("OrchardInstanceTest-%1").arg(app.applicationPid()));
  InstanceCoordinatorTest test;
  return QTest::qExec(&test, argc, argv);
}

#include "instance_coordinator_test.moc"
