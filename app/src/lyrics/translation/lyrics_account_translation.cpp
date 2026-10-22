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
#include "remote_translation.h"
#include "account/orchard_account.h"
#include "lyrics/lyrics_controller.h"
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QTimer>

void LyricsTranslator::setAccount(OrchardAccount *account) {
  if (m_account) disconnect(m_account, nullptr, this, nullptr);
  m_account = account;
  acceptAccountQuota({});
  if (account) connect(account, &OrchardAccount::statusChanged, this, [this] {
    acceptAccountQuota({});
    if (m_provider != QStringLiteral("orchard")) return;
    refreshAccountQuota();
    ++*m_generation;
    cancelRemote();
    m_key.clear();
    sync();
  });
  if (m_provider == QStringLiteral("orchard")) { refreshAccountQuota(); m_key.clear(); sync(); }
}

QString LyricsTranslator::accountQuotaMessage() const {
  if (m_accountQuota.value(QStringLiteral("resets_at")).toInteger() <= QDateTime::currentSecsSinceEpoch())
    return tr("Translation allowance unavailable.");
  return tr("%1 translations left today. Your account allows up to %2 per day.")
      .arg(m_accountQuota.value(QStringLiteral("remaining")).toInteger())
      .arg(m_accountQuota.value(QStringLiteral("daily_limit")).toInteger());
}

void LyricsTranslator::acceptAccountQuota(const QJsonObject &body) {
  ++m_quotaGeneration;
  const auto quota = body.value(QStringLiteral("quota")).toObject();
  const auto limit = quota.value(QStringLiteral("daily_limit")).toInteger(-1);
  const auto remaining = quota.value(QStringLiteral("remaining")).toInteger(-1);
  m_accountQuota = limit >= 0 && remaining >= 0 && remaining <= limit &&
      quota.value(QStringLiteral("resets_at")).toInteger() > QDateTime::currentSecsSinceEpoch() ? quota : QJsonObject();
  emit accountQuotaChanged();
}

void LyricsTranslator::refreshAccountQuota() {
  if (!m_account || !m_account->isSignedIn()) { acceptAccountQuota({}); return; }
  if (m_accountQuota.value(QStringLiteral("resets_at")).toInteger() <= QDateTime::currentSecsSinceEpoch())
    acceptAccountQuota({});
  const int generation = ++m_quotaGeneration;
  QPointer<LyricsTranslator> guard(this);
  const QString userId = m_account->userId();
  m_account->withAccessToken([guard, generation, userId](const QString &token) {
    if (!guard || generation != guard->m_quotaGeneration || !guard->m_account ||
        !guard->m_account->isSignedIn() || guard->m_account->userId() != userId) return;
    if (token.isEmpty()) { guard->acceptAccountQuota({}); return; }
    QNetworkRequest request(guard->m_account->serviceUrl().resolved(QUrl(QStringLiteral("/lyrics/quota"))));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setRawHeader("Authorization", QByteArray("Bearer ") + token.toUtf8());
    request.setTransferTimeout(15000);
    auto *reply = guard->m_network->get(request);
    QTimer::singleShot(15000, reply, [reply] { if (!reply->isFinished()) reply->abort(); });
    connect(reply, &QNetworkReply::finished, guard.data(), [guard, reply, generation, userId] {
      reply->deleteLater();
      if (!guard || generation != guard->m_quotaGeneration || !guard->m_account ||
          !guard->m_account->isSignedIn() || guard->m_account->userId() != userId) return;
      guard->acceptAccountQuota(reply->error() == QNetworkReply::NoError
          ? QJsonDocument::fromJson(reply->readAll()).object() : QJsonObject());
    });
  });
}

void LyricsTranslator::runAccountRemote() {
  if (!m_account || !m_account->isSignedIn()) {
    setStatus(QStringLiteral("failed"), tr("Sign in to Orchard to translate lyrics."));
    return;
  }
  QStringList texts;
  for (const QString &text : std::as_const(m_texts))
    if (m_pending.contains(text) && !texts.contains(text)) texts.append(text);
  if (texts.isEmpty()) { setStatus(QStringLiteral("ready")); return; }
  const int generation = m_generation->load();
  const QString songId = m_lyrics->trackId();
  QPointer<LyricsTranslator> guard(this);
  m_account->withAccessToken([guard, generation, songId, texts](const QString &token) {
    if (!guard || generation != guard->m_generation->load()) return;
    auto *self = guard.data();
    if (token.isEmpty()) {
      self->setStatus(QStringLiteral("failed"), self->tr("Sign in to Orchard again."));
      return;
    }
    QNetworkRequest request(self->m_account->serviceUrl().resolved(QUrl(QStringLiteral("/lyrics/translate"))));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setRawHeader("Authorization", QByteArray("Bearer ") + token.toUtf8());
    request.setTransferTimeout(130000);
    QJsonArray input;
    for (const QString &text : texts) input.append(text);
    const QJsonObject payload{{QStringLiteral("song_id"), songId}, {QStringLiteral("lines"), input},
                              {QStringLiteral("target_language"), self->m_targetLanguage}};
    auto *reply = self->m_network->post(request, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    self->m_reply = reply;
    // Qt's transfer timeout is an inactivity timeout; this bounds total wait too.
    QTimer::singleShot(130000, reply, [reply] { if (!reply->isFinished()) reply->abort(); });
    self->setStatus(QStringLiteral("translating"), self->tr("Translating with Orchard Account"));
    connect(reply, &QNetworkReply::finished, self, [self, reply, generation, texts] {
      if (reply != self->m_reply || generation != self->m_generation->load()) { reply->deleteLater(); return; }
      self->m_reply = nullptr;
      const QByteArray body = reply->readAll();
      const int http = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
      const auto error = reply->error();
      reply->deleteLater();
      self->acceptAccountQuota(QJsonDocument::fromJson(body).object());
      if (error != QNetworkReply::NoError || http >= 300) {
        const QString code = QJsonDocument::fromJson(body).object().value(QStringLiteral("error")).toString();
        const bool timedOut = error == QNetworkReply::TimeoutError || error == QNetworkReply::OperationCanceledError || http == 504;
        const QString message = timedOut ? self->tr("Translation took too long. Try again.")
            : code == QStringLiteral("translation_busy") ? self->tr("Translation is busy. Try again shortly.")
            : code == QStringLiteral("translation_invalid_result") ? self->tr("The model returned invalid lyric lines. Try again.")
            : http == 429 ? self->tr("No translations left today. Resets at 00:00 UTC. Cached songs are still available.")
            : http == 409 ? self->tr("This song is being translated. Try again shortly.")
            : http == 401 ? self->tr("Sign in to Orchard again.")
            : self->tr("Account translation failed. Try again later.");
        self->setStatus(QStringLiteral("failed"), message);
        return;
      }
      const QStringList outputs = parseRemoteTranslation(body, QStringLiteral("orchard"), texts.size());
      if (outputs.size() != texts.size()) {
        self->setStatus(QStringLiteral("failed"), self->tr("The translation returned an invalid number of lines."));
        return;
      }
      for (qsizetype i = 0; i < texts.size(); ++i)
        for (const int index : self->m_pending.take(texts.at(i)))
          self->m_lines[index] = texts.at(i).simplified().compare(outputs.at(i).simplified(), Qt::CaseInsensitive) == 0
              ? QString() : outputs.at(i);
      self->setStatus(QStringLiteral("ready"), self->tr("Translated with Orchard Account"));
    });
  });
}
