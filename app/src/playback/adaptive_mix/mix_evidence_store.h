/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#pragma once
#include <QJsonObject>
#include <QSqlDatabase>
#include <QString>

// Adaptive mix's whole-track evidence by track id (`measured` in the worker), the same rows
// Android's MixEvidenceStore keeps. Playback sends it back with the next mix of the song;
// Best Mix plans with its beat model summary. The database opens on first use.
class MixEvidenceStore final {
public:
  // One connection per owner; Qt connections must not cross threads.
  explicit MixEvidenceStore(const QString &connection);
  ~MixEvidenceStore();
  MixEvidenceStore(const MixEvidenceStore &) = delete;
  MixEvidenceStore &operator=(const MixEvidenceStore &) = delete;

  // The worker's `outgoingMeasured`/`incomingMeasured` for a song, or empty.
  QJsonObject evidence(const QString &id, double duration);
  // Its `beatModel` summary, or empty.
  QJsonObject beatModel(const QString &id, double duration);
  void store(const QString &id, double duration, const QJsonObject &measured);

private:
  bool open();
  QByteArray read(const char *column, const QString &id, double duration);
  void trim();
  QString m_connection;
  QSqlDatabase m_db;
  bool m_opened{false};
};
