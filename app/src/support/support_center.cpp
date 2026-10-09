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

#include "support/support_center.h"

#include "account/orchard_account.h"

#include <QDesktopServices>
#include <QGuiApplication>
#include <QHttpMultiPart>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>

namespace {
constexpr int pollIntervalMs = 5 * 60 * 1000;
constexpr int linkPollIntervalMs = 4000;
constexpr qint64 linkWindowSeconds = 10 * 60;
// Coming back to the window refreshes, but not more than once a minute.
constexpr qint64 activationRefreshSeconds = 60;
constexpr int requestTimeoutMs = 20000;

int statusOf(QNetworkReply *reply) {
  return reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
}

QString describe(int status, const QJsonObject &body) {
  const QString description = body.value(QStringLiteral("error_description")).toString();
  if (!description.isEmpty())
    return description;
  if (status == 0)
    return QObject::tr("Could not reach the Orchard account service.");
  return QObject::tr("The Orchard account service returned HTTP %1.").arg(status);
}

QHttpPart textPart(const QString &name, const QString &value) {
  QHttpPart part;
  part.setHeader(QNetworkRequest::ContentDispositionHeader, QStringLiteral("form-data; name=\"%1\"").arg(name));
  part.setBody(value.toUtf8());
  return part;
}
} // namespace

SupportCenter::SupportCenter(OrchardAccount *account, QObject *parent)
    : QObject(parent), m_account(account) {
  m_poll.setInterval(pollIntervalMs);
  connect(&m_poll, &QTimer::timeout, this, &SupportCenter::refresh);
  m_linkPoll.setInterval(linkPollIntervalMs);
  connect(&m_linkPoll, &QTimer::timeout, this, [this] {
    if (QDateTime::currentDateTimeUtc() > m_linkDeadline)
      cancelGithubLink();
    else
      refresh();
  });

  connect(m_account, &OrchardAccount::statusChanged, this, [this] {
    if (m_account->isSignedIn()) {
      refresh();
      m_poll.start();
    } else {
      clearAccountState();
    }
  });
  connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
    if (state != Qt::ApplicationActive || !m_account->isSignedIn())
      return;
    // Back from the browser after linking GitHub, or just back at the desk.
    if (m_githubLinking || !m_lastRefresh.isValid() ||
        m_lastRefresh.secsTo(QDateTime::currentDateTimeUtc()) > activationRefreshSeconds)
      refresh();
  });
  if (m_account->isSignedIn()) {
    refresh();
    m_poll.start();
  }
}

QNetworkRequest SupportCenter::authorized(const QString &path, const QString &token) const {
  QNetworkRequest request(m_account->serviceUrl().resolved(QUrl(path)));
  request.setRawHeader("Authorization", "Bearer " + token.toLatin1());
  request.setTransferTimeout(requestTimeoutMs);
  return request;
}

void SupportCenter::request(const QString &method, const QString &path, QByteArray body,
                            std::function<void(int, const QJsonObject &)> done) {
  const quint64 generation = m_generation;
  m_account->withAccessToken([this, generation, method, path, body = std::move(body),
                              done = std::move(done)](const QString &token) {
    if (generation != m_generation)
      return;
    if (token.isEmpty()) {
      done(401, QJsonObject{});
      return;
    }
    QNetworkRequest request = authorized(path, token);
    QNetworkReply *reply = nullptr;
    if (method == QStringLiteral("GET")) {
      reply = m_network.get(request);
    } else if (method == QStringLiteral("DELETE")) {
      reply = m_network.deleteResource(request);
    } else {
      request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
      reply = m_network.post(request, body);
    }
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation, done] {
      reply->deleteLater();
      if (generation != m_generation)
        return;
      done(statusOf(reply), QJsonDocument::fromJson(reply->readAll()).object());
    });
  });
}

void SupportCenter::setError(const QString &message) {
  if (m_errorMessage == message)
    return;
  m_errorMessage = message;
  emit errorChanged();
}

void SupportCenter::refresh() {
  if (!m_account->isSignedIn() || m_loading)
    return;
  m_loading = true;
  emit reportsChanged();
  request(QStringLiteral("GET"), QStringLiteral("/support/reports"), {}, [this](int status, const QJsonObject &body) {
    m_loading = false;
    m_lastRefresh = QDateTime::currentDateTimeUtc();
    if (status == 200) {
      applyList(body);
    } else {
      // Background polls stay quiet; the popup shows errors from user actions.
      emit reportsChanged();
    }
  });
}

