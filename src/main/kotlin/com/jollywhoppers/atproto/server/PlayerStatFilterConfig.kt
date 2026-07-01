package com.jollywhoppers.atproto.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Per-server configuration for non-sensitive stat filtering.
 *
 * Server operators can edit this file to control which Minecraft stats
 * are synced to AT Protocol. The [defaults] match the mod's built-in defaults.
 *
 * Config file location: `config/atproto-connect/stats-filter-config.json`
 *
 * Filtering logic (applied in order):
 * 1. If a stat key is in [sensitiveStatKeys], it is ALWAYS excluded
 * 2. If a stat's category starts with any prefix in [nonSensitiveCategories], it is included
 * 3. If a stat key is in [nonSensitiveCustomStats], it is included
 * 4. If a stat key starts with any prefix in [nonSensitivePrefixes], it is included
 * 5. Otherwise, the stat is excluded (unknown/mod stats)
 */
@Serializable
data class PlayerStatFilterConfig(
    val nonSensitivePrefixes: List<String> = listOf("minecraft:"),
    val sensitiveStatKeys: List<String> = listOf(
        "minecraft:leave_game",
        "minecraft:player_kills",
    ),
    val nonSensitiveCategories: List<String> = listOf(
        "minecraft:mined",
        "minecraft:crafted",
        "minecraft:used",
        "minecraft:broken",
        "minecraft:picked_up",
        "minecraft:dropped",
    ),
    val nonSensitiveCustomStats: List<String> = listOf(
        "minecraft:animals_bred",
        "minecraft:aviate_one_cm",
        "minecraft:bell_ring",
        "minecraft:boat_one_cm",
        "minecraft:clean_armor",
        "minecraft:clean_banner",
        "minecraft:climb_one_cm",
        "minecraft:crouch_one_cm",
        "minecraft:enchant_item",
        "minecraft:fall_one_cm",
        "minecraft:fill_cauldron",
        "minecraft:fish_caught",
        "minecraft:fly_one_cm",
        "minecraft:horse_one_cm",
        "minecraft:inspect_dispenser",
        "minecraft:inspect_dropper",
        "minecraft:inspect_hopper",
        "minecraft:interact_with_anvil",
        "minecraft:interact_with_beacon",
        "minecraft:interact_with_blast_furnace",
        "minecraft:interact_with_brewingstand",
        "minecraft:interact_with_campfire",
        "minecraft:interact_with_cartography_table",
        "minecraft:interact_with_crafting_table",
        "minecraft:interact_with_furnace",
        "minecraft:interact_with_grindstone",
        "minecraft:interact_with_lectern",
        "minecraft:interact_with_loom",
        "minecraft:interact_with_smithing_table",
        "minecraft:interact_with_smoker",
        "minecraft:interact_with_stonecutter",
        "minecraft:jump",
        "minecraft:minecart_one_cm",
        "minecraft:open_barrel",
        "minecraft:open_chest",
        "minecraft:open_enderchest",
        "minecraft:open_shulker_box",
        "minecraft:pig_one_cm",
        "minecraft:play_noteblock",
        "minecraft:play_record",
        "minecraft:play_time",
        "minecraft:pot_flower",
        "minecraft:raid_trigger",
        "minecraft:raid_win",
        "minecraft:ring_bell",
        "minecraft:sleep_in_bed",
        "minecraft:sprint_one_cm",
        "minecraft:strider_one_cm",
        "minecraft:swim_one_cm",
        "minecraft:talked_to_villager",
        "minecraft:target_hit",
        "minecraft:traded_with_villager",
        "minecraft:trigger_trapped_chest",
        "minecraft:tune_noteblock",
        "minecraft:use_cauldron",
        "minecraft:walk_on_water_one_cm",
        "minecraft:walk_one_cm",
        "minecraft:walk_under_water_one_cm",
    ),
) {
    /** Converts to Sets for efficient lookups at runtime */
    fun toFilterSets(): FilterSets = FilterSets(
        nonSensitivePrefixes = nonSensitivePrefixes.toSet(),
        sensitiveStatKeys = sensitiveStatKeys.toSet(),
        nonSensitiveCategories = nonSensitiveCategories.toSet(),
        nonSensitiveCustomStats = nonSensitiveCustomStats.toSet(),
    )

    data class FilterSets(
        val nonSensitivePrefixes: Set<String> = setOf("minecraft:"),
        val sensitiveStatKeys: Set<String> = setOf("minecraft:leave_game", "minecraft:player_kills"),
        val nonSensitiveCategories: Set<String> = setOf(
            "minecraft:mined", "minecraft:crafted", "minecraft:used",
            "minecraft:broken", "minecraft:picked_up", "minecraft:dropped",
        ),
        val nonSensitiveCustomStats: Set<String> = PlayerStatFilterConfig().nonSensitiveCustomStats.toSet(),
    ) {
        /**
         * Determines whether a stat key should be synced based on the filter rules.
         * Rules applied in order: sensitive blocklist, category allowlist, custom allowlist, prefix allowlist.
         */
        fun isStatNonSensitive(valueKey: String, categoryKey: String): Boolean {
            if (valueKey in sensitiveStatKeys) return false
            if (nonSensitiveCategories.any { categoryKey.startsWith(it) }) return true
            if (valueKey in nonSensitiveCustomStats) return true
            if (nonSensitivePrefixes.any { valueKey.startsWith(it) }) return true
            return false
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(configDir: Path): PlayerStatFilterConfig {
            val configFile = configDir.resolve("stats-filter-config.json")
            val logger = LoggerFactory.getLogger("atproto-connect:stats-filter")
            return try {
                if (Files.exists(configFile)) {
                    val content = Files.readString(configFile)
                    json.decodeFromString<PlayerStatFilterConfig>(content).also {
                        logger.info("Loaded stats filter config from ${configFile.fileName}")
                    }
                } else {
                    logger.info("No stats filter config found, creating default at ${configFile.fileName}")
                    val defaults = PlayerStatFilterConfig()
                    Files.createDirectories(configDir)
                    val content = json.encodeToString(defaults)
                    val tempFile = configFile.resolveSibling("${configFile.fileName}.tmp")
                    Files.writeString(tempFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    Files.move(tempFile, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    defaults
                }
            } catch (e: Exception) {
                logger.error("Failed to load stats filter config, using defaults", e)
                PlayerStatFilterConfig()
            }
        }
    }
}
