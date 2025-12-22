package com.jollywhoppers.atproto.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client-side AT Protocol client using OFFICIAL Bluesky identity resolution.
 * Handles identity resolution, authentication, and XRPC requests directly from the client.
 * 
 * SECURITY: All authentication happens on the client - passwords never leave the player's computer.
 */
class ClientAtProtoClient(
    private val identityServiceUrl: String = "https://bsky.social"
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
    data class ResolveHandleResponse(
        val did: String
    )

    @Serializable
    data class DIDDocument(
        val id: String,
        val alsoKnownAs: List<String>? = null,
        val verificationMethod: List<VerificationMethod>? = null,
        val service: List<Service>? = null
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

    // Extended response that includes the PDS URL
    data class ExtendedSessionResponse(
        val did: String,
        val handle: String,
        val email: String?,
        val accessJwt: String,
        val refreshJwt: String,
        val pdsUrl: String
    )

    @Serializable
    data class RefreshSessionRequest(
        val refreshJwt: String
    )

    @Serializable
    data class ErrorResponse(
        val error: String? = null,
        val message: String? = null
    )

    /**
     * Resolves a handle to a DID using the OFFICIAL Bluesky identity resolution endpoint.
     */
    suspend fun resolveHandle(handle: String): Result<String> = runCatching {
        logger.info("Resolving handle via official endpoint: ${sanitize(handle)}")
        
        // OFFICIAL: Use com.atproto.identity.resolveHandle on bsky.social
        val url = "$identityServiceUrl/xrpc/com.atproto.identity.resolveHandle?handle=${encodeURIComponent(handle)}"
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Handle resolution failed with status ${response.statusCode()}")
        }

        val resolveResponse = json.decodeFromString<ResolveHandleResponse>(response.body())
        logger.info("Resolved handle to DID: ${sanitize(resolveResponse.did)}")
        resolveResponse.did
    }

    /**
     * Resolves a DID to its DID Document to find the PDS endpoint.
     */
    suspend fun resolveDID(did: String): Result<String> = runCatching {
        logger.info("Resolving DID document: ${sanitize(did)}")
        
        // OFFICIAL: Resolve DID document
        when {
            did.startsWith("did:plc:") -> {
                // PLC directory
                val url = "https://plc.directory/$did"
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() != 200) {
                    throw Exception("DID resolution failed with status ${response.statusCode()}")
                }

                val didDoc = json.decodeFromString<DIDDocument>(response.body())
                
                // Find the PDS service endpoint
                val pdsService = didDoc.service?.find { it.type == "AtprotoPersonalDataServer" }
                    ?: throw Exception("No PDS service found in DID document")
                
                logger.info("Found PDS: ${pdsService.serviceEndpoint}")
                pdsService.serviceEndpoint
            }
            did.startsWith("did:web:") -> {
                // did:web resolution
                val domain = did.removePrefix("did:web:")
                val url = "https://$domain/.well-known/did.json"
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() != 200) {
                    throw Exception("DID resolution failed with status ${response.statusCode()}")
                }

                val didDoc = json.decodeFromString<DIDDocument>(response.body())
                
                val pdsService = didDoc.service?.find { it.type == "AtprotoPersonalDataServer" }
                    ?: throw Exception("No PDS service found in DID document")
                
                logger.info("Found PDS: ${pdsService.serviceEndpoint}")
                pdsService.serviceEndpoint
            }
            else -> throw Exception("Unsupported DID method")
        }
    }

    /**
     * Creates an authenticated session.
     * This uses OFFICIAL identity resolution to find the user's PDS, then authenticates there.
     * Password is never sent to your Minecraft server - only to AT Protocol servers.
     */
    suspend fun createSession(identifier: String, password: String): Result<ExtendedSessionResponse> = runCatching {
        logger.info("Creating session for: ${sanitize(identifier)}")
        
        // Step 1: Resolve identifier to find PDS
        val pdsUrl = if (identifier.startsWith("did:")) {
            // Already a DID, resolve directly
            resolveDID(identifier).getOrElse {
                logger.warn("Could not resolve DID, using fallback PDS")
                identityServiceUrl
            }
        } else {
            // It's a handle, resolve to DID first, then to PDS
            try {
                val did = resolveHandle(identifier).getOrThrow()
                resolveDID(did).getOrElse {
                    logger.warn("Could not resolve PDS from DID, using fallback")
                    identityServiceUrl
                }
            } catch (e: Exception) {
                logger.warn("Could not resolve identity, using fallback PDS: ${e.message}")
                identityServiceUrl
            }
        }
        
        logger.info("Authenticating to PDS: $pdsUrl")
        
        // Step 2: Authenticate to the discovered PDS
        val requestBody = CreateSessionRequest(
            identifier = identifier,
            password = password
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
            logger.error("Response body: ${response.body()}")
            
            // Try to parse error response
            val errorMessage = try {
                val errorResponse = json.decodeFromString<ErrorResponse>(response.body())
                errorResponse.message ?: errorResponse.error ?: "Authentication failed with status ${response.statusCode()}"
            } catch (e: Exception) {
                "Authentication failed with status ${response.statusCode()}"
            }
            
            throw Exception(errorMessage)
        }

        val sessionResponse = json.decodeFromString<CreateSessionResponse>(response.body())
        logger.info("Session created successfully")
        
        // Return extended response with PDS URL
        ExtendedSessionResponse(
            did = sessionResponse.did,
            handle = sessionResponse.handle,
            email = sessionResponse.email,
            accessJwt = sessionResponse.accessJwt,
            refreshJwt = sessionResponse.refreshJwt,
            pdsUrl = pdsUrl
        )
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
