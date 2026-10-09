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

#include "providers/runtime/provider_runtime.h"
#include "provider_host/provider_host.h"
#include "orchard_core.h"

extern "C" {
#include <quickjs.h>
}

#include <memory>
#include "playback/audio_ranges.h"
#include "providers/youtube/catalog/youtube_catalog.h"
#include "providers/youtube/youtube_provider.h"

#include <QHostAddress>
#include <QFile>
#include <QJsonArray>
#include <QJsonObject>
#include <QJsonDocument>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>
#include <QtTest>

class ProviderRuntimeTest final : public QObject
{
    Q_OBJECT

private slots:
    void poMinterRunsInBareQuickJs()
    {
        // No Node or browser globals. Mock only the network and event loop;
        // the shipped bytecode must provide its own actual DOM compatibility.
        std::unique_ptr<JSRuntime, decltype(&JS_FreeRuntime)> runtime(JS_NewRuntime(), JS_FreeRuntime);
        std::unique_ptr<JSContext, decltype(&JS_FreeContext)> context(JS_NewContext(runtime.get()), JS_FreeContext);
        JS_SetMemoryLimit(runtime.get(), 256 * 1024 * 1024);
        JS_SetMaxStackSize(runtime.get(), 2 * 1024 * 1024);
        auto evaluate = [&](const QByteArray &source) {
            JSValue result = JS_Eval(context.get(), source.constData(), source.size(), "po-test.js", JS_EVAL_TYPE_GLOBAL);
            const bool failed = JS_IsException(result);
            if (failed) result = JS_GetException(context.get());
            const char *text = JS_ToCString(context.get(), result);
            const QByteArray value(text ? text : "");
            JS_FreeCString(context.get(), text);
            JS_FreeValue(context.get(), result);
            return qMakePair(failed, value);
        };
        const auto setup = evaluate(R"JS(
            globalThis.setTimeout = () => 1;
            globalThis.clearTimeout = () => {};
            globalThis.requests = 0;
            globalThis.fetch = async (url, init) => {
                requests++;
                const data = url.endsWith('/Create') ? [[
                    'test', [`globalThis.testGuard = { a(program, setup) {
                        setup((done, args) => {
                            args[2][0] = () => binding => binding;
                            done('snapshot');
                        }, () => {}, () => {}, () => {});
                        return [() => 'snapshot'];
                    } };`], [], 'hash', 'program', 'testGuard'
                ]] : [btoa('integrity'), 600, 30];
                return {ok: true, status: 200, json: async () => data};
            };
        )JS");
        QVERIFY2(!setup.first, setup.second.constData());
        QFile bundle(QStringLiteral(":/providers/youtube-po-minter.qjc"));
        QVERIFY(bundle.open(QIODevice::ReadOnly));
        const auto bytes = bundle.readAll();
        JSValue compiled = JS_ReadObject(context.get(), reinterpret_cast<const uint8_t *>(bytes.constData()),
                                         bytes.size(), JS_READ_OBJ_BYTECODE);
        QVERIFY(!JS_IsException(compiled));
        JSValue result = JS_EvalFunction(context.get(), compiled);
        const bool failed = JS_IsException(result);
        if (failed) result = JS_GetException(context.get());
        const char *error = JS_ToCString(context.get(), result);
        const QByteArray detail(error ? error : "");
        JS_FreeCString(context.get(), error);
        JS_FreeValue(context.get(), result);
        QVERIFY2(!failed, detail.constData());
        const auto start = evaluate(R"JS(
            globalThis.completed = null;
            (async () => {
                const first = await OrchardYouTubePoToken.get('abcdefghijk');
                const cached = await OrchardYouTubePoToken.get('abcdefghijk');
                const second = await OrchardYouTubePoToken.get('lmnopqrstuv');
                completed = [atob(first), first === cached, atob(second), requests];
            })().catch(error => completed = {error: error.message});
        )JS");
        QVERIFY2(!start.first, start.second.constData());
        JSContext *jobContext = nullptr;
        int jobResult;
        do { jobResult = JS_ExecutePendingJob(runtime.get(), &jobContext); } while (jobResult > 0);
        QVERIFY(jobResult == 0);
        const auto actual = evaluate("JSON.stringify(completed)");
        QVERIFY2(!actual.first, actual.second.constData());
        QCOMPARE(actual.second, QByteArray(R"(["abcdefghijk",true,"lmnopqrstuv",2])"));
    }

