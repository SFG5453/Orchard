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

#include <QGuiApplication>
#include <QQuickStyle>
#include <QFile>
#include <QEventLoop>
#include <QKeyEvent>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQmlPropertyMap>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTextStream>
#include <QTimer>
#include <QUrl>
#include <memory>

class SettingsMap final : public QQmlPropertyMap {
public:
  SettingsMap() : QQmlPropertyMap(this, nullptr) {}
};

static bool verifyScaleControls(QQmlEngine &engine, QTextStream &output) {
  SettingsMap appearance;
  const QVariantMap values{
      {"layoutStyle", "glade"}, {"uiScale", 1.0}, {"uiScaleRestartRequired", false},
      {"showBitrate", false}, {"fullscreenWindowed", false}, {"fullscreenAutoHide", true},
      {"fullscreenPulse", true}, {"artistVideoBackdrop", false}, {"animatedArtworkEnabled", true},
      {"animatedCollageEnabled", false}, {"artworkSource", "apple_music"},
      {"immersiveBackground", false}, {"speed", 1.38}, {"intensity", 0.92},
      {"saturation", 1.24}, {"brightness", 1.0}, {"availableMirrors", QVariantList{}}};
  for (auto it = values.cbegin(); it != values.cend(); ++it)
    appearance.insert(it.key(), it.value());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAppearance"), &appearance);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAppleMusic"),
                                         QVariantMap{{"connected", false}});
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardLyrics"),
      QVariantMap{{"providerSettings", QVariantMap{{"providers", QVariantList{}}}}});
  QQuickWindow window;
  window.resize(640, 480);
  QQmlComponent component(&engine,
      QUrl(QStringLiteral("qrc:/qt/qml/Orchard/app/qml/components/settings/AppearanceSection.qml")));
  std::unique_ptr<QObject> object(component.create());
  auto *section = qobject_cast<QQuickItem *>(object.get());
  if (!section) {
    for (const auto &error : component.errors())
      output << error.toString() << '\n';
    return false;
  }
  section->setParentItem(window.contentItem());
  section->setWidth(600);
  window.show();
  QEventLoop loop;
  QTimer::singleShot(400, &loop, &QEventLoop::quit);
  loop.exec();
  auto *control = section->findChild<QObject *>(QStringLiteral("uiScaleControl"));
  auto *slider = control ? control->findChild<QQuickItem *>(QStringLiteral("slider")) : nullptr;
  auto *reset = section->findChild<QObject *>(QStringLiteral("resetUiScale"));
  if (!slider || !reset) return false;
  slider->forceActiveFocus();
  QKeyEvent press(QEvent::KeyPress, Qt::Key_Right, Qt::NoModifier);
  QKeyEvent release(QEvent::KeyRelease, Qt::Key_Right, Qt::NoModifier);
  QCoreApplication::sendEvent(&window, &press);
  QCoreApplication::sendEvent(&window, &release);
  if (!qFuzzyCompare(appearance.value(QStringLiteral("uiScale")).toDouble(), 1.05)) {
    output << "Interface scale slider did not persist a keyboard adjustment.\n";
    return false;
  }
  QMetaObject::invokeMethod(reset, "clicked");
  if (appearance.value(QStringLiteral("uiScale")).toDouble() != 1.0) return false;
  appearance.insert(QStringLiteral("uiScale"), 1.5);
  if (control->property("displayValue").toString() != QStringLiteral("150%")) return false;
  return true;
}

static bool verifyTranslationControls(QQmlEngine &engine, QTextStream &output) {
  SettingsMap translation;
  const QVariantMap values{{"enabled", false}, {"targetLanguage", "eng"}, {"quality", "standard"},
    {"provider", "custom"}, {"model", "test"}, {"endpoint", "http://localhost/v1"}, {"hasApiKey", false},
    {"credentialMessage", ""}, {"accountQuotaMessage", ""}, {"packs", QVariantList{}}};
  for (auto it = values.cbegin(); it != values.cend(); ++it) translation.insert(it.key(), it.value());
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardTranslation"), &translation);
  engine.rootContext()->setContextProperty(QStringLiteral("OrchardAccount"), QVariantMap{{"isSignedIn", true}});
  QQmlComponent component(&engine,
      QUrl(QStringLiteral("qrc:/qt/qml/Orchard/app/qml/components/settings/LyricTranslationSettings.qml")));
  std::unique_ptr<QObject> object(component.create());
  if (!object) {
    for (const auto &error : component.errors()) output << error.toString() << '\n';
    return false;
  }
  object->setProperty("visible", false);
  auto *picker = qvariant_cast<QObject *>(object->property("languagePicker"));
  if (!picker || picker->property("count").toInt() != 7 || picker->property("currentIndex").toInt() != 0) return false;
  const QStringList targets{"eng", "spa", "hin", "kor", "jpn", "fra"};
  for (int index = 1; index <= targets.size(); ++index) {
    QMetaObject::invokeMethod(picker, "activated", Q_ARG(int, index));
    if (!translation.value("enabled").toBool() || translation.value("targetLanguage").toString() != targets[index - 1]) return false;
  }
  QMetaObject::invokeMethod(picker, "activated", Q_ARG(int, 0));
  if (translation.value("enabled").toBool()) return false;
  translation.insert("provider", "local");
  if (picker->property("count").toInt() != 2) return false;
  QMetaObject::invokeMethod(picker, "activated", Q_ARG(int, 1));
  return translation.value("enabled").toBool() && translation.value("targetLanguage").toString() == "eng";
}

int main(int argc, char *argv[]) {
  QGuiApplication application(argc, argv);
  QQuickStyle::setStyle(QStringLiteral("Basic"));
  QTextStream output(stderr);
  QFile manifest(QStringLiteral(":/qt/qml/Orchard/qmldir"));
  if (!manifest.open(QIODevice::ReadOnly)) {
    output << "Orchard QML module manifest is missing.\n";
    return 1;
  }
  const auto contents = manifest.readAll();
  if (contents.contains('\\')) {
    output << "Orchard QML module manifest contains Windows path separators.\n";
    return 1;
  }

  QQmlEngine engine;
  QQmlComponent component(&engine,
      QUrl(QStringLiteral("qrc:/qt/qml/Orchard/app/qml/Main.qml")),
      QQmlComponent::PreferSynchronous);
  if (!component.isReady()) {
    for (const auto &error : component.errors())
      output << error.toString() << '\n';
    output << "Orchard could not compile its QML entry point.\n";
    return 1;
  }
  if (!verifyScaleControls(engine, output)) {
    output << "Orchard interface scale controls failed their runtime check.\n";
    return 1;
  }
  if (!verifyTranslationControls(engine, output)) {
    output << "Orchard translation language controls failed their runtime check.\n";
    return 1;
  }
  output << "Orchard QML startup check passed.\n";
  return 0;
}
