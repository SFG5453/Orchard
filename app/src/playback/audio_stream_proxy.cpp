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

#include "audio_stream_proxy.h"
#include "audio_ranges.h"
#include <QNetworkReply>
#include <QPointer>
#include <QRegularExpression>
#include <QTcpSocket>
#include <QTimer>
#include <QUuid>
#include <functional>

namespace {
// Provider reads stay small so a seek never waits behind a large decrypt.
constexpr qint64 kProviderChunkBytes = 512 * 1024;

class StreamConnection final : public QObject {
public:
    StreamConnection(QTcpSocket *socket, QNetworkAccessManager *network,
                     QJsonObject stream, QByteArray path, bool suspended,
                     std::function<void(int, const QString &)> failure,
                     AudioStreamProxy::RangeReader reader)
        : m_socket(socket), m_network(network), m_stream(std::move(stream)),
          m_path(std::move(path)), m_failure(std::move(failure)),
          m_reader(m_stream.contains("provider") ? std::move(reader) : nullptr),
          m_suspended(suspended) {
        socket->setParent(this);
        connect(socket, &QTcpSocket::disconnected, this, &QObject::deleteLater);
        connect(socket, &QTcpSocket::readyRead, this, [this] { readRequest(); });
        connect(socket, &QTcpSocket::bytesWritten, this, [this] { pump(); });
    }
    ~StreamConnection() override {
        if (m_reply) { m_reply->disconnect(this); m_reply->abort(); m_reply->deleteLater(); }
    }
    void setSuspended(bool suspended) {
        if (m_suspended == suspended) return;
        m_suspended = suspended;
        if (m_reader) {
            if (!suspended) pump();
            return;
        }
        if (suspended) {
            if (m_reply && m_headersValidated)
                m_offset = m_chunkEnd + 1 - m_chunkRemaining;
            releaseReply();
            m_retries = 0;
            return;
        }
        if (m_started && m_socket->state() == QAbstractSocket::ConnectedState &&
            !m_reply && m_offset <= m_end)
            fetchChunk();
    }
private:
    void reject(int status) {
        m_socket->write("HTTP/1.1 " + QByteArray::number(status) + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        m_socket->disconnectFromHost();
    }
    void readRequest() {
        if (m_started) return;
        m_request += m_socket->readAll();
        if (m_request.size() > 16384) { m_started = true; reject(431); return; }
        if (!m_request.contains("\r\n\r\n")) return;
        m_started = true;
        const auto lines = m_request.split('\n');
        const auto first = lines.first().trimmed().split(' ');
        if (first.size() != 3 || first[1] != m_path) { reject(404); return; }
        const bool head = first[0] == "HEAD";
        if (!head && first[0] != "GET") { reject(405); return; }
        m_length = static_cast<qint64>(m_stream.value("contentLength").toDouble());
        m_end = m_length - 1;
        bool ranged = false;
        for (const auto &line : lines) {
            if (!line.toLower().startsWith("range:")) continue;
            const auto match = QRegularExpression("^bytes=(\\d*)-(\\d*)$").match(QString::fromLatin1(line.mid(6).trimmed()));
            if (!match.hasMatch() || (match.captured(1).isEmpty() && match.captured(2).isEmpty())) { reject(416); return; }
            bool valid = false;
            if (match.captured(1).isEmpty()) {
                const qint64 suffix = match.captured(2).toLongLong(&valid);
                if (!valid || suffix <= 0) { reject(416); return; }
                m_offset = qMax(qint64(0), m_length - suffix);
            } else {
                m_offset = match.captured(1).toLongLong(&valid);
                if (!valid) { reject(416); return; }
                if (!match.captured(2).isEmpty()) {
                    const auto end = match.captured(2).toLongLong(&valid);
                    if (!valid) { reject(416); return; }
                    m_end = qMin(m_end, end);
                }
            }
            ranged = true;
        }
        if (m_offset < 0 || m_offset > m_end || m_end >= m_length) { reject(416); return; }
        QByteArray type = m_stream.value("mimeType").toString().toLatin1().split(';').first();
        if (type.contains('\r') || type.contains('\n') || type.isEmpty()) type = "application/octet-stream";
        QByteArray headers = ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
        headers += "Content-Type: " + type + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + QByteArray::number(m_end - m_offset + 1);
        if (ranged) headers += "\r\nContent-Range: bytes " + QByteArray::number(m_offset) + "-" + QByteArray::number(m_end) + "/" + QByteArray::number(m_length);
        headers += "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        m_socket->write(headers);
        if (head) { m_socket->disconnectFromHost(); return; }
        if (!m_suspended) fetchChunk();
    }
    void fetchChunk() {
        if (m_reader) { pump(); return; }
        if (m_suspended || m_reply || m_socket->state() != QAbstractSocket::ConnectedState)
            return;
        if (m_offset > m_end) {
            m_socket->disconnectFromHost();
            return;
        }
        m_chunkEnd = qMin(m_end, audioRangeEnd(m_offset, m_length));
        m_chunkRemaining = m_chunkEnd - m_offset + 1;
        m_headersValidated = false;
        QNetworkRequest request(QUrl(m_stream.value("url").toString()));
        request.setTransferTimeout(30000);
        request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
        request.setRawHeader("User-Agent", m_stream.value("userAgent").toString().toUtf8());
        request.setRawHeader("Accept-Encoding", "identity");
        request.setRawHeader("Range", "bytes=" + QByteArray::number(m_offset) + "-" + QByteArray::number(m_chunkEnd));
        const auto origin = m_stream.value("origin").toString().toUtf8();
        if (!origin.isEmpty()) { request.setRawHeader("Origin", origin); request.setRawHeader("Referer", origin + "/"); }
        m_reply = m_network->get(request);
        m_reply->setReadBufferSize(128 * 1024);
        connect(m_reply, &QNetworkReply::readyRead, this, [this] { pump(); });
        connect(m_reply, &QNetworkReply::finished, this, [this] { pump(); });
    }
    bool validateRange(int status) {
        if (status != 206) {
            fail(status, status >= 400 ? QString() :
                AudioStreamProxy::tr("The audio server did not return the requested byte range."));
            return false;
        }
        static const QRegularExpression rangePattern(QStringLiteral("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$"),
                                                      QRegularExpression::CaseInsensitiveOption);
        const auto range = rangePattern.match(QString::fromLatin1(m_reply->rawHeader("Content-Range").trimmed()));
        bool startValid = false, endValid = false, lengthValid = false;
        const qint64 start = range.captured(1).toLongLong(&startValid);
        const qint64 end = range.captured(2).toLongLong(&endValid);
        const qint64 length = range.captured(3).toLongLong(&lengthValid);
        const bool unknownLength = range.captured(3) == QStringLiteral("*");
        if (!range.hasMatch() || !startValid || !endValid || start != m_offset ||
            end < start || end > m_chunkEnd || (!unknownLength && (!lengthValid || length != m_length))) {
            fail(status, AudioStreamProxy::tr("The audio server returned an unexpected byte range."));
            return false;
        }
        const QByteArray contentLength = m_reply->rawHeader("Content-Length");
        if (!contentLength.isEmpty()) {
            bool valid = false;
            const qint64 bytes = contentLength.toLongLong(&valid);
            if (!valid || bytes != end - start + 1) {
                fail(status, AudioStreamProxy::tr("The audio server returned an inconsistent chunk length."));
                return false;
            }
        }
        // A CDN may send less than requested. Take its slice and ask for the
        // next one; the cookie jar has already suffered enough bureaucracy.
        m_chunkEnd = end;
        m_chunkRemaining = end - start + 1;
        m_headersValidated = true;
        return true;
    }
    void releaseReply() {
        if (!m_reply) return;
        m_reply->disconnect(this);
        m_reply->abort();
        m_reply->deleteLater();
        m_reply.clear();
    }
    void retryChunk(int status) {
        if (m_suspended) {
            if (m_headersValidated) m_offset = m_chunkEnd + 1 - m_chunkRemaining;
            releaseReply();
            return;
        }
        if (++m_retries > 3) {
            fail(status, AudioStreamProxy::tr("The audio download was interrupted repeatedly. Please try again."));
            return;
        }
        // Resume after bytes already handed to the player, without duplicates.
        if (m_headersValidated) m_offset = m_chunkEnd + 1 - m_chunkRemaining;
        releaseReply();
        QTimer::singleShot(100 * m_retries, this, [this] {
            if (!m_suspended) fetchChunk();
        });
    }
    // Provider mode: one read in flight, and only while the socket has drained.
    void readProvider() {
        if (m_suspended || m_reading || m_socket->state() != QAbstractSocket::ConnectedState)
            return;
        if (m_offset > m_end) {
            if (!m_socket->bytesToWrite()) m_socket->disconnectFromHost();
            return;
        }
        if (m_socket->bytesToWrite() >= kProviderChunkBytes) return;
        const qint64 end = qMin(m_end, m_offset + kProviderChunkBytes - 1);
        m_reading = true;
        QPointer<StreamConnection> self(this);
        m_reader(m_stream, m_offset, end, [self, end](const QByteArray &bytes, int status) {
            if (!self) return;
            self->m_reading = false;
            if (bytes.size() != end - self->m_offset + 1) {
                self->fail(status ? status : 502, AudioStreamProxy::tr("The audio source stopped responding."));
                return;
            }
            self->m_socket->write(bytes);
            self->m_offset = end + 1;
            self->pump();
        });
    }
    void pump() {
        if (m_reader) { readProvider(); return; }
        if (!m_reply || m_socket->state() != QAbstractSocket::ConnectedState) return;
        const int status = m_reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        if (!status) { if (m_reply->isFinished()) retryChunk(0); return; }
        if (!m_headersValidated && !validateRange(status)) return;
        while (m_reply->bytesAvailable() && m_socket->bytesToWrite() < 128 * 1024) {
            if (m_chunkRemaining == 0) {
                fail(status, AudioStreamProxy::tr("The audio server sent more data than its byte range declared."));
                return;
            }
            const auto data = m_reply->read(qMin(qint64(64 * 1024), m_chunkRemaining));
            if (data.isEmpty()) { retryChunk(status); return; }
            m_socket->write(data);
            m_chunkRemaining -= data.size();
        }
        if (!m_reply->isFinished() || m_reply->bytesAvailable()) return;
        if (m_chunkRemaining != 0) { retryChunk(status); return; }
        // All declared bytes arrived, even if the peer closed just afterward.
        releaseReply();
        m_retries = 0;
        m_offset = m_chunkEnd + 1;
        if (m_offset > m_end) m_socket->disconnectFromHost();
        else fetchChunk();
    }
    void fail(int status, const QString &message = QString()) {
        releaseReply();
        m_socket->abort();
        m_failure(status, message);
    }
    QTcpSocket *m_socket;
    QNetworkAccessManager *m_network;
    QJsonObject m_stream;
    QByteArray m_path, m_request;
    std::function<void(int, const QString &)> m_failure;
    AudioStreamProxy::RangeReader m_reader;
    QPointer<QNetworkReply> m_reply;
    qint64 m_length{0}, m_offset{0}, m_end{0}, m_chunkEnd{0}, m_chunkRemaining{0};
    bool m_started{false};
    bool m_headersValidated{false};
    bool m_suspended{false};
    bool m_reading{false};
    int m_retries{0};
};
}

AudioStreamProxy::AudioStreamProxy(QObject *parent) : QObject(parent) {
    connect(&m_server, &QTcpServer::newConnection, this, [this] {
        while (auto *socket = m_server.nextPendingConnection()) {
            auto *connection = new StreamConnection(socket, &m_network, m_stream, m_path, m_suspended,
                [this](int status, const QString &message) {
                    if (m_failed) return;
                    m_failed = true;
                    emit streamFailed(status, message);
                },
                m_reader);
            connection->setParent(this);
            m_connections.append(connection);
            connect(connection, &QObject::destroyed, this, [this, connection] { m_connections.removeAll(connection); });
        }
    });
}
AudioStreamProxy::~AudioStreamProxy() { clear(); }

QUrl AudioStreamProxy::open(const QJsonObject &stream) {
    clear();
    if (!m_server.isListening() && !m_server.listen(QHostAddress::LocalHost, 0)) return {};
    m_stream = stream;
    m_failed = false;
    m_path = "/" + QUuid::createUuid().toString(QUuid::WithoutBraces).toLatin1();
    return QUrl("http://127.0.0.1:" + QString::number(m_server.serverPort()) + QString::fromLatin1(m_path));
}
void AudioStreamProxy::setSuspended(bool suspended) {
    if (m_suspended == suspended) return;
    m_suspended = suspended;
    const auto connections = m_connections;
    for (auto *connection : connections)
        static_cast<StreamConnection *>(connection)->setSuspended(suspended);
}
void AudioStreamProxy::clear() {
    const auto connections = m_connections;
    for (auto *connection : connections) delete connection;
    m_stream = {};
    m_path.clear();
    m_suspended = false;
}
