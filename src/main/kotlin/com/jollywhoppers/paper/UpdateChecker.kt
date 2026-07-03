package com.jollywhoppers.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI

class UpdateChecker(
    private val plugin: JavaPlugin,
    private val repoOwner: String,
    private val repoName: String,
) {

    fun checkAsync() {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val url = URI("https://api.github.com/repos/$repoOwner/$repoName/releases/latest").toURL()
                val connection = url.openConnection()
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "$repoName/$repoOwner")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val json = connection.inputStream.bufferedReader().readText()
                val root = Json.parseToJsonElement(json).jsonObject
                val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return@Runnable
                val currentVersion = plugin.pluginMeta.version
                val latestVersion = tagName.removePrefix("v")

                if (currentVersion != latestVersion) {
                    val msg = "Update available: v$latestVersion (current: v$currentVersion) — $url"
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.logger.info(msg)
                    })
                }
            } catch (_: Exception) {
            }
        })
    }
}
