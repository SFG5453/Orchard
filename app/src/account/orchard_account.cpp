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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "account/orchard_account.h"

#include <QCryptographicHash>
#include <QDesktopServices>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QSysInfo>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUrlQuery>

#include <qtkeychain/keychain.h>

#include <utility>

namespace {
constexpr auto keychainService = "dev.sfg.orchard";
constexpr auto keychainEntry = "orchard-account";
constexpr auto defaultServiceUrl = "https://account.sfg545.dev";
constexpr int signInTimeoutMs = 5 * 60 * 1000;
constexpr int requestTimeoutMs = 15000;
// Refresh a minute early so a token never expires mid-request.
constexpr qint64 accessTokenSkewSeconds = 60;
constexpr qsizetype maxCallbackHead = 8192;

QString randomUrlToken(int bytes) {
  QByteArray data(bytes, Qt::Uninitialized);
  QRandomGenerator::system()->fillRange(reinterpret_cast<quint32 *>(data.data()), bytes / 4);
  return QString::fromLatin1(data.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

QByteArray callbackPage(int status, const QString &title, const QString &message) {
  const QByteArray body =
      QStringLiteral("<!doctype html><meta charset=\"utf-8\"><title>%1</title>"
                     "<body style=\"font-family:system-ui;background:#1c211e;color:#f0eee7;"
                     "display:grid;place-items:center;height:100vh;margin:0\">"
                     "<main style=\"text-align:center\"><h1>%1</h1><p>%2</p></main></body>")
          .arg(title.toHtmlEscaped(), message.toHtmlEscaped())
          .toUtf8();
  const QByteArray reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
  return "HTTP/1.1 " + QByteArray::number(status) + ' ' + reason +
         "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store"
         "\r\nConnection: close\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n\r\n" + body;
}

void respondAndClose(QTcpSocket *socket, const QByteArray &response) {
  socket->write(response);
  socket->disconnectFromHost();
}

QJsonObject replyJson(QNetworkReply *reply) {
  return QJsonDocument::fromJson(reply->readAll()).object();
}

int httpStatus(QNetworkReply *reply) {
  return reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
}

QString describeFailure(QNetworkReply *reply, const QJsonObject &body) {
  const QString description = body.value(QStringLiteral("error_description")).toString();
  if (!description.isEmpty())
    return description;
  if (reply->error() != QNetworkReply::NoError && httpStatus(reply) == 0)
    return QObject::tr("Could not reach the Orchard account service.");
  return QObject::tr("The Orchard account service returned HTTP %1.").arg(httpStatus(reply));
}
} // namespace

OrchardAccount::OrchardAccount(QObject *parent)
    : OrchardAccount(Options{}, parent) {}

OrchardAccount::OrchardAccount(Options options, QObject *parent)
    : QObject(parent),
      m_serviceUrl(options.serviceUrl),
      m_useKeychain(options.useKeychain),
      m_openBrowser(std::move(options.openBrowser)) {
  if (m_serviceUrl.isEmpty()) {
    const QString overrideUrl = qEnvironmentVariable("ORCHARD_ACCOUNT_URL");
    m_serviceUrl = QUrl(overrideUrl.isEmpty() ? QString::fromLatin1(defaultServiceUrl) : overrideUrl);
  }
  if (!m_openBrowser)
    m_openBrowser = [](const QUrl &url) { QDesktopServices::openUrl(url); };

  m_signInTimeout.setSingleShot(true);
  m_signInTimeout.setInterval(signInTimeoutMs);
  connect(&m_signInTimeout, &QTimer::timeout, this, [this] {
    stopCallbackServer();
    setStatus(QStringLiteral("signed_out"));
    setErrorMessage(tr("Sign-in timed out. Try again."));
  });

  if (m_useKeychain)
    restoreSession();
}

OrchardAccount::~OrchardAccount() { stopCallbackServer(); }

QString OrchardAccount::pkceChallenge(const QString &verifier) {
  return QString::fromLatin1(QCryptographicHash::hash(verifier.toLatin1(), QCryptographicHash::Sha256)
                                 .toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}

OrchardAccount::CallbackResult OrchardAccount::parseCallbackRequest(const QByteArray &requestHead) {
  CallbackResult result;
  const QList<QByteArray> requestLine = requestHead.left(requestHead.indexOf("\r\n")).split(' ');
  if (requestLine.size() != 3 || requestLine[0] != "GET" || !requestLine[2].startsWith("HTTP/1."))
    return result;
  const QUrl target(QStringLiteral("http://127.0.0.1") + QString::fromLatin1(requestLine[1]));
  if (!target.isValid() || target.path() != QStringLiteral("/callback"))
    return result;
  const QUrlQuery query(target);
  result.valid = true;
  result.code = query.queryItemValue(QStringLiteral("code"), QUrl::FullyDecoded);
  result.state = query.queryItemValue(QStringLiteral("state"), QUrl::FullyDecoded);
  result.error = query.queryItemValue(QStringLiteral("error"), QUrl::FullyDecoded);
  return result;
}

QString OrchardAccount::defaultDeviceName() {
  const QString host = QSysInfo::machineHostName().trimmed();
  return host.isEmpty() ? QSysInfo::prettyProductName() : host;
}

QString OrchardAccount::platformName() {
#if defined(Q_OS_WIN)
  return QStringLiteral("windows");
#elif defined(Q_OS_MACOS)
  return QStringLiteral("macos");
#else
  return QStringLiteral("linux");
#endif
}

void OrchardAccount::setStatus(const QString &status) {
  if (m_status == status)
    return;
  m_status = status;
  emit statusChanged();
}

void OrchardAccount::setErrorMessage(const QString &message) {
  if (m_errorMessage == message)
    return;
  m_errorMessage = message;
  emit errorChanged();
}

QNetworkRequest OrchardAccount::jsonRequest(const QString &path) const {
  QNetworkRequest request(m_serviceUrl.resolved(QUrl(path)));
  request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
  request.setTransferTimeout(requestTimeoutMs);
  return request;
}

QNetworkRequest OrchardAccount::authorizedRequest(const QString &path, const QString &token) const {
  QNetworkRequest request = jsonRequest(path);
  request.setRawHeader("Authorization", "Bearer " + token.toLatin1());
  return request;
}

void OrchardAccount::signIn() {
  if (m_status == QStringLiteral("signing_in"))
    return;
  stopCallbackServer();
  setErrorMessage(QString());

  m_callbackServer = new QTcpServer(this);
  // Loopback only: the callback must never be reachable from the network.
  if (!m_callbackServer->listen(QHostAddress::LocalHost, 0)) {
    setErrorMessage(tr("Could not open a local port for sign-in: %1").arg(m_callbackServer->errorString()));
    stopCallbackServer();
    return;
  }
  connect(m_callbackServer, &QTcpServer::newConnection, this, [this] {
    while (m_callbackServer && m_callbackServer->hasPendingConnections())
      handleCallbackConnection(m_callbackServer->nextPendingConnection());
  });

  m_pkceVerifier = randomUrlToken(32);
  m_signInState = randomUrlToken(24);
  m_redirectUri = QStringLiteral("http://127.0.0.1:%1/callback").arg(m_callbackServer->serverPort());

  QUrl url = m_serviceUrl.resolved(QUrl(QStringLiteral("/auth/google/start")));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("redirect_uri"), m_redirectUri);
  query.addQueryItem(QStringLiteral("state"), m_signInState);
  query.addQueryItem(QStringLiteral("code_challenge"), pkceChallenge(m_pkceVerifier));
  query.addQueryItem(QStringLiteral("code_challenge_method"), QStringLiteral("S256"));
  url.setQuery(query);

  ++m_generation;
  m_signInTimeout.start();
  setStatus(QStringLiteral("signing_in"));
  m_openBrowser(url);
}

void OrchardAccount::cancelSignIn() {
  if (m_status != QStringLiteral("signing_in"))
    return;
  ++m_generation;
  stopCallbackServer();
  setStatus(QStringLiteral("signed_out"));
}

void OrchardAccount::stopCallbackServer() {
  m_signInTimeout.stop();
  if (m_callbackServer) {
    m_callbackServer->close();
    m_callbackServer->deleteLater();
    m_callbackServer = nullptr;
  }
}

void OrchardAccount::handleCallbackConnection(QTcpSocket *socket) {
  // Outlive the server, which is torn down before the response is flushed.
  socket->setParent(this);
  connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
  // Browsers that never finish their request get dropped.
  QTimer::singleShot(10000, socket, [socket] { socket->abort(); });
  connect(socket, &QTcpSocket::readyRead, this, [this, socket] {
    const QByteArray buffered = socket->peek(maxCallbackHead);
    if (!buffered.contains("\r\n\r\n")) {
      if (buffered.size() >= maxCallbackHead)
        socket->abort();
      return;
    }
    const QByteArray head = socket->readAll();
    const CallbackResult result = parseCallbackRequest(head);

    // Browsers love a surprise favicon request. Ignore it and keep waiting.
    if (!result.valid) {
      respondAndClose(socket, callbackPage(404, tr("Not found"), QString()));
      return;
    }
    if (m_status != QStringLiteral("signing_in") || result.state != m_signInState) {
      respondAndClose(socket, callbackPage(400, tr("Sign-in failed"),
                                           tr("This sign-in link is stale. Start again from Orchard.")));
      return;
    }

    stopCallbackServer();
    if (!result.error.isEmpty() || result.code.isEmpty()) {
      respondAndClose(socket, callbackPage(400, tr("Sign-in cancelled"), tr("You can close this tab.")));
      setStatus(QStringLiteral("signed_out"));
      setErrorMessage(result.error == QStringLiteral("access_denied")
                          ? QString()
                          : tr("Google sign-in failed. Try again."));
      return;
    }
    respondAndClose(socket, callbackPage(200, tr("Signed in to Orchard"),
                                         tr("You can close this tab and go back to the music.")));
    redeemCode(result.code);
  });
}

void OrchardAccount::redeemCode(const QString &code) {
  const QJsonObject body{
      {QStringLiteral("grant_type"), QStringLiteral("authorization_code")},
      {QStringLiteral("code"), code},
      {QStringLiteral("code_verifier"), m_pkceVerifier},
      {QStringLiteral("redirect_uri"), m_redirectUri},
      {QStringLiteral("device"), QJsonObject{{QStringLiteral("name"), defaultDeviceName()},
                                             {QStringLiteral("platform"), platformName()}}},
  };
  m_pkceVerifier.clear();
  const quint64 generation = m_generation;
  QNetworkReply *reply = m_network.post(jsonRequest(QStringLiteral("/auth/token")),
                                        QJsonDocument(body).toJson(QJsonDocument::Compact));
  connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
    reply->deleteLater();
    if (generation != m_generation)
      return;
    const QJsonObject response = replyJson(reply);
    if (httpStatus(reply) != 200) {
      setStatus(QStringLiteral("signed_out"));
      setErrorMessage(describeFailure(reply, response));
      return;
    }
    acceptTokens(response);
    setStatus(QStringLiteral("signed_in"));
    emit signedIn();
  });
}

