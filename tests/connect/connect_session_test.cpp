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

// Two real Connect nodes in one process, over real LAN sockets and WebRTC.

#include "connect_harness.h"

#include <rtc/rtc.hpp>

#include <QtTest>

#include <chrono>
#include <future>
#include <thread>

using namespace connect_test;
using orchard::connect::NodeConfig;

namespace {

auto eventNamed(const std::string &name) {
  return [name](const Json &e) { return e.value("event", "") == name; };
}

// Waits until `device` sees `peer` online through the hub.
bool seesPeer(TestDevice &device, const std::string &peer) {
  return !device
              .waitEvent([&](const Json &e) {
                if (e.value("event", "") != "devices")
                  return false;
                for (const Json &d : e["devices"])
                  if (d.value("id", "") == peer)
                    return true;
                return false;
              })
              .empty();
}

Json connectAndWait(TestDevice &controller, TestDevice &target) {
  if (!seesPeer(controller, target.id()))
    return Json();
  const auto from = controller.mark();
  controller.node().connectTo(target.id());
  return controller.waitEvent([](const Json &e) { return isSession(e, "controller", "CONNECTED"); }, 15000, from);
}

double positionOf(const Json &snapshot) { return snapshot.is_object() ? snapshot.value("position", 0.0) : 0.0; }

// Idle snapshots carry a null track, and json::value() throws on null.
std::string trackId(const Json &snapshot) {
  if (!snapshot.is_object() || !snapshot.contains("track") || !snapshot["track"].is_object())
    return {};
  return snapshot["track"].value("id", "");
}

} // namespace

class ConnectSessionTest : public QObject {
  Q_OBJECT

private slots:
  void phoneTakesOverIdleDesktop() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    phone.setPlayback(playing(track("A", "Track A"), 94.0, Json::array({track("A2", "Next")})));
    const Json connected = connectAndWait(phone, pc);
    QVERIFY2(!connected.empty(), "phone never connected");
    QCOMPARE(QString::fromStdString(connected.value("transport", "")), QStringLiteral("lan"));

    const Json transfer = pc.waitEvent(eventNamed("initial_playback"));
    QCOMPARE(QString::fromStdString(transfer.value("outcome", "")), QStringLiteral("transferred"));
    QCOMPARE(QString::fromStdString(trackId(transfer["snapshot"])), QStringLiteral("A"));
    const double position = positionOf(transfer["snapshot"]);
    QVERIFY2(position >= 94.0 && position < 96.0, "transfer should start near 1:34");
    QCOMPARE(transfer["snapshot"]["queue"].size(), std::size_t(1));

