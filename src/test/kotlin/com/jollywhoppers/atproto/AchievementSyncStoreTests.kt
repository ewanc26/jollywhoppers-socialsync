package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.AchievementSyncStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AchievementSyncStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: AchievementSyncStore
    private val testUuid = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        store = AchievementSyncStore(tempDir.resolve("achievement-sync.json"))
    }

    @Test
    fun `isSynced returns false for unknown player`() {
        assertFalse(store.isSynced(testUuid, "minecraft:story/root"))
    }

    @Test
    fun `markSynced makes isSynced return true`() {
        store.markSynced(testUuid, "minecraft:story/root")
        assertTrue(store.isSynced(testUuid, "minecraft:story/root"))
    }

    @Test
    fun `isSynced returns false for unsynced advancement after syncing a different one`() {
        store.markSynced(testUuid, "minecraft:story/root")
        assertFalse(store.isSynced(testUuid, "minecraft:nether/root"))
    }

    @Test
    fun `removeSynced removes an existing sync`() {
        store.markSynced(testUuid, "minecraft:story/root")
        assertTrue(store.isSynced(testUuid, "minecraft:story/root"))

        store.removeSynced(testUuid, "minecraft:story/root")
        assertFalse(store.isSynced(testUuid, "minecraft:story/root"))
    }

    @Test
    fun `removeSynced does not throw for unsynced advancement`() {
        store.removeSynced(testUuid, "nonexistent")
        assertFalse(store.isSynced(testUuid, "nonexistent"))
    }

    @Test
    fun `state persists across store reload`() {
        store.markSynced(testUuid, "minecraft:end/root")

        val reloaded = AchievementSyncStore(tempDir.resolve("achievement-sync.json"))
        assertTrue(reloaded.isSynced(testUuid, "minecraft:end/root"))
    }

    @Test
    fun `persistence survives multiple players`() {
        val uuid2 = UUID.randomUUID()

        store.markSynced(testUuid, "minecraft:story/root")
        store.markSynced(uuid2, "minecraft:nether/root")

        val reloaded = AchievementSyncStore(tempDir.resolve("achievement-sync.json"))
        assertTrue(reloaded.isSynced(testUuid, "minecraft:story/root"))
        assertTrue(reloaded.isSynced(uuid2, "minecraft:nether/root"))
        assertFalse(reloaded.isSynced(testUuid, "minecraft:nether/root"))
    }

    @Test
    fun `multiple advancements per player are tracked independently`() {
        store.markSynced(testUuid, "minecraft:story/root")
        store.markSynced(testUuid, "minecraft:nether/root")
        store.markSynced(testUuid, "minecraft:end/root")

        assertTrue(store.isSynced(testUuid, "minecraft:story/root"))
        assertTrue(store.isSynced(testUuid, "minecraft:nether/root"))
        assertTrue(store.isSynced(testUuid, "minecraft:end/root"))

        store.removeSynced(testUuid, "minecraft:nether/root")
        assertTrue(store.isSynced(testUuid, "minecraft:story/root"))
        assertFalse(store.isSynced(testUuid, "minecraft:nether/root"))
        assertTrue(store.isSynced(testUuid, "minecraft:end/root"))
    }

    @Test
    fun `removeSynced on unknown player does not throw`() {
        store.removeSynced(UUID.randomUUID(), "minecraft:story/root")
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
                    store.markSynced(playerUuid, "advancement-$i")
                    store.isSynced(playerUuid, "advancement-$i")
                    store.removeSynced(playerUuid, "advancement-$i")
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }
}