void OrchardAccount::acceptTokens(const QJsonObject &body) {
  m_accessToken = body.value(QStringLiteral("access_token")).toString();
  const qint64 lifetime = body.value(QStringLiteral("expires_in")).toInteger();
  m_accessExpiry = QDateTime::currentDateTimeUtc().addSecs(qMax<qint64>(0, lifetime - accessTokenSkewSeconds));
  m_refreshToken = body.value(QStringLiteral("refresh_token")).toString();
  m_deviceId = body.value(QStringLiteral("device_id")).toString();
  const QJsonObject user = body.value(QStringLiteral("user")).toObject();
  m_userId = user.value(QStringLiteral("id")).toString();
  m_userName = user.value(QStringLiteral("name")).toString();
  m_userEmail = user.value(QStringLiteral("email")).toString();
  m_userPicture = user.value(QStringLiteral("picture")).toString();
  setErrorMessage(QString());
  emit userChanged();
  // Every refresh rotates the token, so the keyring copy must follow.
  persistSession();
}

void OrchardAccount::withAccessToken(std::function<void(const QString &)> callback) {
  if (m_refreshToken.isEmpty()) {
    callback(QString());
    return;
  }
  if (!m_accessToken.isEmpty() && QDateTime::currentDateTimeUtc() < m_accessExpiry) {
    callback(m_accessToken);
    return;
  }
  m_tokenWaiters.append(std::move(callback));
  refreshAccessToken();
}

