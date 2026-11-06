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

#include "update/bootstrapper_client.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include "update/package_release.h"

namespace {

QJsonObject readJson(const QString &path)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return {};
    return QJsonDocument::fromJson(file.readAll()).object();
}

bool busy(const QString &state)
{
    return state == QLatin1String("checking") || state == QLatin1String("downloading") ||
           state == QLatin1String("staging");
}

} // namespace

BootstrapperClient::BootstrapperClient(QObject *parent) : QObject(parent)
{
    const QString bootstrapper = qEnvironmentVariable("ORCHARD_BOOTSTRAPPER");
    m_root = qEnvironmentVariable("ORCHARD_INSTALL_ROOT");
    if (bootstrapper.isEmpty() || m_root.isEmpty() || !QFileInfo::exists(bootstrapper)) {
        m_packaged = qEnvironmentVariableIntValue("ORCHARD_PACKAGE_MANAGED") == 1 ||
                     !qEnvironmentVariableIsEmpty("APPIMAGE");
        if (m_packaged) {
            m_current = QCoreApplication::applicationVersion();
            m_channel = m_current.contains(QLatin1String("-canary")) ? QStringLiteral("canary") : QStringLiteral("stable");
            m_network = new QNetworkAccessManager(this);
            QTimer::singleShot(0, this, &BootstrapperClient::checkForUpdates);
            m_poll.setInterval(6 * 60 * 60 * 1000);
            connect(&m_poll, &QTimer::timeout, this, &BootstrapperClient::checkForUpdates);
            m_poll.start();
        }
        return;
    }
    m_bootstrapper = bootstrapper;
    m_current = qEnvironmentVariable("ORCHARD_INSTALLED_VERSION");
    // Two small file reads; the background updater can finish while we run.
    m_poll.setInterval(30000);
    connect(&m_poll, &QTimer::timeout, this, &BootstrapperClient::refresh);
    m_poll.start();
    refresh();
}

void BootstrapperClient::refresh()
{
    const QJsonObject install = readJson(QDir(m_root).filePath(QStringLiteral("install.json")));
    const QJsonObject status = readJson(QDir(m_root).filePath(QStringLiteral("update-state.json")));
    const QString pending = install.value(QStringLiteral("pending")).toString();

    QString state = status.value(QStringLiteral("state")).toString(QStringLiteral("idle"));
    // A live run owns the state; otherwise install.json is the truth, since a
    // "ready" status outlives the restart that applied it. A busy state nobody
    // has touched for five minutes belongs to a run that was killed.
    const qint64 age = QDateTime::currentSecsSinceEpoch() - status.value(QStringLiteral("updated")).toInteger();
    const bool alive = busy(state) && age < 300;
    if (busy(state) && !alive)
        state = QStringLiteral("idle");
    if (!pending.isEmpty() && !alive)
        state = QStringLiteral("ready");
    else if (state == QLatin1String("ready") || state == QLatin1String("available"))
        state = QStringLiteral("up-to-date");

    m_state = state;
    m_latest = pending.isEmpty() ? status.value(QStringLiteral("latest")).toString() : pending;
    m_channel = install.value(QStringLiteral("channel")).toString(QStringLiteral("stable"));
    m_autoUpdate = install.value(QStringLiteral("autoUpdate")).toBool(true);
    m_error = state == QLatin1String("error") ? status.value(QStringLiteral("error")).toString() : QString();
    m_downloaded = status.value(QStringLiteral("done")).toInteger();
    m_total = status.value(QStringLiteral("total")).toInteger();
    m_poll.setInterval(alive ? 500 : 30000);
    emit changed();
}

void BootstrapperClient::run(const QStringList &arguments, bool detached)
{
    if (!managed())
        return;
    if (detached) {
        QProcess::startDetached(m_bootstrapper, arguments);
        // Pick up the first progress write quickly.
        m_poll.setInterval(500);
        m_poll.start();
        return;
    }
    auto *process = new QProcess(this);
    connect(process, &QProcess::finished, this, [this, process] {
        process->deleteLater();
        refresh();
    });
    process->start(m_bootstrapper, arguments);
}

void BootstrapperClient::checkForUpdates()
{
    if (busy(m_state))
        return;
    if (m_packaged) {
        checkPackageUpdates();
        return;
    }
    if (!managed())
        return;
    m_state = QStringLiteral("checking");
    m_error.clear();
    emit changed();
    run({QStringLiteral("--update")}, true);
}

void BootstrapperClient::cancelDownload()
{
    run({QStringLiteral("--cancel")}, false);
}

void BootstrapperClient::restartToUpdate()
{
    if (!managed())
        return;
    QProcess::startDetached(m_bootstrapper, {QStringLiteral("--update-on-exit"), QStringLiteral("--wait-pid"),
                                             QString::number(QCoreApplication::applicationPid())});
    QCoreApplication::quit();
}

void BootstrapperClient::setChannel(const QString &channel)
{
    if (!managed() || channel == m_channel)
        return;
    m_channel = channel;
    emit changed();
    // Switch, then fetch the other channel's build right away.
    auto *process = new QProcess(this);
    connect(process, &QProcess::finished, this, [this, process] {
        process->deleteLater();
        refresh();
        checkForUpdates();
    });
    process->start(m_bootstrapper, {QStringLiteral("--channel"), channel});
}

void BootstrapperClient::setAutoUpdate(bool enabled)
{
    if (!managed())
        return;
    m_autoUpdate = enabled;
    emit changed();
    run({QStringLiteral("--auto-update"), enabled ? QStringLiteral("on") : QStringLiteral("off")}, false);
}

void BootstrapperClient::checkPackageUpdates()
{
    m_state = QStringLiteral("checking");
    m_error.clear();
    emit changed();
    QNetworkRequest request(QUrl(QStringLiteral("https://depot.sfg545.dev/releases/") + m_channel + QStringLiteral(".json")));
    request.setTransferTimeout(15000);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    auto *reply = m_network->get(request);
    connect(reply, &QNetworkReply::readyRead, reply, [reply] {
        if (reply->bytesAvailable() > 1024 * 1024)
            reply->abort();
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        const QByteArray response = reply->readAll();
        const QString networkError = reply->error() == QNetworkReply::NoError ? QString() : reply->errorString();
        reply->deleteLater();
        QString version;
        m_error = networkError;
        if (m_error.isEmpty() && (response.size() > 1024 * 1024 || !packageReleaseVersion(response, m_channel, &version)))
            m_error = tr("Could not verify release information.");
        if (!m_error.isEmpty()) {
            m_state = QStringLiteral("error");
        } else {
            m_latest = version;
            m_state = packageVersionIsNewer(m_current, m_latest) ? QStringLiteral("available") : QStringLiteral("up-to-date");
            if (m_state == QLatin1String("available") && m_notifiedVersion != m_latest) {
                m_notifiedVersion = m_latest;
                emit noticeRequested(tr("Orchard %1 is available. Install it using your usual update method.").arg(m_latest));
            }
        }
        emit changed();
    });
}
