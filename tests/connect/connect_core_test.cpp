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

// Pure Connect rules: versioning, roles, initial playback, commands, sealing, AudioChunk framing.

#include "connect/device.h"
#include "connect/playback.h"
#include "connect/protocol.h"
#include "connect/secure_channel.h"
#include "connect/stream.h"

#include <QtTest>

using namespace orchard::connect;

namespace {

DeviceInfo device(const std::string &id, const std::string &platform, bool qobuz = false) {
  DeviceInfo info;
  info.id = id;
  info.platform = platform;
  info.kind = platform == "android" ? "mobile" : "desktop";
  info.canMixAudio = info.kind == "desktop";
  info.canFetchArtwork = info.kind == "desktop";
  info.providers = {"youtube", "qobuz"};
  info.providerSessions = {{"youtube", true}, {"qobuz", qobuz}};
  return info;
}

PlaybackSnapshot snapshot(bool hasTrack, bool isPlaying) {
  PlaybackSnapshot s;
  if (hasTrack)
    s.track = sanitizeTrack({{"id", "abc"}, {"title", "Song"}});
  s.playing = isPlaying;
  return s;
}

} // namespace

class ConnectCoreTest : public QObject {
  Q_OBJECT

private slots:
  void versionGate() {
    QCOMPARE(QString::fromStdString(checkProtocol(Json::object())), QStringLiteral("incompatible_client"));
    // A v1 client (Socket.IO era) sent protocolVersion, never connect_protocol_major.
    QCOMPARE(QString::fromStdString(checkProtocol({{"protocolVersion", 4}})), QStringLiteral("incompatible_client"));
    QCOMPARE(QString::fromStdString(checkProtocol({{"connect_protocol_major", 1}})),
             QStringLiteral("incompatible_protocol"));
    QCOMPARE(QString::fromStdString(checkProtocol({{"connect_protocol_major", 3}, {"connect_protocol_minor", 0}})),
             QStringLiteral("incompatible_protocol"));
    QCOMPARE(QString::fromStdString(checkProtocol({{"connect_protocol_major", "2"}})),
             QStringLiteral("incompatible_client"));
    Json hello = Json::object();
    stampProtocol(hello);
    QVERIFY(checkProtocol(hello).empty());
    // Minor versions are additive and never block a connection.
    hello["connect_protocol_minor"] = 7;
    QVERIFY(checkProtocol(hello).empty());
  }

  void desktopTakesExpensiveWork() {
    const DeviceInfo phone = device("phone", "android");
    const DeviceInfo pc = device("pc", "linux");
    const Roles phoneTarget = selectRoles(phone, {pc});
    QCOMPARE(QString::fromStdString(phoneTarget.target), QStringLiteral("phone"));
    QCOMPARE(QString::fromStdString(phoneTarget.mixHost), QStringLiteral("pc"));
    QCOMPARE(QString::fromStdString(phoneTarget.artworkHost), QStringLiteral("pc"));
    const Roles pcTarget = selectRoles(pc, {phone});
    QCOMPARE(QString::fromStdString(pcTarget.target), QStringLiteral("pc"));
    QCOMPARE(QString::fromStdString(pcTarget.mixHost), QStringLiteral("pc"));
    // Alone, the phone does its own work.
    const Roles alone = selectRoles(phone, {});
    QCOMPARE(QString::fromStdString(alone.mixHost), QStringLiteral("phone"));
    QCOMPARE(QString::fromStdString(alone.artworkHost), QStringLiteral("phone"));
  }

  void providerHostFollowsTheSession() {
    const DeviceInfo phone = device("phone", "android", true);
    const DeviceInfo pc = device("pc", "linux", false);
    const Roles pcTarget = selectRoles(pc, {phone});
    QCOMPARE(QString::fromStdString(pcTarget.providerHosts.at("qobuz")), QStringLiteral("phone"));
    QCOMPARE(QString::fromStdString(pcTarget.providerHosts.at("youtube")), QStringLiteral("pc"));
    // When both are signed in, the target uses its own account.
    const Roles both = selectRoles(device("pc", "linux", true), {phone});
    QCOMPARE(QString::fromStdString(both.providerHosts.at("qobuz")), QStringLiteral("pc"));
    // Nobody signed in: the provider reports unavailable.
    const Roles none = selectRoles(device("pc", "linux"), {device("phone", "android")});
    QVERIFY(!none.providerHosts.count("qobuz"));
  }

