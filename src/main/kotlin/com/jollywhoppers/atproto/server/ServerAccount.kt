package com.jollywhoppers.atproto.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

object ServerAccount {
    val SERVER_PLAYER_UUID: UUID = UUID.nameUUIDFromBytes("server".toByteArray())

    private val logger = LoggerFactory.getLogger("atproto-connect:server-account")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var config: ServerAccountConfig? = null
    private var session: ServerSession? = null

    fun isConfigured(): Boolean = config != null

    fun getSession(): Result<ServerSession> = when {
        session != null -> Result.success(session!!)
        else -> Result.failure(IllegalStateException("Server account not authenticated. Use /atproto admin server-login"))
    }

    fun load(configDir: Path) {
        val file = configDir.resolve("server-account.json")
        if (Files.exists(file)) {
            try {
                val content = Files.readString(file)
                config = json.decodeFromString(ServerAccountConfig.serializer(), content)
                if (config?.refreshJwt != null) {
                    session = ServerSession(accessJwt = config!!.accessJwt ?: "", refreshJwt = config!!.refreshJwt!!, did = config!!.did ?: "", handle = config!!.handle ?: "")
                }
                logger.info("Server account loaded for ${config?.handle ?: "unknown"}")
            } catch (e: Exception) {
                logger.error("Failed to load server account", e)
            }
        }
    }

    fun save(configDir: Path) {
        val configToSave = config ?: return
        try {
            Files.createDirectories(configDir)
            val file = configDir.resolve("server-account.json")
            val content = json.encodeToString(ServerAccountConfig.serializer(), configToSave)
            val tempFile = configDir.resolve("server-account.json.tmp")
            Files.writeString(tempFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            logger.info("Server account saved")
        } catch (e: Exception) {
            logger.error("Failed to save server account", e)
        }
    }

    fun setSession(accessJwt: String, refreshJwt: String, did: String, handle: String, pdsUrl: String?) {
        config = ServerAccountConfig(
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            accessJwt = accessJwt,
            refreshJwt = refreshJwt,
        )
        session = ServerSession(accessJwt = accessJwt, refreshJwt = refreshJwt, did = did, handle = handle)
    }

    fun clearSession() {
        session = null
        config = config?.copy(accessJwt = null, refreshJwt = null)
    }

    @Serializable
    data class ServerAccountConfig(
        val did: String? = null,
        val handle: String? = null,
        val pdsUrl: String? = null,
        val accessJwt: String? = null,
        val refreshJwt: String? = null,
    )

    data class ServerSession(
        val accessJwt: String,
        val refreshJwt: String,
        val did: String,
        val handle: String,
    )
}
