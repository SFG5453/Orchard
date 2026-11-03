/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "appearance/appearance_settings.h"
#include "appearance/ui_scaling.h"

#include <QFile>
#include <QGuiApplication>
#include <QImage>
#include <QProcess>
#include <QQuickItem>
#include <QQuickWindow>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QSettings>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>
#include <cmath>
#include <limits>
#include <memory>

static void configureSettings(const QString &path) {
    QCoreApplication::setOrganizationName(QStringLiteral("OrchardTests"));
    QCoreApplication::setApplicationName(QStringLiteral("UiScalingTest"));
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, path);
}

class UiScalingTest : public QObject {
    Q_OBJECT
    QTemporaryDir directory;

private slots:
    void initTestCase() {
        QVERIFY(directory.isValid());
        configureSettings(directory.path());
    }
    void cleanup() { QSettings().clear(); }

    void persistenceAndRestartNotice() {
        AppearanceSettings settings;
        QCOMPARE(settings.uiScale(), 1.0);
        QVERIFY(!settings.uiScaleRestartRequired());
        QSignalSpy changes(&settings, &AppearanceSettings::changed);
        settings.setUiScale(1.5);
        QCOMPARE(changes.size(), 1);
        QVERIFY(settings.uiScaleRestartRequired());
        settings.setUiScale(1.5);
        QCOMPARE(changes.size(), 1);
        QSettings().sync();
        AppearanceSettings reopened;
        QCOMPARE(reopened.uiScale(), 1.5);
        QVERIFY(!reopened.uiScaleRestartRequired());
        settings.reset();
        QCOMPARE(settings.uiScale(), 1.5);
        settings.setUiScale(1.0);
        QVERIFY(!settings.uiScaleRestartRequired());
    }

    void validateValues() {
        AppearanceSettings settings;
        settings.setUiScale(99);
        QCOMPARE(settings.uiScale(), 2.5);
        settings.setUiScale(-1);
        QCOMPARE(settings.uiScale(), 0.75);
        settings.setUiScale(std::numeric_limits<double>::quiet_NaN());
        settings.setUiScale(std::numeric_limits<double>::infinity());
        QCOMPARE(settings.uiScale(), 0.75);
        settings.setUiScale(1.25000001);
        QCOMPARE(settings.uiScale(), 1.25);
    }

    void validateStoredValues_data() {
        QTest::addColumn<QVariant>("stored");
        QTest::addColumn<double>("expected");
        QTest::newRow("invalid") << QVariant("bad") << 1.0;
        QTest::newRow("nan") << QVariant(std::numeric_limits<double>::quiet_NaN()) << 1.0;
        QTest::newRow("infinity") << QVariant(std::numeric_limits<double>::infinity()) << 1.0;
        QTest::newRow("too-small") << QVariant(0.1) << 0.75;
        QTest::newRow("too-large") << QVariant(10.0) << 2.5;
        QTest::newRow("fractional") << QVariant(1.25) << 1.25;
    }
    void validateStoredValues() {
        QFETCH(QVariant, stored);
        QFETCH(double, expected);
        QSettings().setValue(QStringLiteral("appearance/uiScale"), stored);
        QCOMPARE(UiScaling::savedScale(), expected);
        QCOMPARE(AppearanceSettings().uiScale(), expected);
    }

    void autoFactor_data() {
        QTest::addColumn<QSize>("pixels");
        QTest::addColumn<double>("systemRatio");
        QTest::addColumn<double>("expected");
        QTest::newRow("1080p") << QSize(1920, 1080) << 1.0 << 1.0;
        QTest::newRow("1200p") << QSize(1920, 1200) << 1.0 << 1.0;
        QTest::newRow("1440p") << QSize(2560, 1440) << 1.0 << 1.25;
        QTest::newRow("1600p") << QSize(2560, 1600) << 1.0 << 1.5;
        QTest::newRow("1800p") << QSize(3200, 1800) << 1.0 << 1.75;
        QTest::newRow("4k") << QSize(3840, 2160) << 1.0 << 2.0;
        QTest::newRow("4k-portrait") << QSize(2160, 3840) << 1.0 << 2.0;
        QTest::newRow("5k") << QSize(5120, 2880) << 1.0 << 2.5;
        QTest::newRow("8k") << QSize(7680, 4320) << 1.0 << 2.5;
        QTest::newRow("4k-system-scaled") << QSize(3840, 2160) << 1.5 << 1.0;
        QTest::newRow("empty") << QSize() << 1.0 << 1.0;
    }
    void autoFactor() {
        QFETCH(QSize, pixels);
        QFETCH(double, systemRatio);
        QFETCH(double, expected);
        QCOMPARE(UiScaling::autoFactor(pixels, systemRatio), expected);
    }

    void autoSetting() {
        AppearanceSettings settings;
        QVERIFY(settings.autoUiScale());
        settings.setAutoUiScale(false);
        QVERIFY(settings.uiScaleRestartRequired());
        QVERIFY(!UiScaling::autoEnabled());
        settings.setAutoUiScale(true);
        QVERIFY(!settings.uiScaleRestartRequired());
    }

