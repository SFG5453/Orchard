/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

// Download workers run TLS sessions in parallel and TLS 1.3 shares PSA key
// slots between them, so PSA needs its mutexes. Windows only uses the hash
// and signature code, which keeps all state per call.
#if !defined(_WIN32)
#define MBEDTLS_THREADING_C
#define MBEDTLS_THREADING_PTHREAD
#endif

// Client-only HTTPS: drop server, DTLS and session-resumption code.
#undef MBEDTLS_SSL_SRV_C
#undef MBEDTLS_SSL_PROTO_DTLS
#undef MBEDTLS_SSL_DTLS_ANTI_REPLAY
#undef MBEDTLS_SSL_DTLS_HELLO_VERIFY
#undef MBEDTLS_SSL_DTLS_CLIENT_PORT_REUSE
#undef MBEDTLS_SSL_DTLS_CONNECTION_ID
#undef MBEDTLS_SSL_COOKIE_C
#undef MBEDTLS_SSL_CACHE_C
#undef MBEDTLS_SSL_TICKET_C
#undef MBEDTLS_SSL_SESSION_TICKETS
#undef MBEDTLS_SSL_EARLY_DATA
#undef MBEDTLS_SSL_RENEGOTIATION
#undef MBEDTLS_SSL_CONTEXT_SERIALIZATION
#undef MBEDTLS_TIMING_C
// Legacy and rarely negotiated primitives.
#undef MBEDTLS_DES_C
#undef MBEDTLS_ARIA_C
#undef MBEDTLS_CAMELLIA_C
#undef MBEDTLS_RIPEMD160_C
#undef MBEDTLS_MD5_C
#undef MBEDTLS_PKCS7_C
#undef MBEDTLS_X509_CSR_PARSE_C
#undef MBEDTLS_X509_CSR_WRITE_C
#undef MBEDTLS_X509_CRT_WRITE_C
#undef MBEDTLS_X509_CREATE_C
#undef MBEDTLS_PK_WRITE_C
#undef MBEDTLS_PEM_WRITE_C
