package com.jollywhoppers.atproto.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class BlueskyPostPublisher(
    private val recordManager: RecordManager,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:bluesky-posts")
    private companion object {
        const val MAX_POST_LENGTH = 299
    }

    @Serializable
    data class FeedPost(
        @SerialName("\$type")
        val type: String = "app.bsky.feed.post",
        val text: String,
        val createdAt: String,
        val langs: List<String> = listOf("en"),
    )

    suspend fun postAchievement(
        playerUuid: UUID,
        playerName: String,
        achievementId: String,
        achievementName: String?,
        category: String?,
        isChallenge: Boolean,
        serverId: String,
    ): Result<RecordManager.StrongRef> {
        val text = buildString {
            append(playerName)
            append(" unlocked ")
            append(achievementName?.takeIf { it.isNotBlank() } ?: achievementId)
            if (!category.isNullOrBlank()) {
                append(" [")
                append(category)
                append("]")
            }
            if (isChallenge) {
                append(" challenge")
            }
            append(" on ")
            append(serverId)
        }

        return postText(playerUuid, text, "achievement")
    }

    suspend fun postServerStatus(
        version: String,
        onlinePlayers: Int,
        maxPlayers: Int,
        motd: String,
        serverId: String,
        samplePlayers: List<String>,
    ): Result<RecordManager.StrongRef> {
        val sample = samplePlayers.filter { it.isNotBlank() }.take(3)
        val text = buildString {
            append("Server status on ")
            append(serverId)
            append(": ")
            append(onlinePlayers)
            append("/")
            append(maxPlayers)
            append(" online on ")
            append(version)
            if (sample.isNotEmpty()) {
                append(" - ")
                append(sample.joinToString(", "))
            }
            if (motd.isNotBlank()) {
                append(" | ")
                append(motd)
            }
        }

        return postText(ServerAccount.SERVER_PLAYER_UUID, text, "server status")
    }

    suspend fun postPlayerStats(
        playerUuid: UUID,
        summary: String,
    ): Result<RecordManager.StrongRef> {
        return postText(playerUuid, summary, "player stats")
    }

    private suspend fun postText(playerUuid: UUID, text: String, context: String): Result<RecordManager.StrongRef> {
        val post = FeedPost(
            text = compactText(text),
            createdAt = Instant.now().toString(),
        )
        return recordManager.createTypedRecord(
            playerUuid = playerUuid,
            collection = "app.bsky.feed.post",
            record = post,
        ).onSuccess {
            logger.info("Published Bluesky $context post")
        }.onFailure {
            logger.warn("Failed to publish Bluesky $context post: ${it.message}")
        }
    }

    private fun compactText(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= MAX_POST_LENGTH) normalized else normalized.take(296).trimEnd() + "..."
    }
}