    void qtScaling_data() {
        QTest::addColumn<double>("scale");
        QTest::addColumn<QString>("environment");
        QTest::addColumn<QString>("screen");
        QTest::addColumn<bool>("autoScale");
        QTest::addColumn<double>("expected");
        const QString small = QStringLiteral("800x600");
        QTest::newRow("system-default") << 1.0 << QString("1") << small << true << 1.0;
        QTest::newRow("fractional") << 1.25 << QString("1") << small << true << 1.25;
        QTest::newRow("4k") << 2.0 << QString("1") << small << true << 2.0;
        QTest::newRow("maximum") << 2.5 << QString("1") << small << true << 2.5;
        QTest::newRow("compact") << 0.75 << QString("1") << small << true << 0.75;
        QTest::newRow("external-scale") << 1.25 << QString("2") << small << true << 2.5;
        QTest::newRow("invalid-external-scale") << 1.5 << QString("invalid") << small << true << 1.5;
        QTest::newRow("auto-4k-screen") << 1.0 << QString("1") << QStringLiteral("3840x2160") << true << 2.0;
        QTest::newRow("auto-times-manual") << 1.2 << QString("1") << QStringLiteral("3840x2160") << true << 2.4;
        QTest::newRow("auto-off") << 1.0 << QString("1") << QStringLiteral("3840x2160") << false << 1.0;
    }
    void qtScaling() {
        QFETCH(double, scale);
        QFETCH(QString, environment);
        QFETCH(QString, screen);
        QFETCH(bool, autoScale);
        QFETCH(double, expected);
        QSettings().setValue(QStringLiteral("appearance/uiScale"), scale);
        QSettings().setValue(QStringLiteral("appearance/autoUiScale"), autoScale);
        QSettings().sync();
        const QStringList size = screen.split(QLatin1Char('x'));
        QFile config(directory.filePath(QStringLiteral("screens.json")));
        QVERIFY(config.open(QIODevice::WriteOnly | QIODevice::Truncate));
        config.write(QStringLiteral(R"({"screens":[{"name":"main","x":0,"y":0,"width":%1,"height":%2,"logicalDpi":96,"dpr":1}]})")
                         .arg(size.at(0), size.at(1)).toUtf8());
        config.close();
        auto env = QProcessEnvironment::systemEnvironment();
        env.insert(QStringLiteral("QT_QPA_PLATFORM"), QStringLiteral("offscreen:configfile=") + config.fileName());
        env.insert(QStringLiteral("QT_QUICK_BACKEND"), QStringLiteral("software"));
        env.insert(QStringLiteral("QT_SCALE_FACTOR"), environment);
        env.insert(QStringLiteral("QT_SCALE_FACTOR_ROUNDING_POLICY"), QStringLiteral("PassThrough"));
        env.insert(QStringLiteral("QT_ENABLE_HIGHDPI_SCALING"), QStringLiteral("1"));
        env.remove(QStringLiteral("QT_SCREEN_SCALE_FACTORS"));
        env.remove(QStringLiteral("QT_FONT_DPI"));
        QProcess process;
        process.setProcessEnvironment(env);
        process.start(QCoreApplication::applicationFilePath(),
                      {QStringLiteral("--probe"), directory.path(), QString::number(expected)});
        QVERIFY(process.waitForFinished(15000));
        QVERIFY2(process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0,
                 process.readAllStandardError().constData());
    }
};

static int probe(int argc, char **argv) {
    configureSettings(QString::fromLocal8Bit(argv[2]));
    UiScaling::configure();
    QGuiApplication application(argc, argv);
    UiScaling::applyAuto();
    QQmlEngine engine;
    QQmlComponent component(&engine);
    component.setData(R"(
        import QtQuick
        import QtQuick.Controls
        Window {
            width: 640; height: 480; visible: true
            Rectangle { anchors.fill: parent; color: "#204060" }
            Text { x: 20; y: 20; text: "Interface scaling"; font.pixelSize: 20 }
            Popup {
                objectName: "popup"
                parent: Overlay.overlay
                anchors.centerIn: parent
                width: 240; height: 120
                visible: true
            }
        }
    )", QUrl());
    std::unique_ptr<QObject> object(component.create());
    auto *window = qobject_cast<QQuickWindow *>(object.get());
    if (!window) { qWarning() << component.errors(); return 1; }
    QTest::qWait(100);
    const double expected = QByteArray(argv[3]).toDouble();
    const auto *popup = window->findChild<QObject *>(QStringLiteral("popup"));
    const QImage image = window->grabWindow();
    if (!qFuzzyCompare(window->devicePixelRatio(), expected)
        || image.size() != QSize(qRound(640 * expected), qRound(480 * expected))
        || !popup || !qFuzzyCompare(popup->property("x").toDouble(), 200.0)
        || !qFuzzyCompare(popup->property("y").toDouble(), 180.0)) {
        qWarning() << "DPR" << window->devicePixelRatio() << "expected" << expected
                   << "rendered" << image.size();
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 4 && QByteArray(argv[1]) == "--probe")
        return probe(argc, argv);
    QCoreApplication application(argc, argv);
    UiScalingTest test;
    return QTest::qExec(&test, argc, argv);
}
#include "ui_scaling_test.moc"
