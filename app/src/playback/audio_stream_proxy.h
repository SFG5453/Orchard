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

#pragma once

#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QTcpServer>
#include <QUrl>
#include <functional>

// Serves only the active stream on an unguessable loopback URL. Qt's media
// backend can seek normally while upstream requests retain YouTube's headers.
class AudioStreamProxy final : public QObject {
    Q_OBJECT
public:
    // Inclusive byte range of a provider stream. `done` gets the bytes, or an
    // empty array and an HTTP-like status on failure.
    using RangeReader = std::function<void(const QJsonObject &stream, qint64 start, qint64 end,
                                           std::function<void(const QByteArray &, int status)> done)>;

    explicit AudioStreamProxy(QObject *parent = nullptr);
    ~AudioStreamProxy() override;
    // Streams carrying "provider" read through this instead of fetching "url".
    void setRangeReader(RangeReader reader) { m_reader = std::move(reader); }
    QUrl open(const QJsonObject &stream);
    QJsonObject stream() const { return m_stream; }
    void setSuspended(bool suspended);
    void clear();
signals:
    void streamFailed(int status, const QString &message);
private:
    QTcpServer m_server;
    QNetworkAccessManager m_network;
    QJsonObject m_stream;
    QByteArray m_path;
    QList<QObject *> m_connections;
    RangeReader m_reader;
    bool m_suspended{false};
    // One report per opened stream; clients that reconnect would repeat it.
    bool m_failed{false};
};
