package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.AtProtoClient
import com.jollywhoppers.atproto.server.AtProtoSessionManager
import com.jollywhoppers.atproto.server.RecordManager
import io.github.kikin81.atproto.runtime.NoAuth
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AtProtoCommandsValidationTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    private lateinit var client: AtProtoClient

    @BeforeEach
    fun setup() {
        client = AtProtoClient(
            slingshotUrl = "https://slingshot.microcosm.blue",
            fallbackPdsUrl = "https://bsky.social"
        )
    }

    @Test
    fun `isValidDid accepts valid PLC DIDs`() {
        assertTrue(client.isValidDid("did:plc:abc123def456"))
        assertTrue(client.isValidDid("did:plc:ewvi7nxzy7s2r3dklax32kf5e"))
    }

    @Test
    fun `isValidDid accepts valid web DIDs`() {
        assertTrue(client.isValidDid("did:web:example.com"))
        assertTrue(client.isValidDid("did:web:user.bsky.social"))
    }

    @Test
    fun `isValidDid rejects empty string`() {
        assertFalse(client.isValidDid(""))
    }

    @Test
    fun `isValidDid rejects non-did strings`() {
        assertFalse(client.isValidDid("not-a-did"))
        assertFalse(client.isValidDid("alice.bsky.social"))
    }

    @Test
    fun `isValidDid rejects unsupported DID methods`() {
        assertFalse(client.isValidDid("did:key:z6Mk"))
        assertFalse(client.isValidDid("did:example:123"))
    }

    @Test
    fun `isValidHandle accepts valid AT Protocol handles`() {
        assertTrue(client.isValidHandle("alice.bsky.social"))
        assertTrue(client.isValidHandle("test.handler.example.com"))
        assertTrue(client.isValidHandle("a-b.c-d.e-f"))
    }

    @Test
    fun `isValidHandle rejects empty string`() {
        assertFalse(client.isValidHandle(""))
    }

    @Test
    fun `isValidHandle rejects handles with leading dashes`() {
        assertFalse(client.isValidHandle("-invalid"))
    }

    @Test
    fun `isValidHandle rejects handles with trailing dashes`() {
        assertFalse(client.isValidHandle("invalid-"))
    }

    @Test
    fun `isValidHandle rejects handles with special characters`() {
        assertFalse(client.isValidHandle("alice@bsky.social"))
        assertFalse(client.isValidHandle("alice bsky social"))
        assertFalse(client.isValidHandle("alice.bsky_social"))
    }

    @Test
    fun `parseAtUri decomposes valid AT URI`() {
        val recordManager = createRecordManager()
        val components = recordManager.parseAtUri(
            "at://did:plc:test/com.example.collection/rkey123"
        )
        assertNotNull(components)
        assertEquals("did:plc:test", components!!.did)
        assertEquals("com.example.collection", components.collection)
        assertEquals("rkey123", components.rkey)
    }

    @Test
    fun `parseAtUri returns null for string without at protocol prefix`() {
        val recordManager = createRecordManager()
        assertTrue(recordManager.parseAtUri("not-an-at-uri") == null)
    }

    @Test
    fun `parseAtUri returns null for URI missing parts`() {
        val recordManager = createRecordManager()
        assertTrue(recordManager.parseAtUri("at://missing-parts") == null)
    }

    @Test
    fun `parseAtUri returns null for empty string`() {
        val recordManager = createRecordManager()
        assertTrue(recordManager.parseAtUri("") == null)
    }

    @Test
    fun `generateTID produces non-empty string`() {
        val recordManager = createRecordManager()
        val tid = recordManager.generateTID()
        assertNotNull(tid)
        assertTrue(tid.isNotEmpty())
    }

    @Test
    fun `generateTID produces unique values on successive calls`() {
        val recordManager = createRecordManager()
        val tid1 = recordManager.generateTID()
        val tid2 = recordManager.generateTID()
        assertTrue(tid1 != tid2)
    }

    @Test
    fun `writeOperation Create stores collection rkey and value`() {
        val jsonObj = buildJsonObject { put("key", "value") }
        val create = RecordManager.WriteOperation.Create(
            collection = "com.example.collection",
            rkey = "my-rkey",
            value = jsonObj
        )
        assertEquals("com.example.collection", create.collection)
        assertEquals("my-rkey", create.rkey)
        assertEquals(jsonObj, create.value)
    }

    @Test
    fun `writeOperation Update stores collection rkey and value`() {
        val jsonObj = buildJsonObject { put("score", 42) }
        val update = RecordManager.WriteOperation.Update(
            collection = "com.example.stats",
            rkey = "existing-rkey",
            value = jsonObj
        )
        assertEquals("com.example.stats", update.collection)
        assertEquals("existing-rkey", update.rkey)
    }

    @Test
    fun `writeOperation Delete stores collection and rkey`() {
        val delete = RecordManager.WriteOperation.Delete(
            collection = "com.example.sessions",
            rkey = "delete-this"
        )
        assertEquals("com.example.sessions", delete.collection)
        assertEquals("delete-this", delete.rkey)
    }

    @Test
    fun `writeOperation Create can be created without rkey`() {
        val jsonObj = buildJsonObject { put("auto", true) }
        val create = RecordManager.WriteOperation.Create(
            collection = "com.example.auto",
            rkey = null,
            value = jsonObj
        )
        assertEquals("com.example.auto", create.collection)
        assertTrue(create.rkey == null)
    }

    private fun createRecordManager(): RecordManager {
        val testHttpClient = HttpClient(CIO) { expectSuccess = false }
        val xrpcClient = XrpcClient(
            baseUrl = "https://bsky.social",
            httpClient = testHttpClient,
            authProvider = NoAuth
        )
        val sessionManager = AtProtoSessionManager(
            storageFile = tempDir.resolve("test-sessions.json"),
            client = client
        )
        return RecordManager(
            xrpcClient,
            Json { ignoreUnknownKeys = true },
            sessionManager
        )
    }
}
