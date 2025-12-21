package com.jollywhoppers.atproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client-side AT Protocol client.
 * Handles identity resolution, authentication, and XRPC requests directly from the client.
 * 
 * SECURITY: All authentication happens on the client - passwords never leave the player's computer.
 */
class ClientAtProtoClient(
    private val slingshotUrl: String = "https://slingshot.microcosm.blue",
    private val fallbackPdsUrl: String = "https://bsky.social"
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    @Serializable
    data class MiniDoc(
        val did: String,
        val handle: String,
        val pds: String,
        val pdsKnown: Boolean = false
    )

    @Serializable
    data class CreateSessionRequest(
        val identifier: String,
        val password: String
    )

    @Serializable
    data class CreateSessionResponse(
        val did: String,
        val handle: String,
        val email: String? = null,
        val accessJwt: String,
        val refreshJwt: String
    )

    @Serializable
    data class RefreshSessionRequest(
        val refreshJwt: String
    )

    /**
     * Resolves an identifier to a MiniDoc using Slingshot.
     */
    suspend fun resolveMiniDoc(identifier: String): Result<MiniDoc> = runCatching {
        logger.info("Resolving identifier via Slingshot: ${sanitize(identifier)}")
        
        val url = "$slingshotUrl/xrpc/com.bad-example.identity.resolveMiniDoc?identifier=${encodeURIComponent(identifier)}"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Resolution failed with status ${response.statusCode()}")
        }

        val miniDoc = json.decodeFromString<MiniDoc>(response.body())
        logger.info("Resolved ${miniDoc.handle} -> PDS: ${miniDoc.pds}")
        miniDoc
    }

    /**
     * Creates an authenticated session.
     * This is where the actual login happens on the client.
     * Password is never sent to your Minecraft server - only to AT Protocol servers.
     */
    suspend fun createSession(identifier: String, password: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Creating session for: ${sanitize(identifier)}")
        
        // Resolve to find the correct PDS
        val pdsUrl = try {
            val miniDoc = resolveMiniDoc(identifier).getOrThrow()
            miniDoc.pds
        } catch (e: Exception) {
            logger.warn("Could not resolve PDS via Slingshot, using fallback")
            fallbackPdsUrl
        }
        
        val requestBody = CreateSessionRequest(
            identifier = identifier,
            password = password // Password only sent to AT Protocol servers, never to Minecraft server
        )
        
        val url = "$pdsUrl/xrpc/com.atproto.server.createSession"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CreateSessionRequest.serializer(), requestBody)))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            logger.error("Session creation failed: HTTP ${response.statusCode()}")
            throw Exception("Authentication failed - check your credentials")
        }

        val session = json.decodeFromString<CreateSessionResponse>(response.body())
        logger.info("Session created successfully")
        session
    }

    /**
     * Refreshes an existing session.
     */
    suspend fun refreshSession(refreshJwt: String, pdsUrl: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Refreshing session")
        
        val requestBody = RefreshSessionRequest(refreshJwt = refreshJwt)
        
        val url = "$pdsUrl/xrpc/com.atproto.server.refreshSession"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(RefreshSessionRequest.serializer(), requestBody)))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $refreshJwt")
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Session refresh failed")
        }

        json.decodeFromString<CreateSessionResponse>(response.body())
    }

    /**
     * Makes an authenticated XRPC request.
     */
    suspend fun xrpcRequest(
        method: String,
        endpoint: String,
        accessJwt: String,
        pdsUrl: String,
        body: String? = null
    ): Result<String> = runCatching {
        val url = "$pdsUrl/xrpc/$endpoint"
        
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $accessJwt")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))

        val request = when (method.uppercase()) {
            "GET" -> requestBuilder.GET().build()
            "POST" -> requestBuilder.POST(
                HttpRequest.BodyPublishers.ofString(body ?: "{}")
            ).build()
            "PUT" -> requestBuilder.PUT(
                HttpRequest.BodyPublishers.ofString(body ?: "{}")
            ).build()
            "DELETE" -> requestBuilder.DELETE().build()
            else -> throw IllegalArgumentException("Unsupported HTTP method")
        }

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw Exception("Request failed with status ${response.statusCode()}")
        }

        response.body()
    }

    private fun encodeURIComponent(value: String): String {
        return URI(null, null, null, -1, null, null, null)
            .resolve(value)
            .rawSchemeSpecificPart
            .replace("+", "%20")
    }
    
    private fun sanitize(input: String): String {
        return when {
            input.length <= 8 -> "***"
            else -> "${input.take(4)}...${input.takeLast(4)}"
        }
    }
}