void OrchardAccount::refreshAccessToken() {
  // One refresh at a time: rotation makes a second concurrent one fail.
  if (m_refreshReply || m_refreshToken.isEmpty())
    return;
  const QJsonObject body{{QStringLiteral("grant_type"), QStringLiteral("refresh_token")},
                         {QStringLiteral("refresh_token"), m_refreshToken}};
  const quint64 generation = m_generation;
  m_refreshReply = m_network.post(jsonRequest(QStringLiteral("/auth/token")),
                                  QJsonDocument(body).toJson(QJsonDocument::Compact));
  QNetworkReply *reply = m_refreshReply;
  connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
    reply->deleteLater();
    if (m_refreshReply == reply)
      m_refreshReply = nullptr;
    if (generation != m_generation)
      return;
    const QJsonObject response = replyJson(reply);
    const int status = httpStatus(reply);
    if (status == 200) {
      acceptTokens(response);
      setStatus(QStringLiteral("signed_in"));
      finishTokenWaiters(m_accessToken);
      return;
    }
    if (status == 400 && response.value(QStringLiteral("error")).toString() == QStringLiteral("invalid_grant")) {
      clearSession();
      setErrorMessage(tr("You were signed out of Orchard. Sign in again."));
    }
    // Anything else is probably the network. Keep the session for later.
    finishTokenWaiters(QString());
  });
}

