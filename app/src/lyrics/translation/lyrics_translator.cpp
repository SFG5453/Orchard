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

#include "lyrics_translator.h"
#include "translation_packs.h"
#include "remote_translation.h"
#include "translation_worker.h"

#include "lyrics/lyrics_controller.h"
#include "translation/lyric_language.h"

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QSettings>
#include <QUrl>
#include <qtkeychain/keychain.h>

namespace {
const QString kEnabledKey = QStringLiteral("lyrics/translate");
const QString kQualityKey = QStringLiteral("lyrics/translationQuality");
const QString kProviderKey = QStringLiteral("lyrics/translationProvider");
const QString kPreferAppleKey = QStringLiteral("lyrics/preferAppleTranslation");
constexpr auto kKeychainService = "dev.sfg.orchard";

QString keychainEntry(const QString &provider) { return QStringLiteral("lyric-translation-%1").arg(provider); }


QString lineText(const QVariant &value) {
  const QVariantMap line = value.toMap();
  const QString text = line.value(QStringLiteral("text")).toString().trimmed();
  if (!text.isEmpty())
    return text;
  QStringList words;
  for (const QVariant &word : line.value(QStringLiteral("words")).toList())
    words.append(word.toMap().value(QStringLiteral("text")).toString().trimmed());
  return words.join(QLatin1Char(' ')).simplified();
}

// "Oh oh oh" comes back as "Oh oh oh"; showing it twice is noise.
bool sameText(const QString &a, const QString &b) {
  return a.simplified().compare(b.simplified(), Qt::CaseInsensitive) == 0;
}
} // namespace

LyricsTranslator::LyricsTranslator(LyricsController *lyrics, QObject *parent)
    : QObject(parent), m_lyrics(lyrics), m_network(new QNetworkAccessManager(this)),
      m_packs(new TranslationPacks(m_network, this)), m_generation(std::make_shared<std::atomic_int>(0)) {
  m_enabled = QSettings().value(kEnabledKey, false).toBool();
  m_preferApple = QSettings().value(kPreferAppleKey, false).toBool();
  if (QSettings().value(kQualityKey).toString() == QStringLiteral("high"))
    m_quality = QStringLiteral("high");
  const QString target = QSettings().value(QStringLiteral("lyrics/translationTarget"), QStringLiteral("eng")).toString();
  if (lyric_language::supportedTarget(target.toStdString())) m_targetLanguage = target;
  const QString savedProvider = QSettings().value(kProviderKey).toString();
  if (QStringList{QStringLiteral("orchard"), QStringLiteral("openai"), QStringLiteral("claude"), QStringLiteral("gemini"), QStringLiteral("custom")}.contains(savedProvider))
    m_provider = savedProvider;
  m_model = QSettings().value(QStringLiteral("lyrics/model/%1").arg(m_provider)).toString();
  m_endpoint = QSettings().value(QStringLiteral("lyrics/customEndpoint")).toString();
  if (m_provider != QStringLiteral("local") && m_provider != QStringLiteral("orchard")) loadApiKey();

  m_worker = new TranslationWorker(m_generation);
  m_worker->moveToThread(&m_thread);
  connect(&m_thread, &QThread::finished, m_worker, &QObject::deleteLater);
  connect(m_worker, &TranslationWorker::translated, this, &LyricsTranslator::accept);
  connect(m_worker, &TranslationWorker::finished, this, &LyricsTranslator::jobFinished);
  m_thread.setObjectName(QStringLiteral("Lyrics translation"));
  // Lowest priority: a translated chorus can wait a frame, the game you're playing can't.
  m_thread.start(QThread::LowestPriority);

  connect(m_packs, &TranslationPacks::ready, this, &LyricsTranslator::packReady);
  connect(m_packs, &TranslationPacks::failed, this, &LyricsTranslator::packFailed);
  connect(m_packs, &TranslationPacks::progress, this, &LyricsTranslator::packProgress);
  connect(m_packs, &TranslationPacks::changed, this, &LyricsTranslator::packsChanged);

  // Lines arrive one at a time; repaint in batches instead of per line.
  m_flush.setSingleShot(true);
  m_flush.setInterval(120);
  connect(&m_flush, &QTimer::timeout, this, &LyricsTranslator::flush);

  connect(m_lyrics, &LyricsController::stateChanged, this, &LyricsTranslator::sync);
  connect(m_lyrics, &LyricsController::activeChanged, this, &LyricsTranslator::sync);
  sync();
}

LyricsTranslator::~LyricsTranslator() {
  ++*m_generation;
  cancelRemote();
  m_thread.quit();
  m_thread.wait();
}

