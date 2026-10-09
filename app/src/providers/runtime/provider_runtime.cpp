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

#include "provider_runtime.h"
#include "orchard_core.h"

#include <memory>
#include <utility>

#include <QDir>
#include <QFile>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSaveFile>
#include <QStandardPaths>
#include <QTimer>
#include <QUrl>

// We use quickjs so I don't have to bundle a v8 engine with Orchard, which
// would defeat the point of using qt6
#include "provider_host/provider_host.h"

namespace {

using orchard::provider::FetchRequest;
using orchard::provider::FetchResponse;

// Qt side of the shared provider host: networking, timers and the resource bundle.
class QuickJsRuntimeWorker final : public QObject, public orchard::provider::Platform {
  Q_OBJECT

public:
  explicit QuickJsRuntimeWorker(orchard::provider::Bundle bundle, QObject *parent = nullptr)
      : QObject(parent), m_bundle(std::move(bundle)) {}
  ~QuickJsRuntimeWorker() override { shutdown(); }

signals:
  void succeeded(quint64 requestId, const QJsonValue &result);
  void succeededBytes(quint64 requestId, const QByteArray &bytes);
  void failed(quint64 requestId, const QString &message);

public slots:
  void invoke(quint64 requestId, const QString &method, const QJsonValue &payload) {
    if (!m_host) {
      m_network = new QNetworkAccessManager(this);
      m_host = std::make_unique<orchard::provider::ProviderHost>(*this, m_bundle);
    }
    QByteArray json;
    if (payload.isObject()) {
      json = QJsonDocument(payload.toObject()).toJson(QJsonDocument::Compact);
    } else if (payload.isArray()) {
      json = QJsonDocument(payload.toArray()).toJson(QJsonDocument::Compact);
    } else {
      json = QJsonDocument(QJsonArray{payload}).toJson(QJsonDocument::Compact);
      json = json.mid(1, json.size() - 2);
    }
    m_host->invoke(requestId, method.toStdString(), std::string_view(json.constData(), json.size()));
  }

  void cancelAll() {
    const auto replies = m_fetches.keys();
    for (QNetworkReply *reply : replies) {
      if (reply)
        reply->abort();
    }
  }

private:
  void shutdown() {
    // Aborting must not resolve promises inside a host that is being torn down.
    for (auto it = m_fetches.begin(); it != m_fetches.end(); ++it) {
      it.key()->disconnect(this);
      it.key()->abort();
      it.key()->deleteLater();
    }
    m_fetches.clear();
    qDeleteAll(m_timers);
    m_timers.clear();
    m_host.reset();
  }

  void fetch(FetchRequest fetch) override {
    const QUrl url(QString::fromStdString(fetch.url));
    QNetworkRequest request(url);
    // Match the app's other YouTube network paths: avoid HTTP/2 negotiation
    // stalls before the player API and media probe can return.
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::NoLessSafeRedirectPolicy);
    // The provider owns cookies and signs that exact snapshot. Qt's shared jar
    // would replace it after any Set-Cookie response, breaking later requests.
    // Keep guest requests cookie-free too; the jar is not an account manager.
    request.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    request.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    for (const auto &[name, value] : fetch.headers)
      request.setRawHeader(QByteArray::fromStdString(name), QByteArray::fromStdString(value));
    QNetworkReply *reply = m_network->sendCustomRequest(
        request, QByteArray::fromStdString(fetch.method), QByteArray::fromStdString(fetch.body));
    m_fetches.insert(reply, {fetch.id, url});
    QObject::connect(reply, &QNetworkReply::finished, this, [this, reply] { finishFetch(reply); });
  }

  void finishFetch(QNetworkReply *reply) {
    reply->deleteLater();
    if (!m_fetches.contains(reply) || !m_host)
      return;
    const PendingFetch pending = m_fetches.take(reply);
    FetchResponse response;
    response.status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (reply->error() != QNetworkReply::NoError && response.status == 0) {
      response.error = reply->errorString().toStdString();
    } else {
      response.statusText =
          reply->attribute(QNetworkRequest::HttpReasonPhraseAttribute).toString().toStdString();
      response.url = reply->url().toString().toStdString();
      response.redirected = reply->url() != pending.originalUrl;
      response.contentType = reply->header(QNetworkRequest::ContentTypeHeader).toByteArray().toStdString();
      response.body = reply->readAll().toStdString();
      for (const auto &header : reply->rawHeaderPairs())
        response.headers.emplace_back(header.first.toStdString(), header.second.toStdString());
    }
    m_host->completeFetch(pending.id, response);
  }

  void startTimer(int id, int delayMs, bool repeat) override {
    auto *timer = new QTimer(this);
    timer->setSingleShot(!repeat);
    m_timers.insert(id, timer);
    QObject::connect(timer, &QTimer::timeout, this, [this, id, repeat] {
      if (!repeat)
        if (QTimer *timer = m_timers.take(id))
          timer->deleteLater();
      if (m_host)
        m_host->fireTimer(id);
    });
    timer->start(delayMs);
  }

  // Deferred: JS may clear a timer from inside its own timeout.
  void stopTimer(int id) override {
    if (QTimer *timer = m_timers.take(id)) {
      timer->stop();
      timer->deleteLater();
    }
  }

  bool loadBundle(std::string_view name, std::string &bytes) override {
    QFile bundle(QStringLiteral(":/providers/") + QString::fromUtf8(name.data(), name.size()));
    if (!bundle.open(QIODevice::ReadOnly))
      return false;
    bytes = bundle.readAll().toStdString();
    return true;
  }

