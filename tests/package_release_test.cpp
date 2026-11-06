/* Copyright (C) 2026 SFG545. SPDX-License-Identifier: AGPL-3.0-or-later */
#include "security/Signature.hpp"
namespace orchard::boot { const std::vector<TrustedKey>& trustedKeys() { static const std::vector<TrustedKey> k={{"test",R"KEY(-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAET9hscwF5k6rLQnyVBB94FoqfSeXz
hh9H+o3Yara26ngBw1FhmvDDZj1LufhLewsD49/Zlqf9pg3CgFHq9dEviA==
-----END PUBLIC KEY-----
)KEY"}}; return k; } }

#include "update/package_release.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <iostream>
#define CHECK(condition) do { if (!(condition)) { std::cerr << "Check failed at line " << __LINE__ << "\n"; return 1; } } while (false)
int main() {
  QByteArray data = R"json({"format": "orchard-signed-v1", "algorithm": "ecdsa-p256-sha256", "keyId": "test", "payload": "eyJzY2hlbWEiOjEsImNoYW5uZWwiOiJzdGFibGUiLCJ2ZXJzaW9uIjoiMS4yLjQifQ==", "signature": "MEQCIEBYiYV8kSU0ZJH8Vp+ha/Sdimoa5B4mXWh3Y9LmbG+TAiBt6fXEmvq7cMstjJdzJbYE7XVsjf5eRLx8PxwB9Brolw=="})json"; QString version;
  CHECK(packageReleaseVersion(data, "stable", &version) && version == "1.2.4");
  CHECK(!packageReleaseVersion(data, "canary", &version));
  QJsonObject object = QJsonDocument::fromJson(data).object(); object["payload"] = "e30=";
  CHECK(!packageReleaseVersion(QJsonDocument(object).toJson(), "stable", &version));
  CHECK(!packageReleaseVersion("{}", "stable", &version));
  CHECK(packageVersionIsNewer("1.2.3", "1.2.4"));
  CHECK(!packageVersionIsNewer("1.2.4", "1.2.4"));
  CHECK(!packageVersionIsNewer("1.2.5", "1.2.4"));
  CHECK(packageVersionIsNewer("1.2.4-canary.9", "1.2.4-canary.10"));
}
