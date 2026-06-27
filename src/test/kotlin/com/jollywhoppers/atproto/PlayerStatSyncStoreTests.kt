package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.PlayerStatSyncStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PlayerStatSyncStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: PlayerStatSyncStore
    private val testUuid = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        store = PlayerStatSyncStore(tempDir.resolve("sync-state.json"))
    }

    @Test
    fun `new player has no sync state`() {
        val state = store.getState(testUuid)
        assertTrue(state == null)
    }

    @Test
    fun `shouldSync returns true for unknown player`() {
        assertTrue(store.shouldSync(testUuid, "hash123"))
    }

    @Test
    fun `shouldSync returns false when hash matches last sync`() {
        store.recordSuccess(testUuid, "hash123")
        assertFalse(store.shouldSync(testUuid, "hash123"))
    }

    @Test
    fun `shouldSync returns true when hash differs from last sync`() {
        store.recordSuccess(testUuid, "hash123")
        assertTrue(store.shouldSync(testUuid, "hash456"))
    }

    @Test
    fun `recordSuccess updates lastSyncedHash and timestamp`() {
        store.recordSuccess(testUuid, "final-hash")
        val state = store.getState(testUuid)
        assertNotNull(state)
        assertEquals("final-hash", state!!.lastSyncedHash)
        assertNotNull(state.lastSyncedAt)
    }

    @Test
    fun `recordFailure stores error message`() {
        store.recordFailure(testUuid, "Connection timeout")
        val state = store.getState(testUuid)
        assertNotNull(state)
        assertTrue(state!!.lastError!!.contains("Connection timeout"))
    }

    @Test
    fun `recordAttempt updates lastAttemptAt`() {
        store.recordAttempt(testUuid)
        val state = store.getState(testUuid)
        assertNotNull(state)
        assertNotNull(state!!.lastAttemptAt)
    }

    @Test
    fun `success clears previous error`() {
        store.recordFailure(testUuid, "Previous error")
        store.recordSuccess(testUuid, "hash")
        val state = store.getState(testUuid)
        assertTrue(state!!.lastError == null)
    }

    @Test
    fun `state persists across store reload`() {
        store.recordSuccess(testUuid, "persisted-hash")

        val reloaded = PlayerStatSyncStore(tempDir.resolve("sync-state.json"))
        val state = reloaded.getState(testUuid)
        assertNotNull(state)
        assertEquals("persisted-hash", state!!.lastSyncedHash)
    }

    @Test
    fun `persistence survives multiple players`() {
        val uuid2 = UUID.randomUUID()

        store.recordSuccess(testUuid, "hash-a")
        store.recordSuccess(uuid2, "hash-b")

        val reloaded = PlayerStatSyncStore(tempDir.resolve("sync-state.json"))
        assertEquals("hash-a", reloaded.getState(testUuid)!!.lastSyncedHash)
        assertEquals("hash-b", reloaded.getState(uuid2)!!.lastSyncedHash)
    }

    @Test
    fun `error message is truncated to 500 characters`() {
        val longError = "x".repeat(1000)
        store.recordFailure(testUuid, longError)
        val state = store.getState(testUuid)
        assertEquals(500, state!!.lastError!!.length)
    }

    @Test
    fun `concurrent access does not throw`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 1..threadCount) {
            executor.submit {
                try {
                    val playerUuid = UUID.randomUUID()
                    store.recordSuccess(playerUuid, "hash-$i")
                    store.shouldSync(playerUuid, "hash-$i")
                    store.recordFailure(playerUuid, "error-$i")
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }
}
