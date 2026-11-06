/* Copyright (C) 2026 SFG545. SPDX-License-Identifier: AGPL-3.0-or-later */
#include "update/package_release.h"
#include "security/Signature.hpp"
#include "util/Version.hpp"

#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <mbedtls/pk.h>

bool packageReleaseVersion(const QByteArray &envelope, const QString &channel, QString *version)
{
    const QJsonObject doc = QJsonDocument::fromJson(envelope).object();
    if (doc.value(QStringLiteral("format")) != QLatin1String("orchard-signed-v1") ||
        doc.value(QStringLiteral("algorithm")) != QLatin1String("ecdsa-p256-sha256"))
        return false;
    const auto payload = QByteArray::fromBase64Encoding(doc.value(QStringLiteral("payload")).toString().toLatin1(),
                                                        QByteArray::AbortOnBase64DecodingErrors);
    const auto signature = QByteArray::fromBase64Encoding(doc.value(QStringLiteral("signature")).toString().toLatin1(),
                                                          QByteArray::AbortOnBase64DecodingErrors);
    if (!payload || !signature)
        return false;
    const auto id = doc.value(QStringLiteral("keyId")).toString().toStdString();
    const orchard::boot::TrustedKey *key = nullptr;
    for (const auto &candidate : orchard::boot::trustedKeys()) {
        if (candidate.id == id)
            key = &candidate;
    }
    if (!key)
        return false;
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    const QByteArray digest = QCryptographicHash::hash(payload.decoded, QCryptographicHash::Sha256);
    const bool valid = mbedtls_pk_parse_public_key(&pk, reinterpret_cast<const unsigned char *>(key->pem.c_str()),
                                                   key->pem.size() + 1) == 0 &&
                       mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECDSA) &&
                       mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256,
                           reinterpret_cast<const unsigned char *>(digest.constData()), digest.size(),
                           reinterpret_cast<const unsigned char *>(signature.decoded.constData()), signature.decoded.size()) == 0;
    mbedtls_pk_free(&pk);
    if (!valid)
        return false;
    const QJsonObject release = QJsonDocument::fromJson(payload.decoded).object();
    const QString found = release.value(QStringLiteral("version")).toString();
    static const QRegularExpression validVersion(QStringLiteral(R"(^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$)"));
    if (release.value(QStringLiteral("schema")).toInt() != 1 ||
        release.value(QStringLiteral("channel")).toString() != channel || !validVersion.match(found).hasMatch())
        return false;
    *version = found;
    return true;
}

bool packageVersionIsNewer(const QString &current, const QString &latest)
{
    return orchard::boot::compareVersions(current.toStdString(), latest.toStdString()) < 0;
}
