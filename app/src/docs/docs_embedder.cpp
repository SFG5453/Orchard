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

#include "docs_embedder.h"
#include "model_paths.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>

namespace {
// A loaded worker holds about 50 MB, so an idle one leaves after a minute.
constexpr int kIdleMs = 60'000;
constexpr int kExitGraceMs = 3000;
constexpr qsizetype kMaxReplyBytes = 8 * 1024 * 1024;
} // namespace

DocsEmbedder::DocsEmbedder(QString program, QString models, QObject *parent)
    : QObject(parent)
    , m_program(std::move(program))
    , m_models(std::move(models))
{
    m_idle.setSingleShot(true);
    m_idle.setInterval(kIdleMs);
    connect(&m_idle, &QTimer::timeout, this, &DocsEmbedder::retireWorker);
}

DocsEmbedder::~DocsEmbedder()
{
    if (!m_worker)
        return;
    disconnect(m_worker, nullptr, this, nullptr);
    m_worker->kill();
    m_worker->waitForFinished(1000);
}

QString DocsEmbedder::defaultProgram()
{
    QString program = QDir(QCoreApplication::applicationDirPath()).filePath(QStringLiteral("orchard-adaptive-mix"));
#ifdef Q_OS_WIN
    program += QStringLiteral(".exe");
#endif
    return program;
}

QString DocsEmbedder::defaultModels()
{
    return orchardModelsDirectory();
}

bool DocsEmbedder::available() const
{
    return QFileInfo::exists(m_program)
        && QFileInfo::exists(QDir(m_models).filePath(QStringLiteral("docs-search/model_quantized.onnx")));
}

void DocsEmbedder::embed(const QList<QList<int>> &sequences, Done done)
{
    QJsonArray rows;
    for (const QList<int> &ids : sequences) {
        QJsonArray row;
        for (const int id : ids)
            row.append(id);
        rows.append(row);
    }
    Job job;
    job.request = QJsonDocument(QJsonObject{{QStringLiteral("kind"), QStringLiteral("embed")},
                                            {QStringLiteral("ids"), rows}})
                      .toJson(QJsonDocument::Compact)
        + '\n';
    job.count = sequences.size();
    job.done = std::move(done);
    m_jobs.enqueue(std::move(job));
    pump();
}

void DocsEmbedder::pump()
{
    if (m_busy)
        return;
    if (m_jobs.isEmpty()) {
        if (m_worker)
            m_idle.start();
        return;
    }
    if (!available()) {
        failAll(tr("Docs search is not installed."));
        return;
    }
    if (!m_worker)
        startWorker();
    // Windows reports a failed start inside start(); failAll has already emptied the queue.
    if (!m_worker)
        return;
    m_current = m_jobs.dequeue();
    m_busy = true;
    m_idle.stop();
    m_worker->write(m_current.request);
}

void DocsEmbedder::startWorker()
{
    auto *process = new QProcess(this);
    m_worker = process;
    m_buffer.clear();
    // Driver warnings would otherwise pile up in memory.
    process->setStandardErrorFile(QProcess::nullDevice());
    connect(process, &QProcess::readyReadStandardOutput, this, &DocsEmbedder::readOutput);
    connect(process, &QProcess::errorOccurred, this, [this, process](QProcess::ProcessError error) {
        if (process == m_worker && error == QProcess::FailedToStart)
            failAll(tr("The docs search worker could not start."));
    });
    connect(process, &QProcess::finished, this, [this, process](int, QProcess::ExitStatus) {
        process->deleteLater();
        // A retired worker was replaced already.
        if (process != m_worker)
            return;
        m_worker = nullptr;
        m_buffer.clear();
        m_idle.stop();
        if (m_busy)
            finishCurrent({}, tr("The docs search worker stopped."));
        else
            pump();
    });
    process->start(m_program, {m_models});
}

void DocsEmbedder::readOutput()
{
    if (!m_worker)
        return;
    m_buffer += m_worker->readAllStandardOutput();
    for (;;) {
        const qsizetype end = m_buffer.indexOf('\n');
        if (end < 0) {
            if (m_buffer.size() > kMaxReplyBytes)
                failAll(tr("The docs search worker sent an invalid reply."));
            return;
        }
        const QByteArray line = m_buffer.left(end);
        m_buffer.remove(0, end + 1);
        if (!m_busy)
            continue;

        const QJsonObject reply = QJsonDocument::fromJson(line).object();
        if (reply.contains(QStringLiteral("error"))) {
            finishCurrent({}, reply.value(QStringLiteral("error")).toString());
            continue;
        }
        Vectors vectors;
        const QJsonArray rows = reply.value(QStringLiteral("vectors")).toArray();
        bool valid = rows.size() == m_current.count;
        for (const QJsonValue &row : rows) {
            QList<float> vector;
            for (const QJsonValue &value : row.toArray())
                vector.append(static_cast<float>(value.toDouble()));
            valid = valid && !vector.isEmpty() && (vectors.isEmpty() || vector.size() == vectors.first().size());
            vectors.append(std::move(vector));
        }
        if (valid)
            finishCurrent(vectors, QString());
        else
            finishCurrent({}, tr("The docs search worker sent an invalid reply."));
    }
}

void DocsEmbedder::finishCurrent(const Vectors &vectors, const QString &error)
{
    Done done = std::move(m_current.done);
    m_current = {};
    m_busy = false;
    // The callback may queue the next request.
    if (done)
        done(vectors, error);
    pump();
}

void DocsEmbedder::failAll(const QString &error)
{
    QList<Job> jobs;
    if (m_busy)
        jobs.append(std::move(m_current));
    m_current = {};
    m_busy = false;
    while (!m_jobs.isEmpty())
        jobs.append(m_jobs.dequeue());

    if (m_worker) {
        QProcess *process = m_worker;
        m_worker = nullptr;
        disconnect(process, nullptr, this, nullptr);
        process->kill();
        process->deleteLater();
    }
    m_buffer.clear();
    m_idle.stop();
    for (Job &job : jobs) {
        if (job.done)
            job.done({}, error);
    }
}

void DocsEmbedder::retireWorker()
{
    if (!m_worker || m_busy)
        return;
    QProcess *process = m_worker;
    m_worker = nullptr;
    disconnect(process, &QProcess::readyReadStandardOutput, this, nullptr);
    // End of input ends the worker's request loop.
    process->closeWriteChannel();
    QTimer::singleShot(kExitGraceMs, process, [process] {
        if (process->state() != QProcess::NotRunning)
            process->kill();
    });
}