void OrchardAccount::finishTokenWaiters(const QString &token) {
  const auto waiters = std::exchange(m_tokenWaiters, {});
  for (const auto &waiter : waiters)
    waiter(token);
}

void OrchardAccount::signOut() {
  if (!m_refreshToken.isEmpty()) {
    // Best effort. The local session goes away regardless.
    QNetworkReply *reply = m_network.post(
        jsonRequest(QStringLiteral("/auth/logout")),
        QJsonDocument(QJsonObject{{QStringLiteral("refresh_token"), m_refreshToken}}).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, reply, &QObject::deleteLater);
  }
  cancelSignIn();
  clearSession();
  setErrorMessage(QString());
}

void OrchardAccount::clearSession() {
  ++m_generation;
  if (m_refreshReply) {
    m_refreshReply->abort();
    m_refreshReply = nullptr;
  }
  m_accessToken.clear();
  m_refreshToken.clear();
  m_deviceId.clear();
  m_userId.clear();
  m_userName.clear();
  m_userEmail.clear();
  m_userPicture.clear();
  m_devices.clear();
  m_devicesLoading = false;
  deleteSession();
  emit userChanged();
  emit devicesChanged();
  setStatus(QStringLiteral("signed_out"));
  finishTokenWaiters(QString());
}

void OrchardAccount::refreshDevices() {
  m_devicesLoading = true;
  emit devicesChanged();
  const quint64 generation = m_generation;
  withAccessToken([this, generation](const QString &token) {
    if (generation != m_generation)
      return;
    if (token.isEmpty()) {
      m_devicesLoading = false;
      emit devicesChanged();
      return;
    }
    QNetworkReply *reply = m_network.get(authorizedRequest(QStringLiteral("/devices"), token));
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
      reply->deleteLater();
      if (generation != m_generation)
        return;
      m_devicesLoading = false;
      const QJsonObject body = replyJson(reply);
      if (httpStatus(reply) == 200) {
        m_devices.clear();
        for (const QJsonValue &value : body.value(QStringLiteral("devices")).toArray()) {
          const QJsonObject device = value.toObject();
          m_devices.append(QVariantMap{
              {QStringLiteral("id"), device.value(QStringLiteral("id")).toString()},
              {QStringLiteral("name"), device.value(QStringLiteral("name")).toString()},
              {QStringLiteral("platform"), device.value(QStringLiteral("platform")).toString()},
              {QStringLiteral("current"), device.value(QStringLiteral("current")).toBool()},
              {QStringLiteral("lastSeen"),
               QDateTime::fromSecsSinceEpoch(device.value(QStringLiteral("last_seen_at")).toInteger())},
          });
        }
      } else if (httpStatus(reply) == 401) {
        // Revoked from another device. The refresh will confirm and sign out.
        m_accessToken.clear();
        refreshAccessToken();
      } else {
        setErrorMessage(describeFailure(reply, body));
      }
      emit devicesChanged();
    });
  });
}