  void initialPlaybackRules() {
    QCOMPARE(resolveInitialPlayback(snapshot(true, true), snapshot(true, true)), InitialOutcome::TargetWins);
    QCOMPARE(resolveInitialPlayback(snapshot(true, true), snapshot(false, false)), InitialOutcome::TargetWins);
    QCOMPARE(resolveInitialPlayback(snapshot(false, false), snapshot(true, true)), InitialOutcome::Transfer);
    // A paused, restored track on the target is idle; the playing phone moves over.
    QCOMPARE(resolveInitialPlayback(snapshot(true, false), snapshot(true, true)), InitialOutcome::Transfer);
    QCOMPARE(resolveInitialPlayback(snapshot(false, false), snapshot(false, false)), InitialOutcome::Nothing);
    QCOMPARE(resolveInitialPlayback(snapshot(true, false), snapshot(true, false)), InitialOutcome::Nothing);
  }

  void snapshotsSurviveTheWire() {
    PlaybackSnapshot original = snapshot(true, true);
    original.position = 94.5;
    original.queue = Json::array({sanitizeTrack({{"id", "next"}}), Json("junk"), sanitizeTrack({{"id", "after"}})});
    original.repeat = "one";
    original.volume = 0.4;
    const PlaybackSnapshot parsed = snapshotFromJson(toJson(original));
    QVERIFY(parsed.active());
    QCOMPARE(parsed.position, 94.5);
    QCOMPARE(parsed.queue.size(), std::size_t(2));
    QCOMPARE(QString::fromStdString(parsed.repeat), QStringLiteral("one"));
    QCOMPARE(parsed.volume, 0.4);
    // Hostile values are clamped, not trusted.
    const PlaybackSnapshot hostile =
        snapshotFromJson({{"track", {{"id", "x"}}}, {"volume", 7}, {"position", -5}, {"repeat", "forever"}});
    QCOMPARE(hostile.volume, 1.0);
    QCOMPARE(hostile.position, 0.0);
    QCOMPARE(QString::fromStdString(hostile.repeat), QStringLiteral("off"));
  }

  void projectionAndChangeDetection() {
    PlaybackSnapshot a = snapshot(true, true);
    a.position = 10.0;
    QCOMPARE(projectPosition(a, 2500), 12.5);
    PlaybackSnapshot b = a;
    b.position = 12.6;
    QVERIFY(!significantChange(a, b, 2500));
    b.position = 40.0;
    QVERIFY(significantChange(a, b, 2500));
    PlaybackSnapshot paused = a;
    paused.playing = false;
    QVERIFY(significantChange(a, paused, 0));
  }

  void commandsAreValidated() {
    Json seek = {{"action", "seek"}, {"args", {{"position", 122.5}}}};
    QVERIFY(normalizeCommand(seek).empty());
    QCOMPARE(seek["args"]["position"].get<double>(), 122.5);
    Json volume = {{"action", "set_volume"}, {"args", {{"volume", 3}}}};
    QVERIFY(normalizeCommand(volume).empty());
    QCOMPARE(volume["args"]["volume"].get<double>(), 1.0);
    Json bad = {{"action", "format_disk"}};
    QCOMPARE(QString::fromStdString(normalizeCommand(bad)), QStringLiteral("invalid_command"));
    Json noTrack = {{"action", "play_track"}, {"args", {{"track", {{"title", "no id"}}}}}};
    QVERIFY(!normalizeCommand(noTrack).empty());
    Json repeat = {{"action", "set_repeat"}, {"args", {{"mode", "sideways"}}}};
    QVERIFY(!normalizeCommand(repeat).empty());
    Json move = {{"action", "move_queue_item"}, {"args", {{"from", 3}, {"to", 0}}}};
    QVERIFY(normalizeCommand(move).empty());
    Json negative = {{"action", "remove_queue_item"}, {"args", {{"index", -1}}}};
    QVERIFY(!normalizeCommand(negative).empty());
  }

