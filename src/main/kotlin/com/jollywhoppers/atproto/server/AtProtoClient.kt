package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityUtils
import com.jollywhoppers.security.SecurityAuditor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * Enhanced AT Protocol client with PDS resolution via Slingshot.
 * Handles identity resolution, authentication, and XRPC requests.
 * 
 * SECURITY FEATURES:
 * - SSRF protection with comprehensive private network blocking
 * - DNS resolution validation
 * - No automatic HTTP redirects (prevents redirect-based SSRF)
 * - Request timeouts on all HTTP operations
 * - Sanitized error messages
 */
class AtProtoClient(
    private val slingshotUrl: String = "https://slingshot.microcosm.blue",
    private val fallbackPdsUrl: String = "https://bsky.social"
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    
    // HTTP client with security hardening
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER) // Disabled for security - prevents redirect-based SSRF
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
        val signing_key: String
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
        logger.info("Resolving identifier via Slingshot: ${SecurityUtils.sanitizeForLog(identifier)}")
        
        // Validate URL before making request
        val url = "$slingshotUrl/xrpc/com.bad-example.identity.resolveMiniDoc?identifier=${encodeURIComponent(identifier)}"
        validateUrl(url)
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Resolution failed")
        }

        val miniDoc = json.decodeFromString<MiniDoc>(response.body())
        
        // Validate PDS URL
        validateUrl(miniDoc.pds)
        
        logger.info("Resolved ${miniDoc.handle} -> PDS: ${miniDoc.pds}")
        miniDoc
    }

    /**
     * Resolves an AT Protocol handle to a DID.
     * Falls back to standard resolution if Slingshot fails.
     */
    suspend fun resolveHandle(handle: String): Result<String> = runCatching {
        logger.info("Resolving handle: ${SecurityUtils.sanitizeForLog(handle)}")
        
        // Try Slingshot's MiniDoc first
        try {
            val miniDoc = resolveMiniDoc(handle).getOrThrow()
            return@runCatching miniDoc.did
        } catch (e: Exception) {
            logger.warn("Slingshot resolution failed, trying fallback")
        }

        // Fallback to standard XRPC resolution
        val url = "$fallbackPdsUrl/xrpc/com.atproto.identity.resolveHandle?handle=$handle"
        validateUrl(url)
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Handle resolution failed")
        }

        val resolution = json.decodeFromString<HandleResolution>(response.body())
        logger.info("Resolved handle to DID")
        resolution.did
    }

    /**
     * Resolves a DID to its DID Document.
     * Supports did:plc and did:web methods.
     */
    suspend fun resolveDid(did: String): Result<DidDocument> = runCatching {
        logger.info("Resolving DID: ${SecurityUtils.sanitizeForLog(did)}")

        val url = when {
            did.startsWith("did:plc:") -> {
                val identifier = did.removePrefix("did:plc:")
                "https://plc.directory/$identifier"
            }
            did.startsWith("did:web:") -> {
                val domain = did.removePrefix("did:web:")

                // Validate domain format (no IPs, only valid hostnames)
                if (!domain.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))) {
                    throw IllegalArgumentException("Invalid did:web domain format")
                }

                // Block private IP ranges and localhost
                validateNotPrivateNetwork(domain)
                
                // Validate DNS resolution
                validateDnsResolution(domain)

                "https://$domain/.well-known/did.json"
            }
            else -> throw IllegalArgumentException("Unsupported DID method")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("DID resolution failed")
        }

        val didDoc = json.decodeFromString<DidDocument>(response.body())
        logger.info("Resolved DID successfully")
        didDoc
    }

    /**
     * Gets a profile from the AT Protocol network.
     * Uses Slingshot for PDS resolution if needed.
     */
    suspend fun getProfile(actor: String, pdsUrl: String? = null): Result<ProfileView> = runCatching {
        logger.info("Fetching profile for: ${SecurityUtils.sanitizeForLog(actor)}")
        
        val serviceUrl = pdsUrl ?: run {
            // Resolve PDS if not provided
            try {
                val miniDoc = resolveMiniDoc(actor).getOrThrow()
                miniDoc.pds
            } catch (e: Exception) {
                logger.warn("Could not resolve PDS, using fallback")
                fallbackPdsUrl
            }
        }
        
        val url = "$serviceUrl/xrpc/app.bsky.actor.getProfile?actor=$actor"
        validateUrl(url)
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            throw Exception("Profile fetch failed")
        }

        val profile = json.decodeFromString<ProfileView>(response.body())
        logger.info("Retrieved profile successfully")
        profile
    }

    /**
     * Refreshes an existing session using a refresh token.
     */
    suspend fun refreshSession(refreshJwt: String, pdsUrl: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Refreshing session")
        
        val requestBody = RefreshSessionRequest(refreshJwt = refreshJwt)
        
        val url = "$pdsUrl/xrpc/com.atproto.server.refreshSession"
        validateUrl(url)
        
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
        validateUrl(url)
        
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
            throw Exception("Request failed")
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

                    // Validate serviceEndpoint per AT Protocol spec
                    val serviceEndpoint = pdsService.serviceEndpoint
                    val uri = try {
                        URI.create(serviceEndpoint)
                    } catch (e: Exception) {
                        throw Exception("Invalid serviceEndpoint URI")
                    }

                    // Validate per AT Protocol spec
                    require(uri.scheme in listOf("http", "https")) {
                        "serviceEndpoint must use HTTP or HTTPS scheme"
                    }
                    require(uri.host != null) {
                        "serviceEndpoint must have a valid host"
                    }
                    require(uri.path.isNullOrEmpty() || uri.path == "/") {
                        "serviceEndpoint must not contain path"
                    }
                    require(uri.query == null) {
                        "serviceEndpoint must not contain query parameters"
                    }
                    require(uri.fragment == null) {
                        "serviceEndpoint must not contain fragment"
                    }
                    require(uri.userInfo == null) {
                        "serviceEndpoint must not contain userinfo"
                    }

                    // Block private IP ranges
                    validateNotPrivateNetwork(uri.host)
                    
                    // Validate DNS resolution
                    validateDnsResolution(uri.host)

                    // Reconstruct clean URL
                    val cleanPdsUrl = "${uri.scheme}://${uri.host}${uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
                    Triple(identifier, handle, cleanPdsUrl)
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
        return java.net.URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
    }

    /**
     * Validates a URL before making HTTP requests.
     * Checks for SSRF vulnerabilities.
     */
    private fun validateUrl(url: String) {
        try {
            val uri = URI.create(url)
            
            // Ensure HTTPS (except for localhost during development)
            if (uri.scheme != "https" && uri.host != "localhost") {
                throw IllegalArgumentException("Only HTTPS URLs are allowed")
            }
            
            require(uri.host != null) { "URL must have a host" }
            
            // Validate host is not private network
            validateNotPrivateNetwork(uri.host)
            
            // Validate DNS resolution if it's a hostname
            if (!uri.host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                validateDnsResolution(uri.host)
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL format")
        }
    }

    /**
     * Validates that a hostname or domain is not a private network address.
     * Enhanced with more comprehensive checks.
     */
    private fun validateNotPrivateNetwork(host: String) {
        // Localhost variants
        val localhostPatterns = listOf(
            Regex("^localhost$", RegexOption.IGNORE_CASE),
            Regex("^127\\..*"),
            Regex("^::1$"),
            Regex("^::ffff:127\\..*"), // IPv4-mapped IPv6 localhost
            Regex("^0:0:0:0:0:0:0:1$")
        )
        
        // Private IPv4 ranges
        val privateIPv4 = listOf(
            Regex("^10\\..*"),
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"),
            Regex("^192\\.168\\..*"),
            Regex("^169\\.254\\..*") // Link-local
        )
        
        // Private IPv6 ranges
        val privateIPv6 = listOf(
            Regex("^fc00:.*", RegexOption.IGNORE_CASE),
            Regex("^fd[0-9a-f]{2}:.*", RegexOption.IGNORE_CASE),
            Regex("^fe80:.*", RegexOption.IGNORE_CASE) // Link-local
        )
        
        // Cloud metadata services
        val cloudMetadata = listOf(
            Regex("^169\\.254\\.169\\.254$"), // AWS, Azure, GCP
            Regex("^metadata\\.google\\.internal$", RegexOption.IGNORE_CASE),
            Regex("^100\\.100\\.100\\.200$") // Alibaba Cloud
        )
        
        val allPatterns = localhostPatterns + privateIPv4 + privateIPv6 + cloudMetadata

        if (allPatterns.any { it.containsMatchIn(host) }) {
            SecurityAuditor.logSuspiciousActivity(null, "Blocked access to private network: $host")
            throw IllegalArgumentException("Access to private networks is not allowed")
        }
    }
    
    /**
     * Validates DNS resolution to ensure it doesn't resolve to private IPs.
     * Helps prevent DNS rebinding attacks.
     */
    private fun validateDnsResolution(hostname: String) {
        try {
            val addresses = InetAddress.getAllByName(hostname)
            
            addresses.forEach { addr ->
                val hostAddress = addr.hostAddress
                
                // Check if resolved IP is private
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || 
                    addr.isSiteLocalAddress || addr.isAnyLocalAddress) {
                    SecurityAuditor.logSuspiciousActivity(null, "DNS resolved to private address: $hostAddress for $hostname")
                    throw IllegalArgumentException("DNS resolved to private address")
                }
                
                // Additional string pattern check
                validateNotPrivateNetwork(hostAddress)
            }
        } catch (e: java.net.UnknownHostException) {
            throw IllegalArgumentException("Could not resolve hostname")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("DNS resolution validation failed")
        }
    }
}
