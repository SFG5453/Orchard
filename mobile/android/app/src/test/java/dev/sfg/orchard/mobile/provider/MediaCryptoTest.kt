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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.provider

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MediaCryptoTest {
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 5869 test case 1.
    @Test fun `hkdf matches the RFC 5869 vector`() {
        val out = MediaCrypto.hkdfSha256(
            hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            out,
        )
    }

    // RFC 5869 test case 3: empty salt and info.
    @Test fun `hkdf treats an empty salt as zeros`() {
        val out = MediaCrypto.hkdfSha256(hex("0b".repeat(22)), ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            out,
        )
    }

    @Test fun `aes modes decrypt what the JCA encrypts`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        val plain = ByteArray(50) { (it * 7).toByte() }
        for ((mode, transform) in listOf(0 to "AES/CBC/PKCS5Padding", 1 to "AES/CTR/NoPadding")) {
            val cipher = Cipher.getInstance(transform)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            assertArrayEquals(plain, MediaCrypto.aes128(mode, key, iv, cipher.doFinal(plain)))
        }
    }
}