QString SupportCenter::summarizeUpdates(const QVariantList &previous, const QVariantList &current, bool firstLoad) {
  int total = 0;
  QStringList lines;
  QHash<QString, int> before;
  for (const QVariant &value : previous) {
    const QVariantMap report = value.toMap();
    before.insert(report.value(QStringLiteral("id")).toString(), report.value(QStringLiteral("unread")).toInt());
  }
  for (const QVariant &value : current) {
    const QVariantMap report = value.toMap();
    const int unread = report.value(QStringLiteral("unread")).toInt();
    total += unread;
    if (firstLoad || unread <= before.value(report.value(QStringLiteral("id")).toString()))
      continue;
    const QVariantMap latest = report.value(QStringLiteral("latest")).toMap();
    lines.append(QObject::tr("“%1”: %2").arg(report.value(QStringLiteral("title")).toString(),
                                             latest.value(QStringLiteral("title")).toString()));
  }
  // No .qm files ship, so plurals are spelled out instead of using %n.
  if (firstLoad) {
    if (total == 0)
      return {};
    return total == 1 ? QObject::tr("1 new update on your bug reports")
                      : QObject::tr("%1 new updates on your bug reports").arg(total);
  }
  if (lines.size() == 1)
    return lines.constFirst();
  return lines.isEmpty() ? QString() : QObject::tr("%1 of your bug reports have updates").arg(lines.size());
}

void SupportCenter::applyList(const QJsonObject &body) {
  const QJsonObject github = body.value(QStringLiteral("github")).toObject();
  const QString login = github.value(QStringLiteral("login")).toString();
  const bool justLinked = m_githubLinking && !login.isEmpty();
  if (login != m_githubLogin || justLinked) {
    m_githubLogin = login;
    m_githubAvatar = github.value(QStringLiteral("avatar")).toString();
    if (justLinked) {
      m_githubLinking = false;
      m_linkPoll.stop();
      emit notice(tr("GitHub linked as @%1.").arg(login));
    }
    emit githubChanged();
  }

  const QVariantList previous = m_reports;
  const bool firstLoad = !m_loaded;
  m_reports = body.value(QStringLiteral("reports")).toArray().toVariantList();
  m_unread = body.value(QStringLiteral("unread")).toInt();
  m_repository = body.value(QStringLiteral("repository")).toString();
  m_loaded = true;
  emit reportsChanged();

  const QString summary = summarizeUpdates(previous, m_reports, firstLoad);
  if (!summary.isEmpty())
    emit notice(summary);

  // News on the report being read: pull it in and clear the badge.
  const QString activeId = m_activeReport.value(QStringLiteral("id")).toString();
  for (const QVariant &value : std::as_const(m_reports)) {
    const QVariantMap report = value.toMap();
    if (!activeId.isEmpty() && report.value(QStringLiteral("id")).toString() == activeId &&
        report.value(QStringLiteral("unread")).toInt() > 0)
      openReport(activeId);
  }
}

void SupportCenter::openReport(const QString &id) {
  // Placeholder until the reply lands; the id is what late replies are matched on.
  m_activeReport = QVariantMap{{QStringLiteral("id"), id}};
  for (const QVariant &value : std::as_const(m_reports)) {
    if (value.toMap().value(QStringLiteral("id")).toString() == id)
      m_activeReport = value.toMap();
  }
  m_activeLoading = true;
  emit activeChanged();
  request(QStringLiteral("GET"), QStringLiteral("/support/reports/") + id, {},
          [this, id](int status, const QJsonObject &body) {
    if (m_activeReport.value(QStringLiteral("id")).toString() != id)
      return;
    m_activeLoading = false;
    if (status == 200) {
      m_activeReport = body.value(QStringLiteral("report")).toObject().toVariantMap();
      m_activeEvents = body.value(QStringLiteral("events")).toArray().toVariantList();
      if (m_activeReport.value(QStringLiteral("unread")).toInt() > 0)
        markRead(id);
    } else {
      setError(describe(status, body));
    }
    emit activeChanged();
  });
}

void SupportCenter::closeReport() {
  m_activeReport.clear();
  m_activeEvents.clear();
  m_activeLoading = false;
  emit activeChanged();
}

void SupportCenter::markRead(const QString &id) {
  request(QStringLiteral("POST"), QStringLiteral("/support/reports/%1/read").arg(id), {}, [](int, const QJsonObject &) {});
  m_unread = 0;
  for (QVariant &value : m_reports) {
    QVariantMap report = value.toMap();
    if (report.value(QStringLiteral("id")).toString() == id)
      report.insert(QStringLiteral("unread"), 0);
    m_unread += report.value(QStringLiteral("unread")).toInt();
    value = report;
  }
  m_activeReport.insert(QStringLiteral("unread"), 0);
  emit reportsChanged();
}

