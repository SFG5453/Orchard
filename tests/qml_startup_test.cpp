#include <QGuiApplication>
#include <QQuickStyle>
#include <QFile>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QTextStream>
#include <QUrl>

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
  output << "Orchard QML startup check passed.\n";
  return 0;
}