    // The phone stops its own audio and mirrors the desktop playing Track A.
    const Json mirror = phone.waitEvent([](const Json &e) {
      return e.value("event", "") == "remote_state" && trackId(e["snapshot"]) == "A" &&
             e["snapshot"].value("playing", false);
    });
    QVERIFY(!mirror.empty());
    QCOMPARE(phone.playback().value("playing", true), false);
  }

  void phoneFollowsPlayingDesktop() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    pc.setPlayback(playing(track("B", "Track B"), 30.0));
    phone.setPlayback(playing(track("A", "Track A"), 94.0));
    QVERIFY(!connectAndWait(phone, pc).empty());
    const Json outcome = phone.waitEvent(eventNamed("initial_playback"));
    QCOMPARE(QString::fromStdString(outcome.value("outcome", "")), QStringLiteral("target_wins"));
    // Track B continues uninterrupted; nothing from the phone reached the desktop player.
    QTest::qWait(300);
    QCOMPARE(QString::fromStdString(trackId(pc.playback())), QStringLiteral("B"));
    QCOMPARE(pc.playback().value("playing", false), true);
    QCOMPARE(pc.countEvents(eventNamed("command")), 0);
    QVERIFY(!phone.waitEvent([](const Json &e) {
                    return e.value("event", "") == "remote_state" && trackId(e["snapshot"]) == "B";
                  }).empty());
  }

  void idleOnBothSidesChangesNothing() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    QVERIFY(!connectAndWait(pc, phone).empty());
    const Json outcome = pc.waitEvent(eventNamed("initial_playback"));
    QCOMPARE(QString::fromStdString(outcome.value("outcome", "")), QStringLiteral("none"));
    QVERIFY(!phone.playback().contains("track"));
  }

  void desktopControlsPhoneAndKeepsTheWork() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    phone.setPlayback(playing(track("C", "Track C"), 10.0, Json::array({track("D", "Track D")})));
    QVERIFY(!connectAndWait(pc, phone).empty());
    // The phone stays the target but hands mixing and artwork to the desktop.
    const Json roles = phone.waitEvent(
        [](const Json &e) { return e.value("event", "") == "roles" && e["roles"].value("mix_host", "") == "pc"; });
    QVERIFY(!roles.empty());
    QCOMPARE(QString::fromStdString(roles["roles"].value("target", "")), QStringLiteral("phone"));
    QCOMPARE(QString::fromStdString(roles["roles"].value("artwork_host", "")), QStringLiteral("pc"));
    QVERIFY(!pc.waitEvent([](const Json &e) {
                 return e.value("event", "") == "roles" && e["roles"].value("mix_host", "") == "pc" &&
                        e["roles"].value("target", "") == "phone";
               }).empty());

    // Every transport command, applied by the target, mirrored back.
    auto send = [&](Json command, const std::function<bool(const Json &)> &check) {
      const auto from = pc.mark();
      pc.node().command(command.dump());
      const Json result = pc.waitEvent(eventNamed("command_result"), 5000, from);
      if (!result.value("ok", false))
        return false;
      return !pc.waitEvent([&](const Json &e) { return e.value("event", "") == "remote_state" && check(e["snapshot"]); },
                           5000, from)
                  .empty();
    };
    QVERIFY(send({{"action", "pause"}}, [](const Json &s) { return !s.value("playing", true); }));
    QVERIFY(send({{"action", "play"}}, [](const Json &s) { return s.value("playing", false); }));
    QVERIFY(send({{"action", "seek"}, {"args", {{"position", 122.5}}}},
                 [](const Json &s) { return s.value("position", 0.0) >= 122.5; }));
    QVERIFY(send({{"action", "set_volume"}, {"args", {{"volume", 0.3}}}},
                 [](const Json &s) { return std::abs(s.value("volume", 1.0) - 0.3) < 0.01; }));
    QVERIFY(send({{"action", "set_shuffle"}, {"args", {{"enabled", true}}}},
                 [](const Json &s) { return s.value("shuffle", false); }));
    QVERIFY(send({{"action", "set_repeat"}, {"args", {{"mode", "all"}}}},
                 [](const Json &s) { return s.value("repeat", "") == "all"; }));
    QVERIFY(send({{"action", "enqueue"}, {"args", {{"track", track("E", "Track E")}}}},
                 [](const Json &s) { return s["queue"].size() == 2; }));
    QVERIFY(send({{"action", "remove_queue_item"}, {"args", {{"index", 1}}}},
                 [](const Json &s) { return s["queue"].size() == 1; }));
    QVERIFY(send({{"action", "next"}}, [](const Json &s) { return trackId(s) == "D"; }));
    QVERIFY(send({{"action", "play_track"}, {"args", {{"track", track("F", "Track F")}}}},
                 [](const Json &s) { return trackId(s) == "F"; }));

    // Garbage never reaches the player.
    const auto from = pc.mark();
    pc.node().command(Json{{"action", "seek"}, {"args", {{"position", "soon"}}}}.dump());
    const Json rejected = pc.waitEvent(eventNamed("command_result"), 5000, from);
    QCOMPARE(rejected.value("ok", true), false);
  }

  void providerRequestsRunWhereTheAccountIs() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    phone.setDevice([](Json &d) { d["provider_sessions"]["qobuz"] = true; });
    QVERIFY(!connectAndWait(phone, pc).empty());
    QVERIFY(!pc.waitEvent([](const Json &e) {
                 return e.value("event", "") == "roles" && e["roles"]["provider_hosts"].value("qobuz", "") == "phone";
               }).empty());
    const auto from = pc.mark();
    const std::string id =
        pc.node().request(Json{{"method", "ResolveTrack"}, {"host", "provider:qobuz"}, {"params", {{"id", "12345"}}}}
                              .dump());
    const Json result = pc.waitEvent(
        [&](const Json &e) { return e.value("event", "") == "rpc_result" && e.value("id", "") == id; }, 5000, from);
    QCOMPARE(result.value("ok", false), true);
    QCOMPARE(QString::fromStdString(result["result"].value("served_by", "")), QStringLiteral("phone"));
    // Nobody is signed in to this one, so the request fails with provider_unavailable.
    const std::string missing = pc.node().request(Json{{"method", "Search"}, {"host", "provider:tidal"}}.dump());
    const Json unavailable = pc.waitEvent(
        [&](const Json &e) { return e.value("event", "") == "rpc_result" && e.value("id", "") == missing; });
    QCOMPARE(QString::fromStdString(unavailable.value("error", "")), QStringLiteral("provider_unavailable"));
  }

  void audioChunksArriveIntact() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    QVERIFY(!connectAndWait(pc, phone).empty());
    const Json session = pc.waitEvent([](const Json &e) { return isSession(e, "controller", "CONNECTED"); });
    std::string pcm(300 * 1024, '\0');
    for (std::size_t i = 0; i < pcm.size(); ++i)
      pcm[i] = static_cast<char>(i * 31);
    const Json header = {{"stream", "mix-1"}, {"sequence", 0},   {"timestamp", 12.5},
                         {"codec", "pcm_s16"}, {"sample_rate", 48000}, {"channels", 2}};
    QVERIFY(pc.node().sendData(session.value("session_id", ""), header.dump(), pcm));
    QTRY_COMPARE_WITH_TIMEOUT(phone.received().size(), std::size_t(1), 5000);
    QCOMPARE(phone.received()[0].first, header);
    QVERIFY(phone.received()[0].second == pcm);
  }

  void mixHostTradesAudioWithThePhone() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    phone.setPlayback(playing(track("t1", "One"), 50.0));
    QVERIFY(!connectAndWait(pc, phone).empty());
    const Json session = phone.waitEvent([](const Json &e) { return isSession(e, "target", "CONNECTED"); });
    const std::string phoneSid = session.value("session_id", "");
    // Phone to mix host: the provider's encoded bytes, big enough to need pacing.
    std::string source(3 * 1024 * 1024 + 17, '\0');
    for (std::size_t i = 0; i < source.size(); ++i)
      source[i] = static_cast<char>(i * 13);
    const auto phoneFrom = phone.mark();
    const std::string up = phone.node().sendStream(
        phoneSid, Json{{"codec", "source"}, {"track", "t1"}, {"meta", {{"mix", "m1"}, {"role", "outgoing"}}}}.dump(),
        source);
    QVERIFY(!up.empty());
    QVERIFY(!phone.waitEvent([&](const Json &e) { return e.value("event", "") == "stream_sent" && e.value("stream", "") == up; },
                             10000, phoneFrom)
                 .empty());
    QTRY_COMPARE_WITH_TIMEOUT(pc.received().size(), std::size_t(1), 10000);
    const auto [upHeader, upBytes] = pc.received()[0];
    QVERIFY(upBytes == source);
    QCOMPARE(QString::fromStdString(upHeader.value("kind", "")), QStringLiteral("audio"));
    QCOMPARE(QString::fromStdString(upHeader["meta"].value("role", "")), QStringLiteral("outgoing"));
    // Mix host to phone: the render, stamped with the phone's own media time.
    const Json pcSession = pc.waitEvent([](const Json &e) { return isSession(e, "controller", "CONNECTED"); });
    std::string render(48000 * 4 * 3, '\0');
    const std::string down = pc.node().sendStream(
        pcSession.value("session_id", ""),
        Json{{"codec", "pcm_s16"}, {"sample_rate", 48000}, {"channels", 2}, {"timestamp", 171.25}}.dump(), render);
    QVERIFY(!down.empty());
    QTRY_COMPARE_WITH_TIMEOUT(phone.received().size(), std::size_t(1), 10000);
    QCOMPARE(phone.received()[0].first.value("timestamp", 0.0), 171.25);
    QCOMPARE(phone.received()[0].second.size(), render.size());
    // Bad metadata is refused up front.
    QVERIFY(pc.node().sendStream(pcSession.value("session_id", ""), R"({"codec":"mp3"})", "x").empty());
    // A long job names its own deadline.
    pc.answerRpcs = false;
    const auto from = phone.mark();
    const auto started = std::chrono::steady_clock::now();
    const std::string id = phone.node().request(
        Json{{"method", "MixPrepare"}, {"host", "mix"}, {"timeout_ms", 1000}}.dump());
    const Json timedOut = phone.waitEvent(
        [&](const Json &e) { return e.value("event", "") == "rpc_result" && e.value("id", "") == id; }, 5000, from);
    QCOMPARE(QString::fromStdString(timedOut.value("error", "")), QStringLiteral("timeout"));
    QVERIFY(std::chrono::steady_clock::now() - started < std::chrono::seconds(3));
  }

  void controllerLeavingKeepsTheMusicPlaying() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    pc.setPlayback(playing(track("B", "Track B"), 30.0));
    QVERIFY(!connectAndWait(phone, pc).empty());
    phone.node().disconnect();
    const Json ended = pc.waitEvent(eventNamed("session_ended"));
    QCOMPARE(QString::fromStdString(ended.value("reason", "")), QStringLiteral("user_disconnected"));
    QCOMPARE(pc.playback().value("playing", false), true);
  }

  void targetQuittingReturnsControllerToLocal() {
    FakeHub hub;
    auto pc = std::make_unique<TestDevice>(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    QVERIFY(!connectAndWait(phone, *pc).empty());
    pc.reset();
    const Json ended = phone.waitEvent(eventNamed("session_ended"));
    QCOMPARE(QString::fromStdString(ended.value("role", "")), QStringLiteral("controller"));
    QCOMPARE(QString::fromStdString(ended.value("reason", "")), QStringLiteral("shutdown"));
  }

  void droppedLinkReconnectsWithoutReplayingInitialSync() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    phone.setPlayback(playing(track("C", "Track C"), 10.0));
    QVERIFY(!connectAndWait(pc, phone).empty());
    QVERIFY(!phone.waitEvent([](const Json &e) {
                   return e.value("event", "") == "roles" && e["roles"].value("mix_host", "") == "pc";
                 }).empty());
    const auto from = phone.mark();
    pc.node().dropLinks();
    // The phone keeps playing and immediately falls back to mixing for itself.
    QVERIFY(!phone.waitEvent([](const Json &e) {
                   return e.value("event", "") == "roles" && e["roles"].value("mix_host", "") == "phone";
                 }, 5000, from).empty());
    QCOMPARE(phone.playback().value("playing", false), true);
    // The desktop comes back, regains its roles, and nothing re-resolves.
    QVERIFY(!pc.waitEvent([](const Json &e) { return isSession(e, "controller", "RECONNECTING"); }).empty());
    QVERIFY(!phone.waitEvent([](const Json &e) {
                   return e.value("event", "") == "roles" && e["roles"].value("mix_host", "") == "pc";
                 }, 15000, from).empty());
    QCOMPARE(pc.countEvents(eventNamed("initial_playback")), 1);
    QCOMPARE(phone.countEvents(eventNamed("initial_playback")), 1);
    QCOMPARE(phone.playback().value("playing", false), true);
  }

  void webRtcWhenTheLanIsUnreachable() {
    FakeHub hub;
    NodeConfig noLan;
    noLan.enableLan = false;
    TestDevice pc(hub, "pc", "linux", noLan);
    TestDevice phone(hub, "phone", "android", noLan);
    pc.setPlayback(playing(track("W", "Over WebRTC"), 5.0));
    const Json connected = connectAndWait(phone, pc);
    QVERIFY2(!connected.empty(), "no WebRTC session");
    QCOMPARE(QString::fromStdString(connected.value("transport", "")), QStringLiteral("webrtc"));
    const auto from = phone.mark();
    phone.node().command(Json{{"action", "pause"}}.dump());
    QVERIFY(!phone.waitEvent([](const Json &e) {
                   return e.value("event", "") == "remote_state" && !e["snapshot"].value("playing", true);
                 }, 5000, from).empty());
  }

  void busyDeviceDeclines() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    TestDevice phone(hub, "phone", "android");
    TestDevice tablet(hub, "tablet", "android");
    QVERIFY(!connectAndWait(phone, pc).empty());
    QVERIFY(seesPeer(tablet, "phone"));
    tablet.node().connectTo("phone");
    const Json error = tablet.waitEvent(eventNamed("error"));
    QCOMPARE(QString::fromStdString(error.value("code", "")), QStringLiteral("busy"));
  }

  void oldAndForeignClientsAreRejected() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    // The desktop's LAN port, as presence advertises it to phones.
    int port = 0;
    QTRY_VERIFY_WITH_TIMEOUT((port = hub.lanPortOf("pc")) > 0, 5000);

    auto exchange = [&](const Json &hello) {
      rtc::WebSocket socket;
      std::promise<std::string> reply;
      auto future = reply.get_future();
      socket.onOpen([&] { socket.send(hello.dump()); });
      socket.onMessage([&](rtc::message_variant data) {
        if (auto *text = std::get_if<std::string>(&data))
          reply.set_value(*text);
      });
      socket.open("ws://127.0.0.1:" + std::to_string(port) + "/orchard-connect");
      if (future.wait_for(std::chrono::seconds(5)) != std::future_status::ready)
        return std::string("timeout");
      socket.resetCallbacks();
      socket.close();
      return Json::parse(future.get()).value("code", "");
    };
    QCOMPARE(QString::fromStdString(exchange({{"type", "hello"}, {"protocolVersion", 4}})),
             QStringLiteral("incompatible_client"));
    QCOMPARE(QString::fromStdString(exchange({{"type", "hello"}, {"connect_protocol_major", 1}})),
             QStringLiteral("incompatible_protocol"));
    // Right version, no grant: a LAN stranger gets nothing.
    QCOMPARE(QString::fromStdString(exchange({{"type", "hello"},
                                              {"connect_protocol_major", 2},
                                              {"connect_protocol_minor", 0},
                                              {"session_id", "guess"},
                                              {"device_id", "phone"},
                                              {"target_id", "pc"},
                                              {"nonce", "AAAA"}})),
             QStringLiteral("unauthorized"));
  }

  void hubRejectsOldPresence() {
    FakeHub hub;
    TestDevice pc(hub, "pc", "linux");
    // Presence without the major is how an old client would look to the hub.
    const auto from = pc.mark();
    hub.receive(&pc, Json{{"type", "hello"}, {"device", {{"id", "pc"}}}}.dump());
    QVERIFY(!pc.waitEvent([](const Json &e) {
                 return e.value("event", "") == "error" && e.value("code", "") == "incompatible_client";
               }, 5000, from).empty());
  }

};

QTEST_GUILESS_MAIN(ConnectSessionTest)
#include "connect_session_test.moc"