void SupportCenter::linkGithub() {
  setError(QString());
  request(QStringLiteral("POST"), QStringLiteral("/github/link"), "{}", [this](int status, const QJsonObject &body) {
    const QUrl url(body.value(QStringLiteral("url")).toString());
    if (status != 200 || !url.isValid()) {
      setError(describe(status, body));
      return;
    }
    m_githubLinking = true;
    m_linkDeadline = QDateTime::currentDateTimeUtc().addSecs(linkWindowSeconds);
    m_linkPoll.start();
    emit githubChanged();
    QDesktopServices::openUrl(url);
  });
}

void SupportCenter::cancelGithubLink() {
  m_linkPoll.stop();
  if (!m_githubLinking)
    return;
  m_githubLinking = false;
  emit githubChanged();
}

void SupportCenter::unlinkGithub() {
  request(QStringLiteral("DELETE"), QStringLiteral("/github"), {}, [this](int status, const QJsonObject &body) {
    if (status != 204) {
      setError(describe(status, body));
      return;
    }
    m_githubLogin.clear();
    m_githubAvatar.clear();
    emit githubChanged();
  });
}

void SupportCenter::submit() {
  if (m_submitting)
    return;
  const QString title = m_draftTitle.simplified();
  const QString body = m_draftBody.trimmed();
  if (title.isEmpty() || body.isEmpty()) {
    setError(tr("Add a title and describe what happened."));
    return;
  }
  QByteArray mimeType;
  const QByteArray image = m_screenshot.isNull() ? QByteArray() : encodeScreenshot(m_screenshot, &mimeType);
  if (!m_screenshot.isNull() && image.isEmpty()) {
    setError(tr("The screenshot is too large to send. Remove it or capture a smaller window."));
    return;
  }
  setError(QString());
  m_submitting = true;
  emit submittingChanged();

  const quint64 generation = m_generation;
  const QString kind = m_draftKind;
  const QString diagnostics = m_draftDiagnostics ? m_diagnostics : QString();
  m_account->withAccessToken([=, this](const QString &token) {
    if (generation != m_generation)
      return;
    if (token.isEmpty()) {
      m_submitting = false;
      emit submittingChanged();
      setError(tr("Sign in to your Orchard account first."));
      return;
    }
    auto *form = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    form->append(textPart(QStringLiteral("kind"), kind));
    form->append(textPart(QStringLiteral("title"), title));
    form->append(textPart(QStringLiteral("body"), body));
    if (!diagnostics.isEmpty())
      form->append(textPart(QStringLiteral("diagnostics"), diagnostics));
    if (!image.isEmpty()) {
      QHttpPart part;
      const QString extension = mimeType == "image/png" ? QStringLiteral("png") : QStringLiteral("jpg");
      part.setHeader(QNetworkRequest::ContentDispositionHeader,
                     QStringLiteral("form-data; name=\"screenshot\"; filename=\"screenshot.%1\"").arg(extension));
      part.setHeader(QNetworkRequest::ContentTypeHeader, QString::fromLatin1(mimeType));
      part.setBody(image);
      form->append(part);
    }
    QNetworkReply *reply = m_network.post(authorized(QStringLiteral("/support/reports"), token), form);
    form->setParent(reply);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
      reply->deleteLater();
      if (generation != m_generation)
        return;
      m_submitting = false;
      emit submittingChanged();
      const int status = statusOf(reply);
      const QJsonObject response = QJsonDocument::fromJson(reply->readAll()).object();
      if (status != 201) {
        if (response.value(QStringLiteral("error")).toString() == QStringLiteral("github_required")) {
          m_githubLogin.clear();
          emit githubChanged();
        }
        setError(describe(status, response));
        return;
      }
      const QVariantMap report = response.value(QStringLiteral("report")).toObject().toVariantMap();
      m_reports.prepend(report);
      emit reportsChanged();
      discardDraft();
      emit notice(tr("Report sent as issue #%1.").arg(report.value(QStringLiteral("number")).toInt()));
      emit submitted(report.value(QStringLiteral("url")).toString());
      openReport(report.value(QStringLiteral("id")).toString());
    });
  });
}

void SupportCenter::discardDraft() {
  m_draftKind = QStringLiteral("bug");
  m_draftTitle.clear();
  m_draftBody.clear();
  m_draftDiagnostics = true;
  emit draftChanged();
  m_snapshot = QImage();
  setScreenshot(QImage());
}

void SupportCenter::clearAccountState() {
  ++m_generation;
  m_poll.stop();
  m_linkPoll.stop();
  m_githubLogin.clear();
  m_githubAvatar.clear();
  m_githubLinking = false;
  m_loaded = false;
  m_loading = false;
  m_reports.clear();
  m_unread = 0;
  m_lastRefresh = {};
  emit githubChanged();
  emit reportsChanged();
  closeReport();
}
