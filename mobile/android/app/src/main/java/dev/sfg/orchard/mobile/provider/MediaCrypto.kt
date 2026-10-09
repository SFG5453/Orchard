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
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/** Crypto the provider host exposes to JS: HKDF-SHA256 and AES-128 (CBC decrypt, CTR). */
internal object MediaCrypto {
    private const val MAX_HKDF_OUTPUT = 1024
    private const val HASH_LENGTH = 32

    fun hkdfSha256(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_HKDF_OUTPUT)
        val mac = Mac.getInstance("HmacSHA256")
        // An empty salt means HashLen zeros (RFC 5869).
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, "HmacSHA256"))
        val prk = mac.doFinal(key)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter++.toByte())
            block = mac.doFinal()
            val count = min(block.size, length - offset)
            System.arraycopy(block, 0, out, offset, count)
            offset += count
        }
        return out
    }

    /** Mode 0 decrypts CBC with PKCS#7 padding; mode 1 applies CTR. */
    fun aes128(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val transform = if (mode == 0) "AES/CBC/PKCS5Padding" else "AES/CTR/NoPadding"
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
