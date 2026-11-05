/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "appearance/image_dimensions.h"

#include <QEventLoop>
#include <QImage>
#include <QDir>
#include <QPainter>
#include <QPointer>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQmlProperty>
#include <cmath>
#include <QQuickItem>
#include <QQuickItemGrabResult>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTextStream>
#include <QTimer>
#include <memory>

namespace {
void settle(int ms = 100) {
  QEventLoop loop;
  QTimer::singleShot(ms, &loop, &QEventLoop::quit);
  loop.exec();
}
QImage render(QQuickWindow &window) {
  const auto grab = window.contentItem()->grabToImage();
  if (!grab) return {};
  QEventLoop loop;
  QObject::connect(grab.get(), &QQuickItemGrabResult::ready, &loop, &QEventLoop::quit);
  QTimer::singleShot(2000, &loop, &QEventLoop::quit);
  loop.exec();
  return grab->image();
}
QList<QQuickItem *> itemsWith(QQuickItem *root, const char *property) {
  QList<QQuickItem *> result;
  for (auto *child : root->childItems()) {
    if (child->property(property).isValid()) result.append(child);
    result.append(itemsWith(child, property));
  }
  return result;
}
std::unique_ptr<QObject> create(QQmlEngine &engine, const QString &path, QTextStream &out) {
  QQmlComponent component(&engine, QUrl("qrc:/qt/qml/Orchard/app/qml/" + path));
  std::unique_ptr<QObject> object(component.create());
  if (!object) out << component.errorString();
  return object;
}
}