void OrchardAccount::removeDevice(const QString &deviceId) {
  if (deviceId.isEmpty())
    return;
  if (deviceId == m_deviceId) {
    signOut();
    return;
  }
  const quint64 generation = m_generation;
  withAccessToken([this, deviceId, generation](const QString &token) {
    if (generation != m_generation || token.isEmpty())
      return;
    QNetworkReply *reply = m_network.deleteResource(
        authorizedRequest(QStringLiteral("/devices/") + QString::fromLatin1(QUrl::toPercentEncoding(deviceId)), token));
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
      reply->deleteLater();
      if (generation != m_generation)
        return;
      if (httpStatus(reply) != 204 && httpStatus(reply) != 404)
        setErrorMessage(describeFailure(reply, replyJson(reply)));
      refreshDevices();
    });
  });
}

void OrchardAccount::restoreSession() {
  setStatus(QStringLiteral("restoring"));
  const quint64 generation = m_generation;
  auto *job = new QKeychain::ReadPasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [this, generation](QKeychain::Job *baseJob) {
    // A sign-in started while the keyring was thinking wins.
    if (generation != m_generation)
      return;
    auto *readJob = static_cast<QKeychain::ReadPasswordJob *>(baseJob);
    const QJsonObject secret = QJsonDocument::fromJson(readJob->textData().toUtf8()).object();
    if (readJob->error() != QKeychain::NoError || secret.value(QStringLiteral("refreshToken")).toString().isEmpty()) {
      if (readJob->error() != QKeychain::NoError && readJob->error() != QKeychain::EntryNotFound)
        qWarning().noquote() << "Could not read the Orchard account from the OS keyring:" << readJob->errorString();
      setStatus(QStringLiteral("signed_out"));
      return;
    }
    m_refreshToken = secret.value(QStringLiteral("refreshToken")).toString();
    m_deviceId = secret.value(QStringLiteral("deviceId")).toString();
    m_userId = secret.value(QStringLiteral("userId")).toString();
    m_userName = secret.value(QStringLiteral("userName")).toString();
    m_userEmail = secret.value(QStringLiteral("userEmail")).toString();
    m_userPicture = secret.value(QStringLiteral("userPicture")).toString();
    emit userChanged();
    // Optimistic so offline starts still look signed in. The refresh
    // confirms the session and signs out if the server revoked it.
    setStatus(QStringLiteral("signed_in"));
    refreshAccessToken();
  });
  job->start();
}

void OrchardAccount::persistSession() {
  if (!m_useKeychain || m_refreshToken.isEmpty())
    return;
  const QJsonObject secret{{QStringLiteral("refreshToken"), m_refreshToken},
                           {QStringLiteral("deviceId"), m_deviceId},
                           {QStringLiteral("userId"), m_userId},
                           {QStringLiteral("userName"), m_userName},
                           {QStringLiteral("userEmail"), m_userEmail},
                           {QStringLiteral("userPicture"), m_userPicture}};
  auto *job = new QKeychain::WritePasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setTextData(QString::fromUtf8(QJsonDocument(secret).toJson(QJsonDocument::Compact)));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [](QKeychain::Job *finishedJob) {
    if (finishedJob->error() != QKeychain::NoError)
      qWarning().noquote() << "Could not store the Orchard account in the OS keyring:" << finishedJob->errorString();
  });
  job->start();
}

void OrchardAccount::deleteSession() {
  if (!m_useKeychain)
    return;
  auto *job = new QKeychain::DeletePasswordJob(QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this, [](QKeychain::Job *finishedJob) {
    if (finishedJob->error() != QKeychain::NoError && finishedJob->error() != QKeychain::EntryNotFound)
      qWarning().noquote() << "Could not remove the Orchard account from the OS keyring:" << finishedJob->errorString();
  });
  job->start();
}
