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

#pragma once

#include "translation_store.h"

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QStringList>
#include <QThread>
#include <QTimer>
#include <QVariantList>
#include <atomic>
#include <memory>

class LyricsController;
class OrchardAccount;
class QNetworkAccessManager;
class QNetworkReply;
class TranslationPacks;
class TranslationWorker;

// Lyric translation into the selected language. Local models remain the default; remote providers are opt-in.
class LyricsTranslator final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY enabledChanged)
  // "standard" (small, quick) or "high" (larger models, better lines).
  Q_PROPERTY(QString quality READ quality WRITE setQuality NOTIFY qualityChanged)
  Q_PROPERTY(QString targetLanguage READ targetLanguage WRITE setTargetLanguage NOTIFY targetLanguageChanged)
  // Use Apple Music's own translation for Apple lyrics; the selected provider covers everything else.
  Q_PROPERTY(bool preferAppleTranslation READ preferAppleTranslation WRITE setPreferAppleTranslation NOTIFY providerChanged)
  Q_PROPERTY(QString provider READ provider WRITE setProvider NOTIFY providerChanged)
  Q_PROPERTY(QString model READ model WRITE setModel NOTIFY providerChanged)
  Q_PROPERTY(QString endpoint READ endpoint WRITE setEndpoint NOTIFY providerChanged)
  Q_PROPERTY(bool hasApiKey READ hasApiKey NOTIFY providerChanged)
  Q_PROPERTY(QString credentialMessage READ credentialMessage NOTIFY providerChanged)
  Q_PROPERTY(QString accountQuotaMessage READ accountQuotaMessage NOTIFY accountQuotaChanged)
  // off, idle (nothing to translate), unsupported, downloading, translating, ready, failed
  Q_PROPERTY(QString status READ status NOTIFY stateChanged)
  Q_PROPERTY(QString message READ message NOTIFY stateChanged)
  Q_PROPERTY(QString sourceName READ sourceName NOTIFY stateChanged)
  Q_PROPERTY(double progress READ progress NOTIFY stateChanged)
  // One entry per lyric line; empty where the original stands alone.
  Q_PROPERTY(QStringList lines READ lines NOTIFY stateChanged)
  Q_PROPERTY(QVariantList packs READ packs NOTIFY packsChanged)
  Q_PROPERTY(double installedMegabytes READ installedMegabytes NOTIFY packsChanged)

public:
  explicit LyricsTranslator(LyricsController *lyrics, QObject *parent = nullptr);
  ~LyricsTranslator() override;
  void setAccount(OrchardAccount *account);

  [[nodiscard]] bool enabled() const { return m_enabled; }
  void setEnabled(bool enabled);
  [[nodiscard]] QString targetLanguage() const { return m_targetLanguage; }
  void setTargetLanguage(const QString &language);
  [[nodiscard]] QString quality() const { return m_quality; }
  void setQuality(const QString &quality);
  [[nodiscard]] bool preferAppleTranslation() const { return m_preferApple; }
  void setPreferAppleTranslation(bool prefer);
  [[nodiscard]] QString provider() const { return m_provider; }
  void setProvider(const QString &provider);
  [[nodiscard]] QString model() const { return m_model; }
  void setModel(const QString &model);
  [[nodiscard]] QString endpoint() const { return m_endpoint; }
  void setEndpoint(const QString &endpoint);
  [[nodiscard]] bool hasApiKey() const { return !m_apiKey.isEmpty(); }
  [[nodiscard]] QString credentialMessage() const { return m_credentialMessage; }
  [[nodiscard]] QString accountQuotaMessage() const;
  Q_INVOKABLE void refreshAccountQuota();
  Q_INVOKABLE void saveApiKey(const QString &key);
  Q_INVOKABLE void removeApiKey();
  [[nodiscard]] QString status() const { return m_status; }
  [[nodiscard]] QString message() const { return m_message; }
  [[nodiscard]] QString sourceName() const;
  [[nodiscard]] double progress() const { return m_progress; }
  [[nodiscard]] QStringList lines() const { return m_lines; }
  [[nodiscard]] QVariantList packs() const;
  [[nodiscard]] double installedMegabytes() const;
  Q_INVOKABLE void removePack(const QString &code);
  Q_INVOKABLE void retry();

signals:
  void enabledChanged();
  void qualityChanged();
  void targetLanguageChanged();
  void providerChanged();
  void stateChanged();
  void packsChanged();
  void accountQuotaChanged();

private:
  void sync();
  void start();
  void startProvider();
  bool tryApple();
  void runModel();
  void runRemote();
  void runAccountRemote();
  void acceptAccountQuota(const QJsonObject &body);
  void loadApiKey();
  void cancelRemote();
  [[nodiscard]] QString cacheRevision() const;
  void setStatus(const QString &status, const QString &message = QString());
  void accept(int generation, const QString &text, const QString &translation);
  void jobFinished(int generation, const QString &error);
  void packReady(const QString &id);
  void packFailed(const QString &id, const QString &message);
  void packProgress(const QString &id, double fraction);
  void flush();

  OrchardAccount *m_account{nullptr};
  QJsonObject m_accountQuota;
  int m_quotaGeneration{0};
  LyricsController *m_lyrics;
  QNetworkAccessManager *m_network;
  TranslationPacks *m_packs;
  TranslationStore m_store;
  QThread m_thread;
  TranslationWorker *m_worker{nullptr};
  std::shared_ptr<std::atomic_int> m_generation;
  QTimer m_flush;

  bool m_enabled{false};
  bool m_preferApple{false};
  QString m_targetLanguage{QStringLiteral("eng")};
  QString m_quality{QStringLiteral("standard")};
  QString m_provider{QStringLiteral("local")};
  QString m_model;
  QString m_endpoint;
  QString m_apiKey;
  QString m_credentialMessage;
  int m_keyGeneration{0};
  bool m_keyLoading{false};
  QNetworkReply *m_reply{nullptr};
  QStringList m_remoteQueue;
  QStringList m_remoteBatch;
  // Pack chosen for the current song; empty when nothing needs translating.
  QString m_pack;
  QString m_status{QStringLiteral("off")};
  QString m_message;
  QString m_source;
  double m_progress{0};
  // Identity of the lyrics the current state belongs to.
  QString m_key;
  QStringList m_texts;
  QStringList m_lines;
  // Source text -> every line index showing it; choruses translate once.
  QHash<QString, QList<int>> m_pending;
};
