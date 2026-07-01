package com.jollywhoppers.atproto.server

object AchievementMetadata {
    fun categoryOf(advancementId: String): String =
        advancementId.substringAfter(':', "other").substringBefore('/', "other").ifBlank { "other" }
}
