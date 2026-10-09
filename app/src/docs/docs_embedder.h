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

#include <QByteArray>
#include <QList>
#include <QObject>
#include <QQueue>
#include <QString>
#include <QTimer>
#include <functional>

class QProcess;

// Turns token id sequences into sentence vectors with the docs model, which runs in its own
// orchard-adaptive-mix process. The worker starts with the first request and leaves when idle.
class DocsEmbedder final : public QObject {
    Q_OBJECT

public:
    using Vectors = QList<QList<float>>;
    // Runs once per request: the vectors in request order, or an empty list and an error.
    using Done = std::function<void(const Vectors &vectors, const QString &error)>;

    // program is the worker executable; models holds docs-search/model_quantized.onnx.
    DocsEmbedder(QString program, QString models, QObject *parent = nullptr);
    ~DocsEmbedder() override;

    static QString defaultProgram();
    static QString defaultModels();

    // True when the worker executable and the model file exist.
    [[nodiscard]] bool available() const;

    // Requests run one at a time, in order.
    void embed(const QList<QList<int>> &sequences, Done done);

    // How long an idle worker stays alive; the next request starts a fresh one.
    void setIdleTimeout(int milliseconds) { m_idle.setInterval(milliseconds); }

private:
    struct Job {
        QByteArray request;
        qsizetype count = 0;
        Done done;
    };

    void pump();
    void startWorker();
    void readOutput();
    void finishCurrent(const Vectors &vectors, const QString &error);
    void failAll(const QString &error);
    void retireWorker();

    QString m_program;
    QString m_models;
    QProcess *m_worker = nullptr;
    QByteArray m_buffer;
    QQueue<Job> m_jobs;
    bool m_busy = false;
    Job m_current;
    QTimer m_idle;
};
