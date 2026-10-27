/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#pragma once

#include "adaptive_mix/mix_evidence_store.h"
#include <QHash>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QSet>
#include <QSqlDatabase>
#include <QVariantList>

class AppleMusic;
class AuthManager;
class CatalogSource;
class YouTubeProvider;
class QNetworkReply;
class QProcess;
class QSaveFile;

class BestMixController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool busy READ busy NOTIFY changed)
  Q_PROPERTY(bool sorting READ sorting NOTIFY changed)
  Q_PROPERTY(bool downloading READ downloading NOTIFY changed)
  Q_PROPERTY(int downloaded READ downloaded NOTIFY changed)
  Q_PROPERTY(int completed READ completed NOTIFY changed)
  Q_PROPERTY(int total READ total NOTIFY changed)
  Q_PROPERTY(QString error READ error NOTIFY changed)
  Q_PROPERTY(QVariantMap tempos READ tempos NOTIFY changed)
public:
  BestMixController(YouTubeProvider *provider, AuthManager *auth, QObject *parent = nullptr);
  ~BestMixController() override;
  bool busy() const { return m_busy; }
  bool sorting() const { return m_busy && !m_sortWorker.isNull(); }
  bool downloading() const { return m_busy && m_downloading; }
  int downloaded() const { return m_downloaded; }
  int completed() const { return m_completed; }
  int total() const { return m_total; }
  QString error() const { return m_error; }
  // Track id to detected BPM for the last sorted snapshot.
  QVariantMap tempos() const { return m_tempos; }
  QVariantList snapshot() const { return m_snapshot; }
  void start(const QVariantList &queue, const QVariantMap &current);
  void cancel();
  // Songs played from Apple Music sort on Apple's own analysis instead of downloaded audio.
  void setAppleMusic(AppleMusic *account, CatalogSource *source);
signals:
  void changed();
  void orderReady(const QVariantList &snapshot, const QList<int> &order);
private:
  struct Job;
  void pump();
  void resolved(quint64 request, const QJsonValue &result);
  void failed(quint64 request);
  void download(Job *job, const QJsonObject &stream);
  void fetchChunk(Job *job);
  void downloadFinished(Job *job);
  void analyze(Job *job);
  void complete(Job *job, const QJsonObject &features);
  void sort();
  QString cachePath(const QString &id, const QString &suffix) const;
  bool hasCachedFeatures(const QString &id, double duration);
  bool storeFeatures(const QString &id, double duration, const QJsonObject &features);
  QJsonObject summary(const QString &id) const;
  QJsonObject edge(const QString &id, bool tail) const;
  void trimCache() const;
  static double seconds(const QVariantMap &track);
  static QString workerPath();
  // best_mix_apple.cpp
  QString appleId(const QVariantMap &track) const;
  bool appleBusy() const { return !m_applePending.isEmpty() || m_appleWorker; }
  void startApple();
  void analyzeApple(const QJsonObject &items);
  void finishApple(const QVariantMap &track, const QJsonObject &features);
  void stopApple();
  AppleMusic *m_appleAccount{nullptr};
  CatalogSource *m_catalogSource{nullptr};
  QList<QVariantMap> m_applePending;
  // Songs the Apple worker is answering, in request order.
  QList<QVariantMap> m_appleSongs;
  QPointer<QProcess> m_appleWorker;
  quint64 m_appleGeneration{0};
  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  QNetworkAccessManager m_network;
  QHash<quint64, Job *> m_resolving;
  QPointer<QProcess> m_sortWorker;
  QList<Job *> m_active;
  QList<QVariantMap> m_pending;
  // Downloaded songs waiting for the analysis phase.
  QList<QVariantMap> m_ready;
  QSet<QString> m_available;
  QVariantList m_snapshot;
  QVariantMap m_current;
  QVariantMap m_tempos;
  QString m_cacheDir;
  QString m_dbName;
  QSqlDatabase m_db;
  // Songs adaptive mix measured; their beat model verdict replaces the planning assumption.
  MixEvidenceStore m_evidence;
  QString m_error;
  int m_downloaded{0};
  int m_completed{0};
  int m_total{0};
  bool m_busy{false};
  bool m_downloading{false};
};
