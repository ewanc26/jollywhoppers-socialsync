package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.AchievementMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class AchievementMetadataTests {
    @Test
    fun `extracts vanilla advancement categories`() {
        assertEquals("story", AchievementMetadata.categoryOf("minecraft:story/mine_stone"))
        assertEquals("nether", AchievementMetadata.categoryOf("minecraft:nether/find_fortress"))
        assertEquals("adventure", AchievementMetadata.categoryOf("minecraft:adventure/kill_a_mob"))
    }

    @Test
    fun `extracts categories from namespaced datapack advancements`() {
        assertEquals("quests", AchievementMetadata.categoryOf("example:quests/first_step"))
        assertEquals("other", AchievementMetadata.categoryOf("invalid"))
    }
}
