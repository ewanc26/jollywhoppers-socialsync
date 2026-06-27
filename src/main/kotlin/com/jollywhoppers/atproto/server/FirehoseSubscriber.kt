package com.jollywhoppers.atproto.server

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class FirehoseSubscriber(
    private val appViewService: AppViewService,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:firehose")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var client: HttpClient? = null
    private var job: Job? = null

    private val targetCollections = setOf(
        "com.jollywhoppers.minecraft.player.profile",
        "com.jollywhoppers.minecraft.player.stats",
        "com.jollywhoppers.minecraft.achievement",
        "com.jollywhoppers.minecraft.player.session",
        "com.jollywhoppers.minecraft.server.status",
    )

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    connectAndSubscribe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Firehose connection failed, reconnecting in 10s", e)
                    delay(10_000)
                }
            }
        }
        logger.info("Firehose subscriber started")
    }

    private suspend fun connectAndSubscribe() {
        client = HttpClient {
            install(WebSockets)
        }

        client!!.webSocket(HttpMethod.Get, "jetstream1.us-east-bsky.network", 443, "/subscribe") {
            logger.info("Connected to JetStream firehose")

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    processMessage(text)
                }
            }
        }
    }

    private fun processMessage(text: String) {
        try {
            val msg = json.parseToJsonElement(text).jsonObject
            val kind = msg["kind"]?.jsonPrimitive?.contentOrNull ?: return

            if (kind != "commit") return

            val commit = msg["commit"]?.jsonObject ?: return
            val collection = commit["collection"]?.jsonPrimitive?.contentOrNull ?: return

            if (collection !in targetCollections) return

            val repo = msg["did"]?.jsonPrimitive?.contentOrNull ?: return
            val rkey = commit["rkey"]?.jsonPrimitive?.contentOrNull ?: return
            val uri = "at://$repo/$collection/$rkey"
            val record = commit["record"] ?: return
            val action = commit["operation"]?.jsonPrimitive?.contentOrNull ?: "create"

            logger.debug("Firehose event: $action $uri")

            when (action) {
                "create", "update" -> {
                    when (collection) {
                        "com.jollywhoppers.minecraft.player.profile" -> appViewService.indexPlayerProfile(uri, record)
                        "com.jollywhoppers.minecraft.player.stats" -> appViewService.indexPlayerStats(uri, record)
                        "com.jollywhoppers.minecraft.achievement" -> appViewService.indexAchievement(uri, record)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to process firehose message", e)
        }
    }

    fun shutdown() {
        job?.cancel()
        scope.cancel()
        client?.close()
        logger.info("Firehose subscriber shut down")
    }
}