bool verifyMemoryUi(QTextStream &out) {
  QQmlEngine engine;
  QQmlComponent mocks(&engine);
  mocks.setData(R"(
    import QtQuick
    QtObject {
      property color accent: "#80b080"
      property color accentStrong: accent
      property color popupColor: "#202820"
      property bool animatedArtworkEnabled: false
      property bool animatedCollageEnabled: false
      property bool offline: false
      property bool isSignedIn: false
      property string homeSource: "youtube"
      property var track: ({})
      property bool playing: false
      property int revision: 0
      property var detail: ({})
      property var palette: ({})
      property bool loading: false
      property string errorMessage: ""
      property string animatedArtworkUrl: ""
      property var streamQuality: ({})
      property QtObject slop: QtObject {
        property int revision: 0
        function isFlagged(id) { return false; }
        function isLabelled(id) { return false; }
        function probability(id) { return 0; }
      }
      function collectionState(tracks) { return {total: tracks.length, downloaded: 0, pending: 0}; }
      function isDownloaded(id) { return false; }
      function stateOf(id) { return "none"; }
    }
  )", QUrl());
  std::unique_ptr<QObject> mock(mocks.create());
  if (!mock) { out << mocks.errorString(); return false; }
  for (const auto *name : {"OrchardAppearance", "OrchardNetwork", "OrchardAuth", "OrchardHome",
                           "OrchardPlayback", "OrchardDownloads", "OrchardAlbum"})
    engine.rootContext()->setContextProperty(QString::fromLatin1(name), mock.get());

  QQuickWindow window;
  window.setColor(QColor("#101812"));
  window.resize(1100, 750);
  window.show();
  auto attach = [&](QObject *object, int width = 1100) {
    auto *item = qobject_cast<QQuickItem *>(object);
    if (item) {
      item->setParentItem(window.contentItem());
      item->setSize(QSizeF(width, 750));
    }
    return item;
  };
  auto capture = [&](const QString &name) {
    const QString directory = qEnvironmentVariable("ORCHARD_UI_CAPTURE_DIR");
    if (!directory.isEmpty()) {
      QDir().mkpath(directory);
      render(window).save(directory + '/' + name + ".png");
    }
  };
  QVariantList tracks;
  for (int i = 0; i < 1000; ++i)
    tracks.append(QVariantMap{{"id", QString::number(i)}, {"type", "song"},
      {"title", QString("Track %1").arg(i + 1)}, {"artist", "Test artist"},
      {"artistBrowseIds", QStringList{"artist-one", "artist-two"}}, {"duration", "3:00"}});

  auto album = create(engine, "views/album/AlbumView.qml", out);
  auto *albumItem = attach(album.get());
  if (!albumItem) return false;
  album->setProperty("detail", QVariantMap{{"title", "Memory test album"}, {"artist", "Test artist"},
                                           {"browseId", "album-one"}, {"tracks", tracks}});
  settle(500);
  auto rows = itemsWith(albumItem, "trackIndex");
  if (rows.isEmpty() || rows.size() > 30) {
    out << "Album instantiated " << rows.size() << " rows for 1000 tracks.\n"; return false;
  }
  out << "Album: " << rows.size() << " instantiated rows / 1000 tracks.\n";
  capture("album-wide");
  auto views = itemsWith(albumItem, "cacheBuffer");
  if (views.isEmpty()) return false;
  auto *list = views.first();
  const double top = list->property("contentY").toDouble();
  list->setProperty("contentY", 30000);
  settle();
  rows = itemsWith(albumItem, "trackIndex");
  bool lateRow = false;
  for (auto *row : rows) lateRow |= row->property("trackIndex").toInt() > 500;
  if (!lateRow || rows.size() > 30) { out << "Album virtualization failed after scrolling.\n"; return false; }
  list->setProperty("contentY", top);
  albumItem->setWidth(650);
  settle();
  auto *header = qvariant_cast<QObject *>(list->property("headerItem"));
  auto *slot = header ? qvariant_cast<QQuickItem *>(header->property("heroSlot")) : nullptr;
  if (!slot || slot->childItems().isEmpty() || slot->height() < 200) {
    out << "Compact album hero was lost.\n"; return false;
  }
  auto stickyBars = itemsWith(albumItem, "shown");
  if (stickyBars.isEmpty() || stickyBars.first()->property("shown").toBool()) {
    out << "Compact album sticky bar covered the hero at the top.\n"; return false;
  }
  capture("album-compact");
  list->setProperty("contentY", 2000);
  settle();
  if (!stickyBars.first()->property("shown").toBool()) {
    out << "Compact album sticky bar did not appear after scrolling.\n"; return false;
  }
  QMetaObject::invokeMethod(list, "positionViewAtBeginning");
  settle();
  albumItem->setWidth(1100);
  settle();
  if (!slot->childItems().isEmpty() || slot->height() != 14) {
    out << "Wide album hero was not reparented.\n"; return false;
  }
  album.reset();

  auto search = create(engine, "views/search/SearchView.qml", out);
  auto *searchItem = attach(search.get());
  if (!searchItem) return false;
  search->setProperty("filter", "songs");
  search->setProperty("sections", QVariantList{QVariantMap{{"key", "songs"}, {"title", "Songs"}, {"items", tracks}}});
  settle(500);
  auto entries = itemsWith(searchItem, "entryIndex");
  if (entries.isEmpty() || entries.size() > 30) {
    out << "Search instantiated " << entries.size() << " entries for 1000 results.\n"; return false;
  }
  out << "Search: " << entries.size() << " instantiated entries / 1000 results.\n";
  capture("search-songs");
  auto scrolls = itemsWith(searchItem, "contentY");
  if (scrolls.isEmpty()) return false;
  auto *scroll = scrolls.first();
  QPointer<QQuickItem> retained;
  for (auto *entry : entries)
    if (entry->property("entryIndex").toInt() == 5) retained = entry;
  scroll->setProperty("contentY", 200);
  settle();
  if (!retained || retained->property("entryIndex").toInt() != 5) {
    out << "Scrolling recreated artwork that was still visible.\n"; return false;
  }
  scroll->setProperty("contentY", 30000);
  settle();
  entries = itemsWith(searchItem, "entryIndex");
  bool lateEntry = false;
  for (auto *entry : entries) lateEntry |= entry->property("entryIndex").toInt() > 500;
  if (!lateEntry || entries.size() > 30) { out << "Search virtualization failed after scrolling.\n"; return false; }
  search->setProperty("filter", "albums");
  search->setProperty("sections", QVariantList{QVariantMap{{"key", "albums"}, {"title", "Albums"}, {"items", tracks}}});
  settle();
  searchItem->setWidth(650);
  settle();
  entries = itemsWith(searchItem, "entryIndex");
  if (entries.isEmpty() || entries.size() > 30) { out << "Search grid did not reflow.\n"; return false; }
  capture("search-albums");
  scroll->setProperty("contentY", scroll->property("contentHeight").toDouble() - scroll->height());
  settle();
  bool lastEntry = false;
  for (auto *entry : itemsWith(searchItem, "entryIndex")) lastEntry |= entry->property("entryIndex").toInt() == 999;
  if (!lastEntry) { out << "Last search result is unreachable.\n"; return false; }
  searchItem->setWidth(1100);
  search->setProperty("filter", "all");
  QVariantList allSections;
  for (const auto *key : {"songs", "videos", "artists", "albums", "playlists"})
    allSections.append(QVariantMap{{"key", key}, {"title", key}, {"items", tracks}});
  search->setProperty("sections", allSections);
  settle(500);
  capture("search-all");
  if (itemsWith(searchItem, "entryIndex").size() > 25) {
    out << "All-results search ignored the section limit.\n"; return false;
  }
  search.reset();

  auto menu = create(engine, "components/MediaMenuHost.qml", out);
  auto *host = attach(menu.get());
  if (!host) return false;
  menu->setProperty("media", tracks.first());
  auto loaders = itemsWith(host, "active");
  if (loaders.size() != 1 || loaders.first()->property("item").value<QObject *>()) {
    out << "Media menu was allocated before use.\n"; return false;
  }
  QMetaObject::invokeMethod(menu.get(), "viewArtist", Q_ARG(QVariant, QVariant::fromValue(window.contentItem())),
      Q_ARG(QVariant, 20), Q_ARG(QVariant, 20));
  settle();
  QObject *loaded = loaders.first()->property("item").value<QObject *>();
  QObject *choices = loaded ? loaded->property("artistChoices").value<QObject *>() : nullptr;
  if (!choices || !choices->property("visible").toBool() || choices->property("count").toInt() != 2) {
    out << "Lazy artist chooser did not open.\n"; return false;
  }
  QMetaObject::invokeMethod(choices, "close");
  settle(400);
  if (loaders.first()->property("item").value<QObject *>()) {
    out << "Lazy artist chooser was not released.\n"; return false;
  }
  menu.reset();

  QTemporaryDir images;
  QImage banner(3000, 1500, QImage::Format_RGB32);
  {
    QPainter painter(&banner);
    QLinearGradient gradient(0, 0, banner.width(), banner.height());
    gradient.setColorAt(0, QColor("#e2a86b"));
    gradient.setColorAt(0.5, QColor("#416e83"));
    gradient.setColorAt(1, QColor("#601e64"));
    painter.fillRect(banner.rect(), gradient);
    painter.setBrush(QColor("#c99a69"));
    painter.setPen(Qt::NoPen);
    painter.drawEllipse(QRect(1100, 150, 800, 1000));
  }
  const QString path = images.filePath("banner.png");
  if (!banner.save(path)) return false;
  ImageDimensions dimensions;
  dimensions.setSource(QUrl::fromLocalFile(path));
  if (dimensions.size() != banner.size()) { out << "Image dimensions were not read.\n"; return false; }
  dimensions.setSource({});
  if (dimensions.size().isValid()) { out << "Image dimensions did not clear.\n"; return false; }
  auto hero = create(engine, "components/detail/ArtistHero.ui.qml", out);
  auto *heroItem = attach(hero.get(), 800);
  if (!heroItem) return false;
  hero->setProperty("title", "Memory test artist");
  hero->setProperty("banner", QUrl::fromLocalFile(path).toString());
  settle(500);
  if (!hero->property("bannerMode").toBool()) { out << "Artist banner aspect ratio was lost.\n"; return false; }
  for (auto *image : itemsWith(heroItem, "sourceSize")) {
    if (image->property("source").toUrl() == QUrl::fromLocalFile(path)) {
      const QSize size = image->property("sourceSize").toSize();
      const double dpr = window.devicePixelRatio();
      if (size.width() > 800 * dpr + 128 || size.width() < 800 * dpr) {
        out << "Artist image has unbounded or undersized pixels: " << size.width() << '\n'; return false;
      }
    }
  }
  capture("artist");
  if (hero->property("masked").toBool()) {
    const QImage narrow = render(window).convertToFormat(QImage::Format_RGB32);
    int masks = 0;
    for (auto *item : itemsWith(heroItem, "gradient")) {
      QQmlProperty textureSize(item, "layer.textureSize");
      if (textureSize.read().toSize().width() != 1) continue;
      ++masks;
      textureSize.write(QSize(std::ceil(item->width() * window.devicePixelRatio()),
                              std::ceil(item->height() * window.devicePixelRatio())));
    }
    settle();
    const QImage full = render(window).convertToFormat(QImage::Format_RGB32);
    if (masks != 2 || narrow.isNull() || full.isNull() || narrow.size() != full.size()) return false;
    int maximumDifference = 0;
    for (int y = 0; y < full.height(); ++y) {
      const auto *a = reinterpret_cast<const QRgb *>(narrow.constScanLine(y));
      const auto *b = reinterpret_cast<const QRgb *>(full.constScanLine(y));
      for (int x = 0; x < full.width(); ++x)
        maximumDifference = std::max({maximumDifference, std::abs(qRed(a[x]) - qRed(b[x])),
            std::abs(qGreen(a[x]) - qGreen(b[x])), std::abs(qBlue(a[x]) - qBlue(b[x]))});
    }
    capture("artist-full-masks");
    out << "Narrow/full mask maximum channel difference: " << maximumDifference << ".\n";
    if (maximumDifference > 2) { out << "Narrow artist masks changed the rendering.\n"; return false; }
  }
  out << "Memory UI checks passed: bounded rows, grid scrolling, lazy menus, image dimensions.\n";
  return true;
}