  void lanAddressRanking() {
    const auto ranked = rankLanAddresses({{"docker0", "172.17.0.1"},
                                          {"vEthernet (WSL)", "192.168.80.1"},
                                          {"lo", "127.0.0.1"},
                                          {"wlan0", "192.168.1.20"},
                                          {"eth1", "169.254.3.3"},
                                          {"eth0", "10.0.0.5"}});
    QCOMPARE(ranked.size(), std::size_t(4));
    QCOMPARE(QString::fromStdString(ranked[0]), QStringLiteral("192.168.1.20"));
    QCOMPARE(QString::fromStdString(ranked[1]), QStringLiteral("10.0.0.5"));
    // Presence data may only name literal IPv4 addresses.
    QCOMPARE(endpointsFromJson(Json::array({{{"host", "evil.example"}, {"port", 1}},
                                            {{"host", "192.168.1.2"}, {"port", 32147}}}))
                 .size(),
             std::size_t(1));
  }

  void sealedFramesRoundTrip() {
    const std::string key = randomBytes(kSessionKeyBytes);
    const std::string nc = randomBytes(kNonceBytes), nt = randomBytes(kNonceBytes);
    SecureChannel controller, target;
    QVERIFY(controller.establish(key, nc, nt, true));
    QVERIFY(target.establish(key, nc, nt, false));
    // Larger than one fragment, so reassembly is exercised.
    const std::string big(200 * 1024, 'q');
    const auto frames = controller.seal(SecureChannel::Kind::Json, big);
    QVERIFY(frames.size() > 1);
    SecureChannel::Kind kind;
    std::string out;
    for (std::size_t i = 0; i + 1 < frames.size(); ++i)
      QCOMPARE(target.open(frames[i], kind, out), SecureChannel::OpenResult::Incomplete);
    QCOMPARE(target.open(frames.back(), kind, out), SecureChannel::OpenResult::Message);
    QCOMPARE(out.size(), big.size());
    // Replies use the other direction's key.
    auto reply = target.seal(SecureChannel::Kind::Data, "pong");
    QCOMPARE(controller.open(reply[0], kind, out), SecureChannel::OpenResult::Message);
    QCOMPARE(QString::fromStdString(out), QStringLiteral("pong"));
    QVERIFY(kind == SecureChannel::Kind::Data);
  }

  void tamperingIsFatal() {
    const std::string key = randomBytes(kSessionKeyBytes);
    const std::string nc = randomBytes(kNonceBytes), nt = randomBytes(kNonceBytes);
    SecureChannel controller, target, stranger;
    controller.establish(key, nc, nt, true);
    target.establish(key, nc, nt, false);
    stranger.establish(randomBytes(kSessionKeyBytes), nc, nt, true);
    SecureChannel::Kind kind;
    std::string out;
    // A frame sealed with another key never opens.
    QCOMPARE(target.open(stranger.seal(SecureChannel::Kind::Json, "{}")[0], kind, out),
             SecureChannel::OpenResult::Failed);
    SecureChannel fresh;
    fresh.establish(key, nc, nt, false);
    auto frame = controller.seal(SecureChannel::Kind::Json, "{\"type\":\"command\"}")[0];
    frame[5] ^= 1;
    QCOMPARE(fresh.open(frame, kind, out), SecureChannel::OpenResult::Failed);
    // Once broken, the channel stays broken.
    QCOMPARE(fresh.open(controller.seal(SecureChannel::Kind::Json, "{}")[0], kind, out),
             SecureChannel::OpenResult::Failed);
  }