  static QString playerCachePath() {
    return QStandardPaths::writableLocation(QStandardPaths::CacheLocation) +
           QStringLiteral("/youtube-player-v1.json");
  }

  bool readPlayerCache(std::string &data) override {
    QFile file(playerCachePath());
    if (!file.open(QIODevice::ReadOnly) || file.size() > 2 * 1024 * 1024)
      return false;
    data = file.readAll().toStdString();
    return true;
  }

  bool writePlayerCache(std::string_view data) override {
    if (!QDir().mkpath(QStandardPaths::writableLocation(QStandardPaths::CacheLocation)))
      return false;
    QSaveFile file(playerCachePath());
    return file.open(QIODevice::WriteOnly) &&
           file.write(data.data(), static_cast<qint64>(data.size())) == static_cast<qint64>(data.size()) &&
           file.commit();
  }

  std::string extractPlayer(std::string_view source) override {
    std::unique_ptr<char, decltype(&orchard_youtube_extract_free)> json(
        orchard_youtube_extract(reinterpret_cast<const uint8_t *>(source.data()), source.size()),
        orchard_youtube_extract_free);
    return json ? std::string(json.get()) : std::string();
  }

  void settled(std::uint64_t requestId, bool ok, std::string result) override {
    if (!ok) {
      emit failed(requestId, QString::fromStdString(result));
      return;
    }
    const QByteArray json = QByteArray::fromStdString(result);
    const QJsonDocument document = QJsonDocument::fromJson(json);
    if (document.isObject()) {
      emit succeeded(requestId, document.object());
    } else if (document.isArray()) {
      emit succeeded(requestId, document.array());
    } else {
      const QJsonDocument wrapped = QJsonDocument::fromJson("[" + json + "]");
      emit succeeded(requestId, wrapped.isArray() && !wrapped.array().isEmpty()
                                    ? wrapped.array().first()
                                    : QJsonValue());
    }
  }

  void settledBytes(std::uint64_t requestId, std::string bytes) override {
    emit succeededBytes(requestId, QByteArray::fromStdString(bytes));
  }

  bool hkdfSha256(std::string_view key, std::string_view salt, std::string_view info,
                  std::size_t length, std::string &out) override {
    out.assign(length, '\0');
    return orchard_hkdf_sha256(bytes(key), key.size(), bytes(salt), salt.size(), bytes(info),
                               info.size(), reinterpret_cast<uint8_t *>(out.data()), length) == 1;
  }

  bool aes128(orchard::provider::Cipher cipher, std::string_view key, std::string_view iv,
              std::string_view data, std::string &out) override {
    out.assign(data);
    auto *buffer = reinterpret_cast<uint8_t *>(out.data());
    if (cipher == orchard::provider::Cipher::Aes128Ctr)
      return orchard_aes128_ctr_apply(bytes(key), bytes(iv), buffer, out.size()) == 1;
    std::size_t written = 0;
    if (orchard_aes128_cbc_decrypt(bytes(key), bytes(iv), bytes(data), data.size(), buffer,
                                   &written) != 1)
      return false;
    out.resize(written);
    return true;
  }

  static const uint8_t *bytes(std::string_view value) {
    return reinterpret_cast<const uint8_t *>(value.data());
  }

  struct PendingFetch {
    std::uint64_t id{0};
    QUrl originalUrl;
  };

  orchard::provider::Bundle m_bundle;
  std::unique_ptr<orchard::provider::ProviderHost> m_host;
  QNetworkAccessManager *m_network{nullptr};
  QHash<QNetworkReply *, PendingFetch> m_fetches;
  QHash<int, QTimer *> m_timers;
};

} // namespace

ProviderRuntime::ProviderRuntime(QObject *parent)
    : ProviderRuntime(orchard::provider::youtubeBundle(), parent) {}

ProviderRuntime::ProviderRuntime(const orchard::provider::Bundle &bundle, QObject *parent)
    : QObject(parent) {
  auto *worker = new QuickJsRuntimeWorker(bundle);
  worker->moveToThread(&m_thread);
  connect(&m_thread, &QThread::finished, worker, &QObject::deleteLater);
  connect(this, &ProviderRuntime::invokeRequested, worker,
          &QuickJsRuntimeWorker::invoke);
  connect(this, &ProviderRuntime::cancelRequested, worker,
          &QuickJsRuntimeWorker::cancelAll);
  connect(worker, &QuickJsRuntimeWorker::succeeded, this,
          &ProviderRuntime::invocationSucceeded);
  connect(worker, &QuickJsRuntimeWorker::succeededBytes, this,
          &ProviderRuntime::invocationBytes);
  connect(worker, &QuickJsRuntimeWorker::failed, this,
          &ProviderRuntime::invocationFailed);
  m_thread.setObjectName(QStringLiteral("OrchardProvider") + QString::fromStdString(bundle.label));
  // Must exceed JS_SetMaxStackSize (2 MiB). Windows threads default to 1 MiB, so
  // QuickJS's overflow check never fired and the player script walked off the stack.
  m_thread.setStackSize(8 * 1024 * 1024);
  m_thread.start();
}

ProviderRuntime::~ProviderRuntime() {
  emit cancelRequested();
  m_thread.quit();
  m_thread.wait();
}

quint64 ProviderRuntime::invoke(const QString &method,
                                const QJsonValue &payload) {
  const quint64 requestId = m_nextRequestId++;
  emit invokeRequested(requestId, method, payload);
  return requestId;
}

void ProviderRuntime::cancelAll() { emit cancelRequested(); }

#include "provider_runtime.moc"
