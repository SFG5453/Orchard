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
#include "connect/node_impl.h"

#include <algorithm>
#include <chrono>

// Command and RPC routing between controllers, targets and role hosts.
namespace orchard::connect {

std::string Node::Impl::resolveHost(const std::string &hostName) const {
  const Roles roles = effectiveRoles();
  if (hostName.rfind("provider:", 0) == 0) {
    const auto it = roles.providerHosts.find(hostName.substr(9));
    return it == roles.providerHosts.end() ? std::string() : it->second;
  }
  if (hostName == "artwork")
    return roles.artworkHost;
  if (hostName == "mix")
    return roles.mixHost;
  return hostName;
}

void Node::Impl::sendCommand(const std::string &id, Json command) {
  auto session = controllerSession();
  std::string error;
  if (!session || session->state() != SessionState::Connected)
    error = code::NotConnected;
  else
    error = normalizeCommand(command);
  if (error.empty() && !session->sendCommand(id, command))
    error = code::NotConnected;
  if (!error.empty())
    emit({{"event", "command_result"}, {"id", id}, {"ok", false}, {"error", error}});
}

void Node::Impl::sendRequest(const std::string &id, Json request) {
  auto fail = [&](const std::string &error) {
    emit({{"event", "rpc_result"}, {"id", id}, {"ok", false}, {"result", Json()}, {"error", error}});
  };
  const std::string method = jsonString(request, "method", 64);
  if (method.empty())
    return fail(code::InvalidRequest);
  const std::string target = resolveHost(jsonString(request, "host", 96));
  if (target.empty())
    return fail(jsonString(request, "host", 96).rfind("provider:", 0) == 0 ? code::ProviderUnavailable
                                                                           : code::HostUnavailable);
  if (target == self.id)
    return fail("local");
  std::shared_ptr<Session> session;
  for (const auto &[sid, candidate] : sessions) {
    if (candidate->peer().id == target && candidate->state() == SessionState::Connected)
      session = candidate;
  }
  if (!session || !session->sendRpc({{"id", id}, {"method", method}, {"params", request.value("params", Json::object())}}))
    return fail(code::HostUnavailable);
  auto strong = loop.lock();
  std::weak_ptr<Impl> weak = weak_from_this();
  PendingRpc pendingRpc{session->id(), 0};
  if (strong) {
    // Long jobs (a remote mix) name their own bound; everything else gets the default.
    const int timeoutMs = request.contains("timeout_ms")
                              ? static_cast<int>(std::clamp(jsonNumber(request, "timeout_ms"), 1000.0, 300000.0))
                              : config.rpcTimeoutMs;
    pendingRpc.timer = strong->after(std::chrono::milliseconds(timeoutMs), [weak, id] {
      auto self = weak.lock();
      if (!self || !self->rpcs.erase(id))
        return;
      self->emit({{"event", "rpc_result"}, {"id", id}, {"ok", false}, {"result", Json()}, {"error", code::Timeout}});
    });
  }
  rpcs[id] = pendingRpc;
}

void Node::Impl::respond(Json response) {
  const std::string id = jsonString(response, "id", 64);
  auto it = incomingRpcs.find(id);
  if (it == incomingRpcs.end())
    return;
  auto session = sessions.count(it->second) ? sessions[it->second] : nullptr;
  incomingRpcs.erase(it);
  if (!session)
    return;
  Json result = {{"id", id}, {"ok", jsonBool(response, "ok")}};
  if (response.contains("result"))
    result["result"] = response["result"];
  if (response.contains("error"))
    result["error"] = jsonString(response, "error", 128);
  session->sendRpcResult(result);
}

void Node::Impl::failPendingRpcs(const std::string &sessionId, const std::string &error) {
  auto strong = loop.lock();
  for (auto it = rpcs.begin(); it != rpcs.end();) {
    if (it->second.sessionId != sessionId) {
      ++it;
      continue;
    }
    if (strong)
      strong->cancel(it->second.timer);
    emit({{"event", "rpc_result"}, {"id", it->first}, {"ok", false}, {"result", Json()}, {"error", error}});
    it = rpcs.erase(it);
  }
}

} // namespace orchard::connect
