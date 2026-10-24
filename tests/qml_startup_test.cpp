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
#include <QJSValue>
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
      {"fullscreenPulse", true}, {"lyricsFocusBlur", true},
      {"lyricsDistanceFade", true}, {"lyricsStaggeredScroll", true}, {"artistVideoBackdrop", false}, {"animatedArtworkEnabled", true},
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

static bool verifyPlaylistFilter(QQmlEngine &engine, QTextStream &output) {
  SettingsMap appearance;
  appearance.insert("popupColor", "#202820");
  appearance.insert("accent", "#80b080");
  SettingsMap library;
  const QVariantList targets{
      QVariantMap{{"id", "one"}, {"title", "Evening mix"}, {"containsTrack", true}},
      QVariantMap{{"id", "two"}, {"title", "Weekend evenings"}},
      QVariantMap{{"id", "three"}, {"title", "Workout"}}};
  library.insert("playlistTargets", targets);
  library.insert("targetsLoading", false);
  library.insert("targetsError", "");
  library.insert("saving", false);
  engine.rootContext()->setContextProperty("OrchardAppearance", &appearance);
  engine.rootContext()->setContextProperty("OrchardLibrary", &library);
  QQuickWindow window;
  window.resize(640, 480);
  window.show();
  QQmlComponent component(&engine,
      QUrl("qrc:/qt/qml/Orchard/app/qml/components/PlaylistPicker.qml"));
  std::unique_ptr<QObject> picker(component.create());
  if (!picker) {
    for (const auto &error : component.errors()) output << error.toString() << '\n';
    return false;
  }
  picker->setProperty("parent", QVariant::fromValue(window.contentItem()));
  auto *field = picker->findChild<QQuickItem *>("playlistFilter");
  auto *clear = picker->findChild<QObject *>("clearPlaylistFilter");
  if (!field || !clear) { output << "Missing playlist search controls.\n"; return false; }
  auto matches = [&] {
    const auto value = picker->property("filteredTargets");
    return value.metaType() == QMetaType::fromType<QJSValue>()
        ? value.value<QJSValue>().toVariant().toList() : value.toList();
  };
  auto open = [&] {
    QMetaObject::invokeMethod(picker.get(), "openFor",
        Q_ARG(QVariant, (QVariantMap{{"id", "song"}})),
        Q_ARG(QVariant, (QVariantMap{{"x", 10}, {"y", 10}})));
    QEventLoop loop;
    QTimer::singleShot(300, &loop, &QEventLoop::quit);
    loop.exec();
  };
  open();
  if (!field->hasActiveFocus() || matches().size() != 3) {
    output << "Opening playlist picker: focus=" << field->hasActiveFocus() << " matches=" << matches().size() << '\n';
    return false;
  }
  for (const auto character : QStringLiteral(" EVEN ")) {
    QKeyEvent event(QEvent::KeyPress, character.unicode(), Qt::NoModifier, QString(character));
    QCoreApplication::sendEvent(&window, &event);
  }
  if (matches().size() != 2 || matches().first().toMap().value("id") != "one") {
    output << "Typing playlist filter: text=" << field->property("text").toString() << " matches=" << matches().size() << '\n';
    return false;
  }
  QQuickItem *entry = nullptr;
  QMetaObject::invokeMethod(picker.get(), "itemAt", Q_RETURN_ARG(QQuickItem *, entry), Q_ARG(int, 3));
  if (!entry || entry->isEnabled()) { output << "Duplicate playlist target is enabled or missing.\n"; return false; }
  field->setProperty("text", "missing");
  if (!matches().isEmpty() || picker->property("emptyText") != "No matching playlists") { output << "Missing playlist no-results message.\n"; return false; }
  QMetaObject::invokeMethod(clear, "clicked");
  if (matches().size() != 3 || !field->hasActiveFocus()) { output << "Clearing playlist filter failed.\n"; return false; }
  field->setProperty("text", "work");
  QKeyEvent down(QEvent::KeyPress, Qt::Key_Down, Qt::NoModifier);
  QCoreApplication::sendEvent(&window, &down);
  if (field->hasActiveFocus() || picker->property("currentIndex").toInt() != 0) { output << "Playlist keyboard navigation failed.\n"; return false; }
  QMetaObject::invokeMethod(picker.get(), "close");
  open();
  if (!field->property("text").toString().isEmpty() || matches().size() != 3) { output << "Reopening playlist picker did not reset its filter.\n"; return false; }
  QVariantList manyTargets;
  for (int index = 0; index < 100; ++index)
    manyTargets.append(QVariantMap{{"id", QString::number(index)}, {"title", QString("List %1").arg(index)}});
  library.insert("playlistTargets", manyTargets);
  QCoreApplication::processEvents();
  auto *results = picker->findChild<QQuickItem *>("playlistResults");
  if (!results || results->height() < 100 || picker->property("height").toDouble() > window.height()) { output << "Playlist results have invalid scroll bounds.\n"; return false; }
  picker->setProperty("currentIndex", 102);
  QEventLoop scrollLoop;
  QTimer::singleShot(300, &scrollLoop, &QEventLoop::quit);
  scrollLoop.exec();
  if (results->property("contentY").toDouble() <= 0 || field->y() != 0) {
    output << "Playlist scroll: offset=" << results->property("contentY").toDouble()
           << " index=" << results->property("currentIndex").toInt() << " filterY=" << field->y() << '\n';
    return false;
  }
  picker->setProperty("track", QVariantMap{{"id", "local:song"}});
  picker->setProperty("localTargets", QVariantList{QVariantMap{{"id", "local:list"}, {"title", "Local evenings"}}});
  field->setProperty("text", "EVEN");
  if (matches().size() != 1 || matches().first().toMap().value("id") != "local:list") { output << "Local playlist filtering failed.\n"; return false; }
  picker->setProperty("localTargets", QVariantList{});
  if (picker->property("emptyText") != "No local playlists yet") { output << "Missing local playlists message.\n"; return false; }
  return true;
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
  if (!verifyPlaylistFilter(engine, output)) {
    output << "Orchard playlist filter failed its runtime check.\n";
    return 1;
  }
  output << "Orchard QML startup check passed.\n";
  return 0;
}