    void nativePlayerExtraction_data()
    {
        QTest::addColumn<QByteArray>("source");
        const QByteArray target = R"JS(var solve = function(url, name = '', signature = '') {
            url = new g.URL(url); url.set('alr', 'yes'); url.set(name, signature); return url;
        };)JS";
        const QByteArray timestamp = "var config = function() { return { signatureTimestamp: 20702 }; };";
        const QByteArray helpers = R"JS(var setValue = function(key, value) { this.params[key] = value; };
            g.URL = function(value) { this.params = {}; this.set = setValue;
                this.get = function(key) { return this.params[key]; }; };)JS";
        auto row = [&](const char *name, const QByteArray &body) {
            QTest::newRow(name) << QByteArray("(function(g) { " + body + timestamp + " })(globalThis);");
        };
        row("forward dependencies", target + helpers);
        row("backward dependencies", helpers + target);
        QByteArray predeclared = target + helpers;
        predeclared.replace("var solve =", "solve =");
        predeclared.replace("var setValue =", "setValue =");
        row("predeclared functions", "var solve, setValue;" + predeclared);
        row("reused prototype aliases", target + R"JS(
            var other = function() {};
            g.t = other.prototype;
            g.t.get = function() { throw new Error('wrong prototype'); };
            g.URL = function() { this.params = {}; };
            g.t = g.URL.prototype;
            g.t.get = function(key) { return this.params[key]; };
            g.t.set = function(key, value) { this.params[key] = value; };
        )JS");
        row("direct prototype assignments", target + R"JS(
            g.URL = function() { this.params = {}; };
            g.URL.prototype.get = function(key) { return this.params[key]; };
            g.URL.prototype.set = function(key, value) { this.params[key] = value; };
        )JS");
        row("computed prototype assignments", target + R"JS(
            g.URL = function() { this.params = {}; };
            g.URL.prototype['get'] = function(key) { return this.params[key]; };
            g.URL.prototype['set'] = function(key, value) { this.params[key] = value; };
        )JS");
        row("classes and self assignments", target + R"JS(
            g.URL = class { constructor() { this.params = {}; }
                get(key) { return this.params[key]; }
                set(key, value) { this.params[key] = value; } };
            g.URL.prototype.set = g.URL.prototype.set;
        )JS");
        row("scoped and cyclic dependencies", target + R"JS(
            var helper = function(value) { return bounce() ? value : ''; };
            var bounce = function() { return typeof helper === 'function'; };
            var local = function() { throw new Error('shadowed binding'); };
            g.URL = function() { this.params = {}; };
            g.URL.prototype.get = function(key) { return this.params[key]; };
            g.URL.prototype.set = function(key, value) {
                var local = ({value}) => helper(value); this.params[key] = local({value});
            };
        )JS");
        row("side effects are excluded", target + R"JS(
            var boot = function() { throw new Error('player initialization executed'); };
            var service = boot();
            g.URL = function() { if (service) boot(); this.params = {}; };
            g.URL.prototype.get = function(key) { return this.params[key]; };
            g.URL.prototype.set = function(key, value) { this.params[key] = value; };
            boot();
        )JS");
        row("UTF-8 source offsets", "var unicode = 'café 🎵';" + target + helpers);
    }

    void nativePlayerExtraction()
    {
        QFETCH(QByteArray, source);
        std::unique_ptr<char, decltype(&orchard_youtube_extract_free)> json(
            orchard_youtube_extract(reinterpret_cast<const uint8_t *>(source.constData()), source.size()),
            orchard_youtube_extract_free);
        QVERIFY(json);
        const QJsonObject result = QJsonDocument::fromJson(json.get()).object();
        QVERIFY2(!result.contains("error"), qPrintable(result.value("error").toString()));
        QCOMPARE(result.value("exportedRawValues").toObject().value("signatureTimestampVar").toString(), "20702");
        const QByteArray program = result.value("output").toString().toUtf8() + R"JS(
            const url = exportedVars.nsigFunction('https://example.com', 'sig', 'signed');
            JSON.stringify([url.get('sig'), url.get('alr')]);
        )JS";
        std::unique_ptr<JSRuntime, decltype(&JS_FreeRuntime)> runtime(JS_NewRuntime(), JS_FreeRuntime);
        std::unique_ptr<JSContext, decltype(&JS_FreeContext)> context(JS_NewContext(runtime.get()), JS_FreeContext);
        JSValue value = JS_Eval(context.get(), program.constData(), program.size(), "extracted-player.js", JS_EVAL_TYPE_GLOBAL);
        const bool failed = JS_IsException(value);
        if (failed) value = JS_GetException(context.get());
        const char *text = JS_ToCString(context.get(), value);
        const QByteArray actual(text ? text : "");
        JS_FreeCString(context.get(), text);
        JS_FreeValue(context.get(), value);
        QVERIFY2(!failed, actual.constData());
        QCOMPARE(actual, QByteArray(R"(["signed","yes"])"));
    }

    void audioDownloadsUseSmallContiguousRanges()
    {
        constexpr qint64 length = 3449447;
        qint64 offset = 0;
        int requests = 0;
        while (offset < length) {
            const qint64 end = audioRangeEnd(offset, length);
            QVERIFY(end >= offset);
            QVERIFY(end < length);
            QVERIFY(end - offset + 1 <= 1024 * 1024);
            offset = end + 1;
            ++requests;
        }
        QCOMPARE(offset, length);
        QCOMPARE(requests, 4);
        QCOMPARE(audioRangeEnd(0, 42), 41);
    }

    void liveBrowserPlayerRunsInQuickJs()
    {
        if (!qEnvironmentVariableIsSet("ORCHARD_TEST_LIVE_YOUTUBE"))
            QSKIP("Set ORCHARD_TEST_LIVE_YOUTUBE to check current player extraction over the network.");
        ProviderRuntime runtime;
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        QSignalSpy failure(&runtime, &ProviderRuntime::invocationFailed);
        runtime.invoke(QStringLiteral("playback.resolve"), QJsonObject{
            {"track", QJsonObject{{"id", "dQw4w9WgXcQ"}, {"type", "song"}}},
            {"refreshStream", qEnvironmentVariableIsSet("ORCHARD_TEST_COLD_PLAYER")},
            // Synthetic credentials exercise the runtime without reading account secrets.
            {"session", QJsonObject{{"cookie", "SAPISID=runtime-test"}}}
        });
        QTRY_VERIFY_WITH_TIMEOUT(!success.isEmpty() || !failure.isEmpty(), 60000);
        if (!success.isEmpty()) qInfo() << "Resolver timings (ms):" << success.first().at(1).value<QJsonValue>().toObject().value("timings");
        if (!failure.isEmpty()) {
            const QString message = failure.first().at(1).toString();
            qInfo() << "Live browser resolver:" << message;
            QVERIFY2(!message.contains("ReferenceError") && !message.contains("TypeError") &&
                     !message.contains("out of memory") && !message.contains("extract") &&
                     !message.contains("signature execution") && !message.contains("stack overflow"), qPrintable(message));
            // Synthetic cookies cannot prove authenticated playback, but the
            // current script must load before the player can reject the request.
            QVERIFY2(message.contains("Sign in", Qt::CaseInsensitive) ||
                     message.contains("YouTube") || message.contains("unavailable"), qPrintable(message));
        }
    }

    void dispatchesPromiseResults()
    {
        ProviderRuntime runtime;
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        const quint64 requestId = runtime.invoke(QStringLiteral("runtime.ping"), QJsonObject{});
        QVERIFY(success.wait(3000));
        QCOMPARE(success.first().at(0).toULongLong(), requestId);
        QCOMPARE(success.first().at(1).value<QJsonValue>().toObject().value(QStringLiteral("ready")).toBool(), true);
    }

    void bridgesFetchThroughQtNetwork()
    {
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost, 0));
        connect(&server, &QTcpServer::newConnection, &server, [&server] {
            QTcpSocket *socket = server.nextPendingConnection();
            QObject::connect(socket, &QTcpSocket::readyRead, socket, [socket] {
                socket->readAll();
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nX-Orchard-Test: yes\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello");
                socket->disconnectFromHost();
            });
        });

        ProviderRuntime runtime;
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        const QJsonObject payload{{QStringLiteral("url"),
                                  QStringLiteral("http://127.0.0.1:%1/profile").arg(server.serverPort())}};
        runtime.invoke(QStringLiteral("runtime.fetch"), payload);
        QVERIFY(success.wait(3000));
        const QJsonObject result = success.first().at(1).value<QJsonValue>().toObject();
        QCOMPARE(result.value(QStringLiteral("status")).toInt(), 200);
        QCOMPARE(result.value(QStringLiteral("body")).toString(), QStringLiteral("hello"));
        QCOMPARE(result.value(QStringLiteral("headers")).toObject().value(QStringLiteral("x-orchard-test")).toString(), QStringLiteral("yes"));
    }

    void fetchKeepsProviderCookiesAfterServerSetsCookies_data()
    {
        QTest::addColumn<QString>("cookie");
        QTest::addColumn<QString>("authorization");
        QTest::newRow("saved session") << QStringLiteral("SAPISID=saved; SID=session")
                                       << QStringLiteral("SAPISIDHASH saved-signature");
        QTest::newRow("different account") << QStringLiteral("SAPISID=other; SID=other-session")
                                          << QStringLiteral("SAPISIDHASH other-signature");
        QTest::newRow("guest") << QString() << QString();
    }

    void fetchKeepsProviderCookiesAfterServerSetsCookies()
    {
        QFETCH(QString, cookie);
        QFETCH(QString, authorization);
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost, 0));
        connect(&server, &QTcpServer::newConnection, &server, [&server] {
            auto *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            connect(socket, &QTcpSocket::readyRead, socket, [socket, request = QByteArray{}]() mutable {
                request += socket->readAll();
                if (!request.contains("\r\n\r\n")) return;
                QJsonObject received;
                for (const QByteArray &line : request.split('\n')) {
                    const int colon = line.indexOf(':');
                    if (colon > 0)
                        received.insert(QString::fromLatin1(line.left(colon).toLower()),
                                        QString::fromLatin1(line.mid(colon + 1).trimmed()));
                }
                const QByteArray body = QJsonDocument(received).toJson(QJsonDocument::Compact);
                // A guest response must not put the next request on a cookie diet.
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                              "Set-Cookie: VISITOR_INFO1_LIVE=guest; Path=/\r\n"
                              "Set-Cookie: SAPISID=server-value; Path=/\r\n"
                              "Connection: close\r\nContent-Length: " + QByteArray::number(body.size())
                              + "\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
        });

        ProviderRuntime runtime;
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        QSignalSpy failure(&runtime, &ProviderRuntime::invocationFailed);
        const QString url = QStringLiteral("http://127.0.0.1:%1/browse").arg(server.serverPort());
        const QJsonObject savedHeaders{{QStringLiteral("Cookie"), QStringLiteral("SAPISID=saved; SID=session")},
                                       {QStringLiteral("Authorization"), QStringLiteral("SAPISIDHASH saved-signature")}};
        QJsonObject nextHeaders;
        if (!cookie.isEmpty()) nextHeaders.insert(QStringLiteral("Cookie"), cookie);
        if (!authorization.isEmpty()) nextHeaders.insert(QStringLiteral("Authorization"), authorization);

        // Reuse the runtime, just as playback and playlist browsing do all evening.
        for (const QJsonObject &headers : {savedHeaders, nextHeaders}) {
            const quint64 requestId = runtime.invoke(QStringLiteral("runtime.fetch"), QJsonObject{
                {QStringLiteral("url"), url},
                {QStringLiteral("init"), QJsonObject{{QStringLiteral("headers"), headers}}}
            });
            QTRY_VERIFY_WITH_TIMEOUT(!success.isEmpty() || !failure.isEmpty(), 3000);
            QVERIFY2(failure.isEmpty(), failure.isEmpty() ? "" : qPrintable(failure.first().at(1).toString()));
            const auto result = success.takeFirst();
            QCOMPARE(result.at(0).toULongLong(), requestId);
            const QJsonObject response = result.at(1).value<QJsonValue>().toObject();
            QCOMPARE(response.value(QStringLiteral("status")).toInt(), 200);
            const QJsonObject received = QJsonDocument::fromJson(response.value(QStringLiteral("body")).toString().toUtf8()).object();
            QCOMPARE(received.value(QStringLiteral("cookie")).toString(), headers.value(QStringLiteral("Cookie")).toString());
            QCOMPARE(received.value(QStringLiteral("authorization")).toString(), headers.value(QStringLiteral("Authorization")).toString());
        }
    }

    void reportsUnknownMethods()
    {
        ProviderRuntime runtime;
        QSignalSpy failure(&runtime, &ProviderRuntime::invocationFailed);
        runtime.invoke(QStringLiteral("missing.method"), QJsonObject{});
        QVERIFY(failure.wait(3000));
        QVERIFY(failure.first().at(1).toString().contains(QStringLiteral("Unknown YouTube provider method")));
    }

    void mediaCryptoAndBinaryResultsCrossTheHost()
    {
        ProviderRuntime runtime({"provider-media-test.qjc", "OrchardMediaTest", "Test"});
        QSignalSpy bytes(&runtime, &ProviderRuntime::invocationBytes);
        QSignalSpy failure(&runtime, &ProviderRuntime::invocationFailed);
        auto call = [&](const QString &method, const QJsonObject &payload) {
            bytes.clear();
            runtime.invoke(method, payload);
            [&] { QTRY_VERIFY_WITH_TIMEOUT(!bytes.isEmpty() || !failure.isEmpty(), 3000); }();
            if (!failure.isEmpty())
                qWarning().noquote() << failure.first().at(1).toString();
            return bytes.isEmpty() ? QByteArray() : bytes.first().at(1).toByteArray();
        };
        // RFC 5869 test case 1.
        QCOMPARE(call("hkdf", {{"key", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"},
                               {"salt", "000102030405060708090a0b0c"},
                               {"info", "f0f1f2f3f4f5f6f7f8f9"}, {"length", 42}}).toHex(),
                 QByteArray("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
        // NIST SP 800-38A F.5.1, CTR-AES128.
        QCOMPARE(call("aes", {{"mode", 1}, {"key", "2b7e151628aed2a6abf7158809cf4f3c"},
                              {"iv", "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"},
                              {"data", "6bc1bee22e409f96e93d7e117393172a"}}).toHex(),
                 QByteArray("874d6191b620e3261bef6864990db6ce"));
        QVERIFY(failure.isEmpty());
        runtime.invoke("aes", QJsonObject{{"mode", 0}, {"key", "00"}, {"iv", "00"}, {"data", "00"}});
        QVERIFY(failure.wait(3000));
        QVERIFY(failure.first().at(1).toString().contains("16-byte key"));
    }

    void binaryFetchBodiesStayBinary()
    {
        QByteArray body(256, Qt::Uninitialized);
        for (int i = 0; i < body.size(); ++i) body[i] = char(i);
        QTcpServer server;
        QVERIFY(server.listen(QHostAddress::LocalHost, 0));
        connect(&server, &QTcpServer::newConnection, &server, [&server, body] {
            QTcpSocket *socket = server.nextPendingConnection();
            QObject::connect(socket, &QTcpSocket::readyRead, socket, [socket, body] {
                socket->readAll();
                socket->write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 256\r\n"
                              "Connection: close\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
        });
        ProviderRuntime runtime({"provider-media-test.qjc", "OrchardMediaTest", "Test"});
        QSignalSpy bytes(&runtime, &ProviderRuntime::invocationBytes);
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        runtime.invoke("fetchBytes", QJsonObject{
            {"url", QStringLiteral("http://127.0.0.1:%1/segment").arg(server.serverPort())}, {"offset", 16}});
        QVERIFY(bytes.wait(3000));
        QCOMPARE(bytes.first().at(1).toByteArray(), body.mid(16));
        // Plain objects keep the JSON path, even with a field named "bytes".
        runtime.invoke("json", QJsonObject{});
        QVERIFY(success.wait(3000));
        QCOMPARE(success.first().at(1).value<QJsonValue>().toObject().value("bytes").toArray().size(), 2);
    }

    void qobuzBytecodeBootsInQuickJs()
    {
        ProviderRuntime runtime(orchard::provider::qobuzBundle());
        QSignalSpy success(&runtime, &ProviderRuntime::invocationSucceeded);
        QSignalSpy failure(&runtime, &ProviderRuntime::invocationFailed);
        runtime.invoke("session.set", QJsonObject{{"token", "token"}, {"userId", 42}});
        QVERIFY(success.wait(3000));
        QCOMPARE(success.first().at(1).value<QJsonValue>().toObject().value("connected").toBool(), true);
        runtime.invoke("playback.read", QJsonObject{{"playbackId", "missing"}, {"start", 0}});
        QVERIFY(failure.wait(3000));
        QVERIFY(failure.first().at(1).toString().contains("expired"));
        runtime.invoke("missing.method", QJsonObject{});
        QTRY_COMPARE_WITH_TIMEOUT(failure.size(), 2, 3000);
        QVERIFY(failure.last().at(1).toString().contains("Unknown Qobuz provider method"));
    }

    void catalogRoutesItsOwnFailures()
    {
        YouTubeProvider provider;
        YouTubeCatalog catalog(&provider);
        QSignalSpy failure(&catalog, &YouTubeCatalog::requestFailed);
        const quint64 requestId = catalog.fetchHome(QJsonObject{});
        QVERIFY(failure.wait(3000));
        QCOMPARE(failure.first().at(0).toULongLong(), requestId);
        QVERIFY(failure.first().at(1).toString().contains(QStringLiteral("Authenticated YouTube cookies")));
    }
};

QTEST_GUILESS_MAIN(ProviderRuntimeTest)

#include "provider_runtime_test.moc"
