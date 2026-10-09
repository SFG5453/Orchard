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
    if (bootstrapper.isEmpty() || m_root.isEmpty() || !QFileInfo::exists(bootstrapper))
        return;
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
    if (!available())
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
    if (!available())
        return;
    QProcess::startDetached(m_bootstrapper, {QStringLiteral("--update-on-exit"), QStringLiteral("--wait-pid"),
                                             QString::number(QCoreApplication::applicationPid())});
    QCoreApplication::quit();
}

void BootstrapperClient::setChannel(const QString &channel)
{
    if (!available() || channel == m_channel)
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
    m_autoUpdate = enabled;
    emit changed();
    run({QStringLiteral("--auto-update"), enabled ? QStringLiteral("on") : QStringLiteral("off")}, false);
}
