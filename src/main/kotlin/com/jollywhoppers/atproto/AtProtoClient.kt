package com.jollywhoppers.atproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * Enhanced AT Protocol client with PDS resolution via Slingshot.
 * Handles identity resolution, authentication, and XRPC requests.
 */
class AtProtoClient(
    private val slingshotUrl: String = "https://slingshot.microcosm.blue",
    private val fallbackPdsUrl: String = "https://bsky.social"
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
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
    data class DidDocument(
        val id: String,
        val alsoKnownAs: List<String> = emptyList(),
        val verificationMethod: List<VerificationMethod> = emptyList(),
        val service: List<Service> = emptyList()
    )

    @Serializable
    data class VerificationMethod(
        val id: String,
        val type: String,
        val controller: String,
        val publicKeyMultibase: String? = null
    )

    @Serializable
    data class Service(
        val id: String,
        val type: String,
        val serviceEndpoint: String
    )

    @Serializable
    data class HandleResolution(
        val did: String
    )

    @Serializable
    data class ProfileView(
        val did: String,
        val handle: String,
        val displayName: String? = null,
        val description: String? = null,
        val avatar: String? = null
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
        val refreshJwt: String,
        val didDoc: DidDocument? = null
    )

    @Serializable
    data class RefreshSessionRequest(
        val refreshJwt: String
    )

    /**
     * Resolves an identifier (handle or DID) to a MiniDoc using Slingshot.
     * This is the preferred method as it's fast and cached.
     */
    suspend fun resolveMiniDoc(identifier: String): Result<MiniDoc> = runCatching {
        logger.info("Resolving identifier via Slingshot: $identifier")
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$slingshotUrl/xrpc/com.bad-example.identity.resolveMiniDoc?identifier=${encodeURIComponent(identifier)}"))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Failed to resolve via Slingshot: HTTP ${response.statusCode()}")
        }

        val miniDoc = json.decodeFromString<MiniDoc>(response.body())
        logger.info("Resolved ${miniDoc.handle} -> PDS: ${miniDoc.pds}")
        miniDoc
    }

    /**
     * Resolves an AT Protocol handle to a DID.
     * Falls back to standard resolution if Slingshot fails.
     */
    suspend fun resolveHandle(handle: String): Result<String> = runCatching {
        logger.info("Resolving handle: $handle")
        
        // Try Slingshot's MiniDoc first
        try {
            val miniDoc = resolveMiniDoc(handle).getOrThrow()
            return@runCatching miniDoc.did
        } catch (e: Exception) {
            logger.warn("Slingshot resolution failed, trying fallback: ${e.message}")
        }

        // Fallback to standard XRPC resolution
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$fallbackPdsUrl/xrpc/com.atproto.identity.resolveHandle?handle=$handle"))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Failed to resolve handle: HTTP ${response.statusCode()}")
        }

        val resolution = json.decodeFromString<HandleResolution>(response.body())
        logger.info("Resolved handle $handle to DID: ${resolution.did}")
        resolution.did
    }

    /**
     * Resolves a DID to its DID Document.
     * Supports did:plc and did:web methods.
     */
    suspend fun resolveDid(did: String): Result<DidDocument> = runCatching {
        logger.info("Resolving DID: $did")
        
        val url = when {
            did.startsWith("did:plc:") -> {
                val identifier = did.removePrefix("did:plc:")
                "https://plc.directory/$identifier"
            }
            did.startsWith("did:web:") -> {
                val domain = did.removePrefix("did:web:")
                "https://$domain/.well-known/did.json"
            }
            else -> throw IllegalArgumentException("Unsupported DID method: $did")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Failed to resolve DID: HTTP ${response.statusCode()}")
        }

        val didDoc = json.decodeFromString<DidDocument>(response.body())
        logger.info("Resolved DID $did successfully")
        didDoc
    }

    /**
     * Gets a profile from the AT Protocol network.
     * Uses Slingshot for PDS resolution if needed.
     */
    suspend fun getProfile(actor: String, pdsUrl: String? = null): Result<ProfileView> = runCatching {
        logger.info("Fetching profile for: $actor")
        
        val serviceUrl = pdsUrl ?: run {
            // Resolve PDS if not provided
            try {
                val miniDoc = resolveMiniDoc(actor).getOrThrow()
                miniDoc.pds
            } catch (e: Exception) {
                logger.warn("Could not resolve PDS, using fallback: ${e.message}")
                fallbackPdsUrl
            }
        }
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$serviceUrl/xrpc/app.bsky.actor.getProfile?actor=$actor"))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Failed to get profile: HTTP ${response.statusCode()}: ${response.body()}")
        }

        val profile = json.decodeFromString<ProfileView>(response.body())
        logger.info("Retrieved profile for ${profile.handle} (${profile.did})")
        profile
    }

    /**
     * Creates an authenticated session using identifier and app password.
     * This is the primary authentication method for the mod.
     */
    suspend fun createSession(identifier: String, password: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Creating session for: $identifier")
        
        // Resolve to find the correct PDS
        val pdsUrl = try {
            val miniDoc = resolveMiniDoc(identifier).getOrThrow()
            miniDoc.pds
        } catch (e: Exception) {
            logger.warn("Could not resolve PDS via Slingshot, trying fallback: ${e.message}")
            fallbackPdsUrl
        }
        
        val requestBody = CreateSessionRequest(
            identifier = identifier,
            password = password
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$pdsUrl/xrpc/com.atproto.server.createSession"))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(CreateSessionRequest.serializer(), requestBody)))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            val errorBody = response.body()
            logger.error("Session creation failed: HTTP ${response.statusCode()}: $errorBody")
            throw Exception("Failed to create session: HTTP ${response.statusCode()}: $errorBody")
        }

        val session = json.decodeFromString<CreateSessionResponse>(response.body())
        logger.info("Session created successfully for ${session.handle}")
        session
    }

    /**
     * Refreshes an existing session using a refresh token.
     */
    suspend fun refreshSession(refreshJwt: String, pdsUrl: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Refreshing session")
        
        val requestBody = RefreshSessionRequest(refreshJwt = refreshJwt)
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$pdsUrl/xrpc/com.atproto.server.refreshSession"))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(RefreshSessionRequest.serializer(), requestBody)))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $refreshJwt")
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Failed to refresh session: HTTP ${response.statusCode()}")
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
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$pdsUrl/xrpc/$endpoint"))
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
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw Exception("XRPC request failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        response.body()
    }

    /**
     * Validates that a DID is properly formatted.
     */
    fun isValidDid(did: String): Boolean {
        return did.matches(Regex("^did:(plc|web):[a-zA-Z0-9._:%-]+$"))
    }

    /**
     * Validates that a handle is properly formatted.
     */
    fun isValidHandle(handle: String): Boolean {
        return handle.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))
    }

    /**
     * Determines if the input is a DID or handle and resolves accordingly.
     * Returns (DID, handle, PDS URL).
     */
    suspend fun resolveIdentifier(identifier: String): Result<Triple<String, String, String>> = runCatching {
        when {
            identifier.startsWith("did:") -> {
                if (!isValidDid(identifier)) {
                    throw IllegalArgumentException("Invalid DID format")
                }
                // Try Slingshot first
                try {
                    val miniDoc = resolveMiniDoc(identifier).getOrThrow()
                    Triple(miniDoc.did, miniDoc.handle, miniDoc.pds)
                } catch (e: Exception) {
                    // Fallback to DID resolution
                    val didDoc = resolveDid(identifier).getOrThrow()
                    val handle = didDoc.alsoKnownAs.firstOrNull()
                        ?.removePrefix("at://")
                        ?: throw Exception("No handle found in DID document")
                    val pdsService = didDoc.service.firstOrNull { it.type == "AtprotoPersonalDataServer" }
                        ?: throw Exception("No PDS service found in DID document")
                    Triple(identifier, handle, pdsService.serviceEndpoint)
                }
            }
            else -> {
                if (!isValidHandle(identifier)) {
                    throw IllegalArgumentException("Invalid handle format")
                }
                val miniDoc = resolveMiniDoc(identifier).getOrThrow()
                Triple(miniDoc.did, miniDoc.handle, miniDoc.pds)
            }
        }
    }

    private fun encodeURIComponent(value: String): String {
        return URI(null, null, null, -1, null, null, null)
            .resolve(value)
            .rawSchemeSpecificPart
            .replace("+", "%20")
    }
}
