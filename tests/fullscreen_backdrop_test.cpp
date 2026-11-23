#include "appearance/kawarp_background.h"
#include <QFile>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQmlPropertyMap>
#include <QQuickItem>
#include <QTemporaryDir>
#include <QTest>
#include <cmath>

class FullscreenBackdropTest : public QObject {
  Q_OBJECT
private slots:
  void initTestCase() { qmlRegisterType<KawarpBackground>("Orchard", 1, 0, "KawarpBackground"); }

  void preservesFallbackAnimationState() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    QImage artwork(32, 32, QImage::Format_RGB32);
    artwork.fill(Qt::red);
    const QString path = directory.filePath("art.png");
    QVERIFY(artwork.save(path));
    QQmlEngine engine;
    QQmlPropertyMap appearance;
    appearance.insert(QStringLiteral("backgroundQuality"), QStringLiteral("medium"));
    engine.rootContext()->setContextProperty(QStringLiteral("OrchardAppearance"), &appearance);
    QQmlPropertyMap windowState;
    windowState.insert(QStringLiteral("idle"), false);
    engine.rootContext()->setContextProperty(QStringLiteral("OrchardWindowState"), &windowState);
    QQmlComponent component(&engine);
    const QByteArray qml = QByteArrayLiteral("import QtQuick\nimport \"")
        + QUrl::fromLocalFile(QStringLiteral(ORCHARD_SOURCE_DIR "/app/qml/components/player")).toEncoded()
        + QByteArrayLiteral("\"\nFullscreenBackdrop { width: 800; height: 600; playing: false }");
    component.setData(qml, QUrl());
    QScopedPointer<QObject> root(component.create());
    QVERIFY2(root, qPrintable(component.errorString()));
    auto loader = [&]() -> QQuickItem * {
      for (auto *item : root->findChildren<QQuickItem *>())
        if (item->inherits("QQuickLoader")) return item;
      return nullptr;
    }();
    QVERIFY(loader);
    QVERIFY(loader->property("active").toBool());
    root->setProperty("thumbnail", QUrl::fromLocalFile(path));
    QTRY_VERIFY_WITH_TIMEOUT(!loader->property("active").toBool(), 3000);
    QVERIFY(!loader->property("item").value<QObject *>());
    root->setProperty("fallbackRotation", 137.0);
    root->setProperty("reveal", 0.5);
    QVERIFY(loader->property("active").toBool());
    QObject *fallback = loader->property("item").value<QObject *>();
    QVERIFY(fallback);
    QCOMPARE(fallback->property("rotation").toDouble(), 137.0);
    QTRY_COMPARE_WITH_TIMEOUT(fallback->property("opacity").toDouble(), 0.9, 1000);
    root->setProperty("reveal", 1.0);
    QVERIFY(!loader->property("active").toBool());
    QCOMPARE(root->property("fallbackRotation").toDouble(), 137.0);
    root->setProperty("thumbnail", QUrl());
    QTRY_VERIFY_WITH_TIMEOUT(loader->property("active").toBool(), 2000);
    QVERIFY(loader->property("item").value<QObject *>());
  }

  void qualitySetsWarpScaleAndFrameRate() {
    QQmlEngine engine;
    QQmlPropertyMap appearance;
    appearance.insert(QStringLiteral("backgroundQuality"), QStringLiteral("low"));
    engine.rootContext()->setContextProperty(QStringLiteral("OrchardAppearance"), &appearance);
    QQmlComponent component(&engine);
    const QByteArray qml = QByteArrayLiteral("import QtQuick\nimport \"")
        + QUrl::fromLocalFile(QStringLiteral(ORCHARD_SOURCE_DIR "/app/qml/components/home")).toEncoded()
        + QByteArrayLiteral("\"\nImmersiveBackground { width: 1920; height: 1080 }");
    component.setData(qml, QUrl());
    QScopedPointer<QObject> root(component.create());
    QVERIFY2(root, qPrintable(component.errorString()));
    QCOMPARE(root->property("frameRate").toInt(), 20);
    QCOMPARE(root->property("renderScale").toDouble(), 0.25);
    appearance.insert(QStringLiteral("backgroundQuality"), QStringLiteral("medium"));
    QCOMPARE(root->property("frameRate").toInt(), 30);
    QCOMPARE(root->property("renderScale").toDouble(), 0.4);
    appearance.insert(QStringLiteral("backgroundQuality"), QStringLiteral("high"));
    QCOMPARE(root->property("frameRate").toInt(), 60);
    QCOMPARE(root->property("renderScale").toDouble(), 0.6);
    // The pixel cap wins on large windows.
    root->setProperty("width", 3840);
    root->setProperty("height", 2160);
    QVERIFY(qAbs(root->property("renderScale").toDouble() - std::sqrt(1280.0 * 720 / (3840.0 * 2160))) < 1e-9);
  }
};
QTEST_MAIN(FullscreenBackdropTest)
#include "fullscreen_backdrop_test.moc"
