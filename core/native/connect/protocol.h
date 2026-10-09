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

#include <nlohmann/json.hpp>

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

// Orchard Connect v2 wire contract, shared verbatim by desktop and Android.
namespace orchard::connect {

using Json = nlohmann::json;

// Bumped only for incompatible wire changes. Features travel as capabilities.
constexpr int kProtocolMajor = 2;
constexpr int kProtocolMinor = 0;

// Preferred LAN listener port; 32145 belongs to v1 (Socket.IO) clients.
constexpr std::uint16_t kPreferredLanPort = 32147;
constexpr const char *kLanPath = "/orchard-connect";
constexpr const char *kDataChannelLabel = "orchard-connect";
// Offered as the hub WebSocket subprotocol next to the bearer token.
constexpr const char *kHubSubprotocol = "orchard-connect.2";

namespace code {
inline constexpr const char *IncompatibleClient = "incompatible_client";
inline constexpr const char *IncompatibleProtocol = "incompatible_protocol";
inline constexpr const char *Unauthorized = "unauthorized";
inline constexpr const char *NotConnected = "not_connected";
inline constexpr const char *InvalidCommand = "invalid_command";
inline constexpr const char *InvalidRequest = "invalid_request";
inline constexpr const char *PeerOffline = "peer_offline";
inline constexpr const char *Busy = "busy";
inline constexpr const char *Timeout = "timeout";
inline constexpr const char *HostUnavailable = "host_unavailable";
inline constexpr const char *ProviderUnavailable = "provider_unavailable";
inline constexpr const char *TransportFailed = "transport_failed";
inline constexpr const char *InvalidStream = "invalid_stream";
} // namespace code

namespace msg {
// Cleartext handshake.
inline constexpr const char *Hello = "hello";
inline constexpr const char *Challenge = "challenge";
inline constexpr const char *Auth = "auth";
inline constexpr const char *Reject = "reject";
// Encrypted session traffic.
inline constexpr const char *Capabilities = "capabilities";
inline constexpr const char *Roles = "roles";
inline constexpr const char *InitialPlayback = "initial_playback";
inline constexpr const char *State = "state";
inline constexpr const char *Command = "command";
inline constexpr const char *CommandResult = "command_result";
inline constexpr const char *Rpc = "rpc";
inline constexpr const char *RpcResult = "rpc_result";
inline constexpr const char *StreamEnd = "stream_end";
inline constexpr const char *Ping = "ping";
inline constexpr const char *Pong = "pong";
inline constexpr const char *Bye = "bye";
} // namespace msg

// Adds connect_protocol_major/minor to a message.
void stampProtocol(Json &message);

// Empty when the peer speaks this protocol, otherwise the reject code.
std::string checkProtocol(const Json &message);

std::optional<Json> parseJson(std::string_view text);
std::string base64Encode(std::string_view bytes);
std::optional<std::string> base64Decode(std::string_view text);
// Cryptographically random bytes from Mbed TLS's DRBG.
std::string randomBytes(std::size_t count);
// 128 random bits as lowercase hex.
std::string newId();
std::int64_t monotonicMs();

// Optional typed reads that never throw on a hostile payload.
std::string jsonString(const Json &object, const char *key, std::size_t maxLength = 4096);
double jsonNumber(const Json &object, const char *key, double fallback = 0.0);
bool jsonBool(const Json &object, const char *key, bool fallback = false);

} // namespace orchard::connect