  void audioStreamsChunkAndReassemble() {
    Json meta = {{"track", "abc"}, {"timestamp", 10.0}, {"codec", "pcm_s16"}, {"sample_rate", 48000},
                 {"channels", 2}, {"meta", {{"mix", "m1"}}}};
    std::string pcm(600 * 1024, '\0');
    for (std::size_t i = 0; i < pcm.size(); ++i)
      pcm[i] = static_cast<char>(i * 7);
    QVERIFY(normalizeStream(meta, pcm.size()).empty());
    QVERIFY(!jsonString(meta, "stream").empty());
    OutgoingStream out{"s1", meta, pcm};
    std::vector<std::pair<Json, std::string>> frames;
    while (!out.done())
      frames.push_back(out.next());
    QCOMPARE(frames.size(), std::size_t(3));
    // Each PCM chunk starts on a frame, so its timestamp is exact target media time.
    QCOMPARE(frames[1].first.value("timestamp", 0.0), 10.0 + 65536.0 / 48000.0);
    QVERIFY(!frames[1].first.value("final", true) && frames[2].first.value("final", false));
    StreamAssembler assembler;
    std::string stream, error;
    QVERIFY(!assembler.accept(frames[0].first, frames[0].second, stream, error) && error.empty());
    QVERIFY(!assembler.accept(frames[1].first, frames[1].second, stream, error) && error.empty());
    auto finished = assembler.accept(frames[2].first, frames[2].second, stream, error);
    QVERIFY(finished && error.empty());
    QVERIFY(finished->payload == pcm);
    QCOMPARE(finished->header.value("timestamp", 0.0), 10.0);
    QCOMPARE(finished->header.value("bytes", 0), static_cast<int>(pcm.size()));
    QCOMPARE(QString::fromStdString(finished->header["meta"].value("mix", "")), QStringLiteral("m1"));
  }

  void brokenStreamsAreDropped() {
    Json pcm = {{"codec", "pcm_f32"}, {"sample_rate", 44100}, {"channels", 2}};
    QVERIFY(!normalizeStream(pcm, 12).empty()); // Not a whole stereo f32 frame.
    Json unknown = {{"codec", "mp3"}};
    QVERIFY(!normalizeStream(unknown, 100).empty());
    Json empty = {{"codec", "source"}};
    QVERIFY(!normalizeStream(empty, 0).empty());
    Json huge = {{"codec", "source"}};
    QVERIFY(!normalizeStream(huge, kStreamMaxBytes + 1).empty());
    Json source = {{"stream", "up"}, {"codec", "source"}};
    QVERIFY(normalizeStream(source, kStreamChunkBytes * 2 + 5).empty());
    OutgoingStream out{"s1", source, std::string(kStreamChunkBytes * 2 + 5, 'x')};
    auto first = out.next();
    out.next();
    auto third = out.next();
    StreamAssembler assembler;
    std::string stream, error;
    assembler.accept(first.first, first.second, stream, error);
    // A missing chunk fails the stream, so nobody hears a gap spliced into their song.
    QVERIFY(!assembler.accept(third.first, third.second, stream, error));
    QCOMPARE(QString::fromStdString(error), QStringLiteral("invalid_stream"));
    QCOMPARE(QString::fromStdString(stream), QStringLiteral("up"));
    error.clear();
    assembler.accept(first.first, first.second, stream, error);
    QVERIFY(assembler.clear() == std::vector<std::string>{"up"});
  }

  void proofsBindEverything() {
    const std::string key = randomBytes(kSessionKeyBytes);
    const std::string proof = handshakeProof(key, "target", "s1", "nc", "nt", "phone", "pc");
    QVERIFY(equalSecret(proof, handshakeProof(key, "target", "s1", "nc", "nt", "phone", "pc")));
    QVERIFY(!equalSecret(proof, handshakeProof(key, "controller", "s1", "nc", "nt", "phone", "pc")));
    QVERIFY(!equalSecret(proof, handshakeProof(key, "target", "s2", "nc", "nt", "phone", "pc")));
    QVERIFY(!equalSecret(proof, handshakeProof(key, "target", "s1", "nc", "nt", "pc", "phone")));
  }
};

QTEST_GUILESS_MAIN(ConnectCoreTest)
#include "connect_core_test.moc"
