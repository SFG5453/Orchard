/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "update/ReleaseClient.hpp"

#include "platform/Platform.hpp"
#include "security/Hash.hpp"
#include "security/Signature.hpp"
#include "util/Log.hpp"

namespace orchard::boot {

namespace {

constexpr std::size_t kChannelLimit = 1u << 20;
constexpr std::size_t kManifestLimit = 64u << 20;

} // namespace

ReleaseClient::ReleaseClient(std::string server, std::string platform)
    : server_(std::move(server)), platform_(std::move(platform)), session_(platform::createHttpSession()) {
  if (server_.empty() || server_.back() != '/')
    server_ += '/';
}

ChannelRelease ReleaseClient::fetchChannel(const std::string &channel, const CancelToken &cancel) {
  if (!isSafeName(channel))
    throw Error("invalid channel name \"" + channel + "\"");
  const std::string url = server_ + "releases/" + channel + ".json";
  const std::string envelope = httpGetText(*session_, url, kChannelLimit, cancel);
  return parseChannel(openSignedEnvelope(envelope, "release channel " + channel), channel);
}

ReleaseClient::SignedManifest ReleaseClient::fetchManifest(const ChannelRelease &release,
                                                           const CancelToken &cancel) {
  const auto entry = release.platforms.find(platform_);
  if (entry == release.platforms.end())
    throw Error("release " + release.version + " is not available for " + platform_);
  const PlatformRelease &ref = entry->second;
  std::string envelope = httpGetText(*session_, server_ + ref.manifest, kManifestLimit, cancel);
  if (envelope.size() != ref.size || sha256Hex(envelope) != ref.sha256)
    throw Error("manifest for " + release.version + " does not match the channel file");
  Manifest manifest =
      parseManifest(openSignedEnvelope(envelope, "release manifest"), release.version, platform_);
  return {std::move(manifest), std::move(envelope)};
}

ReleaseClient::SignedManifest ReleaseClient::fetchManifest(const std::string &version, const CancelToken &cancel) {
  if (!isSafeName(version))
    throw Error("invalid version \"" + version + "\"");
  std::string envelope =
      httpGetText(*session_, server_ + "manifests/" + version + "-" + platform_ + ".json", kManifestLimit, cancel);
  Manifest manifest = parseManifest(openSignedEnvelope(envelope, "release manifest"), version, platform_);
  return {std::move(manifest), std::move(envelope)};
}

void saveManifest(const Layout &layout, const std::string &platform, const std::string &envelope,
                  const Manifest &manifest) {
  writeFileAtomic(layout.manifestFile(manifest.version, platform), envelope);
}

std::optional<Manifest> loadManifest(const Layout &layout, const std::string &platform, const std::string &version) {
  try {
    const std::string envelope = readFile(layout.manifestFile(version, platform), kManifestLimit);
    return parseManifest(openSignedEnvelope(envelope, "stored manifest"), version, platform);
  } catch (const Error &e) {
    log::warn("stored manifest for " + version + " is unusable: " + e.what());
    return std::nullopt;
  }
}

} // namespace orchard::boot
