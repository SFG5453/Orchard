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

package dev.sfg.orchard.connect.client

import dev.sfg.orchard.connect.protocol.ConnectClientError
import dev.sfg.orchard.connect.protocol.ConnectClientStatus
import dev.sfg.orchard.connect.protocol.ConnectCommand
import dev.sfg.orchard.connect.protocol.ConnectProtocol
import dev.sfg.orchard.connect.protocol.ConnectResults
import dev.sfg.orchard.connect.protocol.ConnectSnapshot
import dev.sfg.orchard.connect.session.ConnectSessionStore
import dev.sfg.orchard.connect.session.SecureDeviceTokenGenerator
import dev.sfg.orchard.connect.session.StoredConnectSession
import dev.sfg.orchard.connect.transport.ConnectTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class OrchardConnectClientTest {
    @Test
    fun pairsThenAllowsTypedCommands() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore()
        val events = RecordingListener()
        val client = OrchardConnectClient(
            factory,
            session,
            SecureDeviceTokenGenerator(SecureRandom(byteArrayOf(1, 2, 3))),
            "Test Phone",
            Runnable::run,
            events
        )

        client.connect("http://192.168.1.8:32145", "pair-token")
        assertFalse(client.send(ConnectCommand.Next))
        factory.transport.listener.onOpened()
        val hello = factory.transport.emissions.single()
        assertEquals(ConnectProtocol.Event.HELLO, hello.event)
        assertEquals("pair-token", (hello.payload as JSONObject).getString(ConnectProtocol.Field.TOKEN))
        assertEquals(3, (hello.payload as JSONObject).getInt(ConnectProtocol.Field.PROTOCOL_VERSION))

        hello.ack?.invoke(Result.success(JSONObject().put("ok", true).put("data", JSONObject()
            .put("status", "approved")
            .put("protocolVersion", 3)
            .put("state", JSONObject().put("status", "connected").put("protocolVersion", 3)))))

        assertEquals(ConnectClientStatus.APPROVED, client.status())
        assertEquals(3, client.protocolVersion())
        assertTrue(client.send(ConnectCommand.Next))
        assertEquals(ConnectProtocol.CommandType.NEXT, (factory.transport.emissions.last().payload as JSONObject).getString("type"))
        assertEquals("connected", events.snapshots.single().status)
        assertEquals(3, events.snapshots.single().protocolVersion)
    }

    @Test
    fun legacyDesktopDefaultsToProtocolVersionOne() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore()
        val events = RecordingListener()
        val client = OrchardConnectClient(
            factory, session, SecureDeviceTokenGenerator(), "Phone", Runnable::run, events
        )
        client.connect("http://legacy:32145", "pair")
        factory.transport.listener.onOpened()
        val hello = factory.transport.emissions.single()
        hello.ack?.invoke(Result.success(JSONObject().put("ok", true).put("data", JSONObject()
            .put("status", "approved")
            .put("state", JSONObject().put("status", "connected")))))

        assertEquals(1, client.protocolVersion())
        assertEquals(1, events.snapshots.single().protocolVersion)
    }

    @Test
    fun approvalEventPersistsReturnedCredentialAndRevocationClearsIt() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore()
        val client = OrchardConnectClient(
            factory, session, SecureDeviceTokenGenerator(), "Phone", Runnable::run, RecordingListener()
        )
        client.connect("http://desktop:32145", "pair")
        factory.transport.listener.onOpened()
        factory.transport.listener.onEvent(
            ConnectProtocol.Event.APPROVED,
            JSONObject().put("deviceToken", "approved-token").put("state", JSONObject())
        )
        assertEquals("approved-token", session.value.deviceToken)

        factory.transport.listener.onEvent(ConnectProtocol.Event.REVOKED, JSONObject())
        assertEquals("", session.value.deviceToken)
    }

    @Test
    fun doesNotOfferCredentialToDifferentDesktopHost() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore().apply {
            value = StoredConnectSession("http://old-host:32145", "old-host", "old-host-token")
        }
        val client = OrchardConnectClient(
            factory, session, SecureDeviceTokenGenerator(), "Phone", Runnable::run, RecordingListener()
        )

        client.connect("http://new-host:32145", "pair")
        factory.transport.listener.onOpened()
        val hello = factory.transport.emissions.single().payload as JSONObject

        assertTrue(hello.getString(ConnectProtocol.Field.DEVICE_TOKEN).isNotEmpty())
        assertFalse(hello.getString(ConnectProtocol.Field.DEVICE_TOKEN) == "old-host-token")
    }

    @Test
    fun higherDesktopProtocolVersionCapsToMaxSupportedClientVersion() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore()
        val events = RecordingListener()
        val client = OrchardConnectClient(
            factory, session, SecureDeviceTokenGenerator(), "Phone", Runnable::run, events
        )
        client.connect("http://future-desktop:32145", "pair")
        factory.transport.listener.onOpened()
        val hello = factory.transport.emissions.single()
        hello.ack?.invoke(Result.success(JSONObject().put("ok", true).put("data", JSONObject()
            .put("status", "approved")
            .put("protocolVersion", 99)
            .put("state", JSONObject().put("status", "connected").put("protocolVersion", 99)))))

        assertEquals(ConnectProtocol.PROTOCOL_VERSION, client.protocolVersion())
        assertEquals(ConnectProtocol.PROTOCOL_VERSION, events.snapshots.single().protocolVersion)
    }

    @Test
    fun requestsAnalysisOverConnectWhenApproved() {
        val factory = FakeTransportFactory()
        val session = FakeSessionStore()
        val events = RecordingListener()
        val client = OrchardConnectClient(
            factory, session, SecureDeviceTokenGenerator(), "Phone", Runnable::run, events
        )
        client.connect("http://desktop:32145", "pair")
        factory.transport.listener.onOpened()
        val hello = factory.transport.emissions.single()
        hello.ack?.invoke(Result.success(JSONObject().put("ok", true).put("data", JSONObject()
            .put("status", "approved")
            .put("protocolVersion", 3))))

        assertTrue(client.requestAnalysis(listOf("t1", "t2"), "req-1"))
        val emitted = factory.transport.emissions.last()
        assertEquals(ConnectProtocol.Event.ANALYSIS, emitted.event)
        val payload = emitted.payload as JSONObject
        assertEquals("req-1", payload.getString(ConnectProtocol.Field.REQUEST_ID))
        assertEquals(2, payload.getJSONArray(ConnectProtocol.Field.TRACK_IDS).length())
    }

    private data class Emission(
        val event: String,
        val payload: Any?,
        val ack: ((Result<JSONObject>) -> Unit)?
    )

    private class FakeTransportFactory : ConnectTransport.Factory {
        lateinit var transport: FakeTransport
        override fun create(serverUrl: String, listener: ConnectTransport.Listener): ConnectTransport =
            FakeTransport(listener).also { transport = it }
    }

    private class FakeTransport(val listener: ConnectTransport.Listener) : ConnectTransport {
        val emissions = mutableListOf<Emission>()
        override fun connect() = Unit
        override fun emit(event: String, payload: Any?, ack: ((Result<JSONObject>) -> Unit)?) {
            emissions += Emission(event, payload, ack)
        }
        override fun close() = Unit
    }

    private class FakeSessionStore : ConnectSessionStore {
        var value = StoredConnectSession()
        override fun load() = value
        override fun saveLocation(serverUrl: String, serverHost: String) { value = value.copy(serverUrl = serverUrl, serverHost = serverHost) }
        override fun saveDeviceToken(deviceToken: String) { value = value.copy(deviceToken = deviceToken) }
        override fun clearDeviceToken() { value = value.copy(deviceToken = "") }
        override fun clear() { value = StoredConnectSession() }
    }

    private class RecordingListener : OrchardConnectClient.Listener {
        val statuses = mutableListOf<ConnectClientStatus>()
        val snapshots = mutableListOf<ConnectSnapshot>()
        override fun onStatusChanged(status: ConnectClientStatus) { statuses += status }
        override fun onSnapshot(snapshot: ConnectSnapshot) { snapshots += snapshot }
        override fun onSearchResults(results: ConnectResults) = Unit
        override fun onLibraryResults(results: ConnectResults) = Unit
        override fun onError(error: ConnectClientError) { throw AssertionError(error) }
    }
}
