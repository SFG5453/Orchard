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

#include "offline_library.h"

#include "connectivity_monitor.h"
#include "download_manager.h"
#include "local/local_library.h"

namespace {

// Two dozen songs per shelf: enough to browse, too few to get lost.
constexpr int kShelfSize = 24;

QVariantMap section(const QString &key, const QString &title, const QVariantList &items) {
  return {{QStringLiteral("key"), key}, {QStringLiteral("title"), title}, {QStringLiteral("items"), items}};
}

QString haystack(const QVariantMap &item) {
  QStringList parts{item.value(QStringLiteral("title")).toString(), item.value(QStringLiteral("artist")).toString(),
                    item.value(QStringLiteral("author")).toString(), item.value(QStringLiteral("album")).toString()};
  parts.append(item.value(QStringLiteral("artists")).toStringList());
  return parts.join(QLatin1Char(' '));
}

QVariantList matching(const QVariantList &items, const QStringList &tokens) {
  QVariantList found;
  for (const QVariant &value : items) {
    const QString text = haystack(value.toMap());
    bool all = true;
    for (const QString &token : tokens)
      all = all && text.contains(token, Qt::CaseInsensitive);
    if (all)
      found.append(value);
  }
  return found;
}

} // namespace

OfflineLibrary::OfflineLibrary(DownloadManager *downloads, LocalLibrary *local, ConnectivityMonitor *connectivity,
                               QObject *parent)
    : QObject(parent), m_downloads(downloads), m_local(local), m_connectivity(connectivity) {
  if (m_downloads)
    connect(m_downloads, &DownloadManager::changed, this, &OfflineLibrary::changed);
  if (m_local)
    connect(m_local, &LocalLibrary::changed, this, &OfflineLibrary::changed);
}

bool OfflineLibrary::offline() const { return m_connectivity && m_connectivity->offline(); }

QVariantList OfflineLibrary::songs() const {
  QVariantList list;
  if (m_downloads) {
    const offline::OfflineStore &store = m_downloads->store();
    for (const QString &id : store.order) {
      const auto it = store.tracks.constFind(id);
      if (it != store.tracks.cend())
        list.append(offline::trackToVariant(it.value()));
    }
  }
  if (m_local) {
    for (const QVariant &song : m_local->songs()) {
      if (!song.toMap().value(QStringLiteral("missing")).toBool())
        list.append(song);
    }
  }
  return list;
}

QVariantList OfflineLibrary::playlists() const {
  QVariantList list;
  if (m_local)
    list = m_local->playlists();
  if (!m_downloads)
    return list;
  const offline::OfflineStore &store = m_downloads->store();
  for (const offline::CollectionRecord &collection : store.collections) {
    if (offline::downloadedCount(collection, store) > 0)
      list.append(offline::collectionToVariant(collection, store));
  }
  return list;
}

QVariantList OfflineLibrary::homeSections() const {
  QVariantList sections;
  if (m_downloads) {
    const offline::OfflineStore &store = m_downloads->store();
    QVariantList recent;
    for (const QString &id : store.order) {
      if (recent.size() >= kShelfSize)
        break;
      recent.append(offline::trackToVariant(store.tracks.value(id)));
    }
    if (!recent.isEmpty())
      sections.append(section(QStringLiteral("downloads"), tr("Recently downloaded"), recent));
  }
  const QVariantList lists = playlists();
  if (!lists.isEmpty())
    sections.append(section(QStringLiteral("playlists"), tr("Your playlists"), lists));
  if (m_local) {
    QVariantList files;
    for (const QVariant &song : m_local->songs()) {
      if (files.size() >= kShelfSize)
        break;
      if (!song.toMap().value(QStringLiteral("missing")).toBool())
        files.append(song);
    }
    if (!files.isEmpty())
      sections.append(section(QStringLiteral("local"), tr("On this computer"), files));
  }
  return sections;
}

QVariantList OfflineLibrary::search(const QString &query, const QString &filter) const {
  const QStringList tokens = query.simplified().split(QLatin1Char(' '), Qt::SkipEmptyParts);
  if (tokens.isEmpty())
    return {};
  QVariantList sections;
  if (filter == QLatin1String("all") || filter == QLatin1String("songs")) {
    const QVariantList found = matching(songs(), tokens);
    if (!found.isEmpty())
      sections.append(section(QStringLiteral("songs"), tr("Songs"), found));
  }
  if (filter == QLatin1String("all") || filter == QLatin1String("playlists")) {
    const QVariantList found = matching(playlists(), tokens);
    if (!found.isEmpty())
      sections.append(section(QStringLiteral("playlists"), tr("Playlists"), found));
  }
  return sections;
}

QVariantMap OfflineLibrary::collectionDetail(const QString &id) const {
  if (!m_downloads)
    return {};
  const offline::OfflineStore &store = m_downloads->store();
  const offline::CollectionRecord *collection = store.collection(id);
  if (!collection || offline::downloadedCount(*collection, store) == 0)
    return {};
  return offline::collectionDetail(*collection, store);
}

QString OfflineLibrary::collectionIdFor(const QVariantMap &item) const {
  const QString direct = item.value(QStringLiteral("id")).toString();
  if (offline::isCollectionId(direct))
    return direct;
  const QVariantMap payload = item.value(QStringLiteral("browsePayload")).toMap();
  for (const QString &candidate : {item.value(QStringLiteral("playlistId")).toString(),
                                   item.value(QStringLiteral("browseId")).toString(),
                                   payload.value(QStringLiteral("browseId")).toString(), direct}) {
    QString source = candidate.trimmed();
    if (source.startsWith(QLatin1String("VL")))
      source.remove(0, 2);
    if (source.isEmpty())
      continue;
    const QString id = offline::kCollectionPrefix + source;
    if (m_downloads && m_downloads->store().collection(id))
      return id;
  }
  return {};
}
