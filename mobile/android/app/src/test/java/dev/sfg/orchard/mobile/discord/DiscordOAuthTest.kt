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

package dev.sfg.orchard.mobile.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordOAuthTest {

    @Test
    fun generatesValidCodeVerifierAndChallenge() {
        val verifier = DiscordOAuthRepository.generateCodeVerifier()
        assertNotNull(verifier)
        assertTrue("Verifier should be at least 43 characters", verifier.length >= 43)
        assertFalse("Verifier should be URL safe (no +)", verifier.contains("+"))
        assertFalse("Verifier should be URL safe (no /)", verifier.contains("/"))
        assertFalse("Verifier should be unpadded (no =)", verifier.contains("="))

        val challenge = DiscordOAuthRepository.generateCodeChallenge(verifier)
        assertNotNull(challenge)
        assertTrue("Challenge should be non-empty", challenge.isNotEmpty())
        assertFalse("Challenge should be URL safe (no +)", challenge.contains("+"))
        assertFalse("Challenge should be URL safe (no /)", challenge.contains("/"))
        assertFalse("Challenge should be unpadded (no =)", challenge.contains("="))
    }

    @Test
    fun deterministicSha256Challenge() {
        val testVerifier = "test_code_verifier_12345678901234567890"
        val challenge1 = DiscordOAuthRepository.generateCodeChallenge(testVerifier)
        val challenge2 = DiscordOAuthRepository.generateCodeChallenge(testVerifier)
        assertEquals(challenge1, challenge2)
    }

    @Test
    fun generatesUniqueStates() {
        val state1 = DiscordOAuthRepository.generateState()
        val state2 = DiscordOAuthRepository.generateState()
        assertTrue(state1.isNotBlank())
        assertTrue(state2.isNotBlank())
        assertFalse("Random states should differ", state1 == state2)
    }
}
