/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.sfg.orchard.mobile.lyrics.translation

import android.annotation.SuppressLint
import android.content.Context
import dev.sfg.orchard.mobile.model.LyricTranslationProvider
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher

/** API secrets stay encrypted with a non-exportable Android Keystore key. */
@SuppressLint("UseKtx") // A failed disk write must not look like a saved credential.
internal class TranslationApiKeys(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("lyric_translation_keys", Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCipher("orchard_lyric_translation_keys_v1")

    @Synchronized fun has(provider: LyricTranslationProvider): Boolean = preferences.contains(provider.key)

    @Synchronized fun load(provider: LyricTranslationProvider): String =
        preferences.getString(provider.key, null)?.let(cipher::decrypt).orEmpty()

    @Synchronized fun save(provider: LyricTranslationProvider, key: String) {
        require(provider != LyricTranslationProvider.LOCAL)
        require(key.isNotBlank())
        check(preferences.edit().putString(provider.key, cipher.encrypt(key.trim())).commit()) {
            "API key storage failed"
        }
    }

    @Synchronized fun remove(provider: LyricTranslationProvider) {
        check(preferences.edit().remove(provider.key).commit()) { "API key deletion failed" }
    }
}