void LyricsTranslator::setEnabled(bool enabled) {
  if (m_enabled == enabled)
    return;
  m_enabled = enabled;
  QSettings().setValue(kEnabledKey, enabled);
  emit enabledChanged();
  // Switching off mid-download means "not now", so the half-fetched model goes too.
  if (!enabled)
    m_packs->cancel();
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setTargetLanguage(const QString &language) {
  if (!lyric_language::supportedTarget(language.toStdString()) || m_targetLanguage == language) return;
  m_targetLanguage = language;
  QSettings().setValue(QStringLiteral("lyrics/translationTarget"), language);
  emit targetLanguageChanged();
  ++*m_generation;
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setQuality(const QString &quality) {
  const QString value = quality == QStringLiteral("high") ? quality : QStringLiteral("standard");
  if (m_quality == value)
    return;
  m_quality = value;
  QSettings().setValue(kQualityKey, value);
  emit qualityChanged();
  // A download for the other quality is no longer wanted.
  m_packs->cancel();
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setPreferAppleTranslation(bool prefer) {
  if (m_preferApple == prefer) return;
  m_preferApple = prefer;
  QSettings().setValue(kPreferAppleKey, prefer);
  emit providerChanged();
  ++*m_generation;
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setProvider(const QString &provider) {
  if (!QStringList{QStringLiteral("local"), QStringLiteral("orchard"), QStringLiteral("openai"), QStringLiteral("claude"), QStringLiteral("gemini"), QStringLiteral("custom")}.contains(provider) || m_provider == provider)
    return;
  ++m_keyGeneration;
  m_packs->cancel();
  cancelRemote();
  m_provider = provider;
  m_apiKey.clear();
  m_credentialMessage.clear();
  m_keyLoading = false;
  QSettings().setValue(kProviderKey, provider);
  m_model = QSettings().value(QStringLiteral("lyrics/model/%1").arg(provider)).toString();
  emit providerChanged();
  if (provider != QStringLiteral("local") && provider != QStringLiteral("orchard")) loadApiKey();
  m_key.clear();
  sync();
}

void LyricsTranslator::setModel(const QString &model) {
  const QString value = model.trimmed();
  if (m_model == value) return;
  m_model = value;
  QSettings().setValue(QStringLiteral("lyrics/model/%1").arg(m_provider), value);
  emit providerChanged();
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setEndpoint(const QString &endpoint) {
  const QString value = endpoint.trimmed();
  if (m_endpoint == value) return;
  m_endpoint = value;
  QSettings().setValue(QStringLiteral("lyrics/customEndpoint"), value);
  emit providerChanged();
  if (m_provider == QStringLiteral("custom")) { cancelRemote(); m_key.clear(); sync(); }
}

void LyricsTranslator::loadApiKey() {
  const int generation = ++m_keyGeneration;
  const QString provider = m_provider;
  m_keyLoading = true;
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(keychainEntry(provider));
  connect(job, &QKeychain::Job::finished, this, [this, generation](QKeychain::Job *base) {
    if (generation != m_keyGeneration) return;
    auto *read = static_cast<QKeychain::ReadPasswordJob *>(base);
    m_keyLoading = false;
    m_apiKey = read->error() == QKeychain::NoError ? read->textData() : QString();
    m_credentialMessage = read->error() != QKeychain::NoError && read->error() != QKeychain::EntryNotFound
                              ? tr("Could not read the API key from the keychain: %1").arg(read->errorString()) : QString();
    emit providerChanged();
    m_key.clear();
    sync();
  });
  job->start();
}

void LyricsTranslator::saveApiKey(const QString &key) {
  if (m_provider == QStringLiteral("local") || m_provider == QStringLiteral("orchard")) return;
  const QString value = key.trimmed();
  if (value.isEmpty()) return;
  const int generation = ++m_keyGeneration;
  m_keyLoading = true;
  m_apiKey.clear();
  m_credentialMessage.clear();
  auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(keychainEntry(m_provider));
  job->setTextData(value);
  connect(job, &QKeychain::Job::finished, this, [this, generation, value](QKeychain::Job *done) {
    if (generation != m_keyGeneration) return;
    m_keyLoading = false;
    if (done->error() == QKeychain::NoError) {
      m_apiKey = value;
    } else {
      m_credentialMessage = tr("Could not save the API key in the keychain: %1").arg(done->errorString());
    }
    emit providerChanged();
    m_key.clear();
    sync();
  });
  job->start();
  emit providerChanged();
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::removeApiKey() {
  if (m_provider == QStringLiteral("local") || m_provider == QStringLiteral("orchard")) return;
  const int generation = ++m_keyGeneration;
  m_apiKey.clear();
  m_keyLoading = false;
  m_credentialMessage.clear();
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(kKeychainService), this);
  job->setKey(keychainEntry(m_provider));
  connect(job, &QKeychain::Job::finished, this, [this, generation](QKeychain::Job *done) {
    if (generation != m_keyGeneration) return;
    if (done->error() != QKeychain::NoError && done->error() != QKeychain::EntryNotFound) {
      m_credentialMessage = tr("Could not remove the API key from the keychain: %1").arg(done->errorString());
      emit providerChanged();
    }
  });
  job->start();
  emit providerChanged();
  cancelRemote();
  m_key.clear();
  sync();
}

QString LyricsTranslator::sourceName() const {
  return QString::fromStdString(lyric_language::displayName(m_source.toStdString()));
}

QVariantList LyricsTranslator::packs() const { return m_packs->list(); }

double LyricsTranslator::installedMegabytes() const { return double(m_packs->installedBytes()) / 1e6; }

void LyricsTranslator::removePack(const QString &code) {
  if (code == m_pack) {
    ++*m_generation;
    m_lines = QStringList(m_texts.size(), QString());
    setStatus(QStringLiteral("failed"), tr("The %1 model was removed.").arg(sourceName()));
  }
  m_packs->remove(code);
}

void LyricsTranslator::retry() {
  cancelRemote();
  m_key.clear();
  sync();
}

void LyricsTranslator::setStatus(const QString &status, const QString &message) {
  m_status = status;
  m_message = message;
  emit stateChanged();
}

void LyricsTranslator::sync() {
  if (!m_enabled || m_lyrics->status() != QStringLiteral("ready")) {
    if (!m_key.isEmpty() || !m_lines.isEmpty() || m_status != (m_enabled ? QStringLiteral("idle") : QStringLiteral("off"))) {
      ++*m_generation;
      cancelRemote();
      m_key.clear();
      m_texts.clear();
      m_lines.clear();
      m_pending.clear();
      m_source.clear();
      m_pack.clear();
      setStatus(m_enabled ? QStringLiteral("idle") : QStringLiteral("off"));
    }
    return;
  }
  // Discord can keep lyrics loaded while nobody looks; only spend CPU on lines someone will read.
  if (!m_lyrics->active()) {
    if (m_provider != QStringLiteral("local") && m_reply) {
      ++*m_generation;
      cancelRemote();
      m_key.clear();
    }
    return;
  }
  QStringList texts;
  for (const QVariant &line : m_lyrics->lines())
    texts.append(lineText(line));
  const QString key = m_lyrics->trackId() + QChar(0x1f) + texts.join(QChar(0x1e));
  if (key == m_key)
    return;
  m_key = key;
  m_texts = texts;
  start();
}

void LyricsTranslator::start() {
  ++*m_generation;
  cancelRemote();
  m_flush.stop();
  m_pending.clear();
  m_progress = 0;
  m_lines = QStringList(m_texts.size(), QString());
  if (tryApple())
    return;
  startProvider();
}

void LyricsTranslator::startProvider() {
  std::vector<std::string> utf8;
  utf8.reserve(m_texts.size());
  for (const QString &text : std::as_const(m_texts))
    utf8.push_back(text.toStdString());
  const lyric_language::SongPlan plan = m_provider == QStringLiteral("local") ? lyric_language::plan(utf8)
      : lyric_language::remotePlan(utf8, m_targetLanguage.toStdString());
  m_source = QString::fromStdString(plan.source);
  m_pack.clear();
  if (m_source.isEmpty() && m_provider == QStringLiteral("local")) {
    setStatus(QStringLiteral("idle"));
    return;
  }
  const TranslationPack *pack = m_provider == QStringLiteral("local") ? TranslationPacks::resolve(m_source, m_quality) : nullptr;
  if (pack)
    m_pack = QLatin1String(pack->id);
  if (m_provider == QStringLiteral("local") && (!pack || !m_packs->available(m_pack))) {
    setStatus(QStringLiteral("unsupported"), tr("%1 lyrics can't be translated yet.").arg(sourceName()));
    return;
  }

  QStringList wanted;
  for (qsizetype i = 0; i < m_texts.size(); ++i) {
    if (!plan.translate[size_t(i)] || m_texts.at(i).isEmpty())
      continue;
    if (!m_pending.contains(m_texts.at(i)))
      wanted.append(m_texts.at(i));
    m_pending[m_texts.at(i)].append(int(i));
  }
  if (m_pending.isEmpty()) {
    setStatus(QStringLiteral("idle"), tr("Lyrics are already in the selected language"));
    return;
  }
  if (m_provider == QStringLiteral("orchard")) {
    runAccountRemote();
    return;
  }
  const QString revision = cacheRevision();
  const QHash<QString, QString> cached = m_store.lookup(revision, wanted);
  for (auto it = cached.cbegin(); it != cached.cend(); ++it) {
    for (const int index : m_pending.take(it.key()))
      m_lines[index] = sameText(it.key(), it.value()) ? QString() : it.value();
  }
  if (m_pending.isEmpty()) {
    setStatus(QStringLiteral("ready"), m_source.isEmpty() ? tr("Lyrics are up to date") : tr("Translated from %1").arg(sourceName()));
    return;
  }
  if (m_provider != QStringLiteral("local")) {
    if (m_model.isEmpty()) { setStatus(QStringLiteral("failed"), tr("Enter a translation model in Settings.")); return; }
    if (m_keyLoading) { setStatus(QStringLiteral("idle"), tr("Checking API key")); return; }
    if (m_apiKey.isEmpty() && m_provider != QStringLiteral("custom")) {
      setStatus(QStringLiteral("failed"), tr("Add an API key in Settings.")); return;
    }
    const QUrl url(remoteTranslationEndpoint(m_provider, m_endpoint));
    if (!url.isValid() || url.host().isEmpty() || !url.userInfo().isEmpty() || !url.query().isEmpty() || !url.fragment().isEmpty() ||
        (url.scheme() != QStringLiteral("https") &&
        !(url.scheme() == QStringLiteral("http") && (m_apiKey.isEmpty() || url.host() == QStringLiteral("localhost") || url.host() == QStringLiteral("127.0.0.1"))))) {
      setStatus(QStringLiteral("failed"), tr("Use HTTPS for an API key. Keyless HTTP APIs and localhost are also allowed.")); return;
    }
    // Follow lyric order, and send each repeated chorus just once.
    for (const QString &text : std::as_const(m_texts))
      if (m_pending.contains(text) && !m_remoteQueue.contains(text)) m_remoteQueue.append(text);
    runRemote();
    return;
  }
  if (m_packs->installed(m_pack)) {
    runModel();
    return;
  }
  setStatus(QStringLiteral("downloading"), tr("Downloading the %1 model").arg(sourceName()));
  m_packs->ensure(m_pack);
}

void LyricsTranslator::runModel() {
  QStringList texts;
  // Song order, so the opening lines land first.
  for (const QString &text : std::as_const(m_texts))
    if (m_pending.contains(text) && !texts.contains(text))
      texts.append(text);
  const int generation = m_generation->load();
  const QString directory = m_packs->directory(m_pack);
  setStatus(QStringLiteral("translating"), tr("Translating from %1").arg(sourceName()));
  QMetaObject::invokeMethod(m_worker, [worker = m_worker, generation, directory, texts] {
    worker->translate(generation, directory, texts);
  });
}

void LyricsTranslator::accept(int generation, const QString &text, const QString &translation) {
  if (generation != m_generation->load())
    return;
  if (const auto *pack = TranslationPacks::find(m_pack))
    m_store.store(QLatin1String(pack->revision), text, translation);
  for (const int index : m_pending.take(text))
    m_lines[index] = sameText(text, translation) ? QString() : translation;
  if (!m_flush.isActive())
    m_flush.start();
}

void LyricsTranslator::flush() { emit stateChanged(); }

void LyricsTranslator::jobFinished(int generation, const QString &error) {
  if (generation != m_generation->load())
    return;
  m_flush.stop();
  m_pending.clear();
  if (!error.isEmpty())
    setStatus(QStringLiteral("failed"), error);
  else
    setStatus(QStringLiteral("ready"), tr("Translated from %1").arg(sourceName()));
}

void LyricsTranslator::packReady(const QString &id) {
  emit packsChanged();
  if (id == m_pack && m_status == QStringLiteral("downloading"))
    runModel();
}

void LyricsTranslator::packFailed(const QString &id, const QString &message) {
  emit packsChanged();
  if (id == m_pack && m_status == QStringLiteral("downloading"))
    setStatus(QStringLiteral("failed"), message);
}

void LyricsTranslator::packProgress(const QString &id, double fraction) {
  if (id != m_pack || m_status != QStringLiteral("downloading"))
    return;
  // Whole percents only; a 20 MB download would otherwise repaint the lyrics thousands of times.
  if (int(fraction * 100) == int(m_progress * 100))
    return;
  m_progress = fraction;
  emit stateChanged();
}
