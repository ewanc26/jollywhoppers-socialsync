package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityUtils
import com.jollywhoppers.security.SecurityAuditor
import io.github.kikin81.atproto.app.bsky.actor.ActorService
import io.github.kikin81.atproto.app.bsky.actor.GetProfileRequest
import io.github.kikin81.atproto.com.atproto.identity.IdentityService
import io.github.kikin81.atproto.com.atproto.identity.ResolveHandleRequest

import io.github.kikin81.atproto.runtime.NoAuth
import io.github.kikin81.atproto.runtime.XrpcClient
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.Handle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI

class AtProtoClient(
    private val slingshotUrl: String = "https://slingshot.microcosm.blue",
    private val fallbackPdsUrl: String = "https://bsky.social",
    private val publicClient: XrpcClient? = null
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val ktorClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    val xrpcClient: XrpcClient = publicClient ?: XrpcClient(
        baseUrl = fallbackPdsUrl,
        httpClient = ktorClient,
        authProvider = NoAuth
    )

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
    data class CreateSessionRequest(
        val identifier: String,
        val password: String
    )

    @Serializable
    data class AtpRefreshSessionRequest(
        val refreshJwt: String
    )

    suspend fun resolveMiniDoc(identifier: String): Result<MiniDoc> = runCatching {
        logger.info("Resolving identifier via Slingshot: ${SecurityUtils.sanitizeForLog(identifier)}")

        val url = "$slingshotUrl/xrpc/com.bad-example.identity.resolveMiniDoc?identifier=${encodeURIComponent(identifier)}"
        validateUrl(url)

        val response = ktorClient.get(url) {
            header("Content-Type", "application/json")
        }
        val body = response.bodyAsText()

        if (response.status.value != 200) {
            throw Exception("Resolution failed")
        }

        val miniDoc = json.decodeFromString<MiniDoc>(body)

        validateUrl(miniDoc.pds)

        logger.info("Resolved ${miniDoc.handle} -> PDS: ${miniDoc.pds}")
        miniDoc
    }

    suspend fun resolveHandle(handle: String): Result<String> = runCatching {
        logger.info("Resolving handle: ${SecurityUtils.sanitizeForLog(handle)}")

        try {
            val miniDoc = resolveMiniDoc(handle).getOrThrow()
            return@runCatching miniDoc.did
        } catch (e: Exception) {
            logger.warn("Slingshot resolution failed, trying fallback")
        }

        val resolution = IdentityService(xrpcClient).resolveHandle(ResolveHandleRequest(handle = Handle(handle)))
        logger.info("Resolved handle to DID")
        resolution.did.toString()
    }

    suspend fun resolveDid(did: String): Result<DidDocument> = runCatching {
        logger.info("Resolving DID: ${SecurityUtils.sanitizeForLog(did)}")

        val url = when {
            did.startsWith("did:plc:") -> {
                val identifier = did.removePrefix("did:plc:")
                "https://plc.directory/$identifier"
            }
            did.startsWith("did:web:") -> {
                val domain = did.removePrefix("did:web:")

                if (!domain.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))) {
                    throw IllegalArgumentException("Invalid did:web domain format")
                }

                validateNotPrivateNetwork(domain)

                validateDnsResolution(domain)

                "https://$domain/.well-known/did.json"
            }
            else -> throw IllegalArgumentException("Unsupported DID method")
        }

        val response = ktorClient.get(url) {
            header("Content-Type", "application/json")
        }

        if (response.status.value != 200) {
            throw Exception("DID resolution failed")
        }

        val didDoc = json.decodeFromString<DidDocument>(response.bodyAsText())
        logger.info("Resolved DID successfully")
        didDoc
    }

    suspend fun getProfile(actor: String, pdsUrl: String? = null): Result<ProfileView> = runCatching {
        logger.info("Fetching profile for: ${SecurityUtils.sanitizeForLog(actor)}")

        val serviceUrl = pdsUrl ?: run {
            try {
                val miniDoc = resolveMiniDoc(actor).getOrThrow()
                miniDoc.pds
            } catch (e: Exception) {
                logger.warn("Could not resolve PDS, using fallback")
                fallbackPdsUrl
            }
        }

        val profileClient = if (serviceUrl == fallbackPdsUrl) {
            xrpcClient
        } else {
            XrpcClient(
                baseUrl = serviceUrl,
                httpClient = ktorClient,
                authProvider = NoAuth
            )
        }

        val libProfile = ActorService(profileClient).getProfile(GetProfileRequest(actor = AtIdentifier(actor)))
        val profile = ProfileView(
            did = libProfile.did.toString(),
            handle = libProfile.handle.toString(),
            displayName = libProfile.displayName,
            description = libProfile.description,
            avatar = libProfile.avatar?.toString()
        )
        logger.info("Retrieved profile successfully")
        profile
    }

    suspend fun refreshSession(refreshJwt: String, pdsUrl: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Refreshing session")

        val url = "$pdsUrl/xrpc/com.atproto.server.refreshSession"
        validateUrl(url)

        val bodyJson = json.encodeToString(AtpRefreshSessionRequest.serializer(), AtpRefreshSessionRequest(refreshJwt = refreshJwt))
        val response = ktorClient.post(url) {
            header("Authorization", "Bearer $refreshJwt")
            header("Content-Type", "application/json")
            setBody(bodyJson)
        }
        val responseBody = response.bodyAsText()

        if (response.status.value != 200) {
            throw Exception("Session refresh failed")
        }

        json.decodeFromString<CreateSessionResponse>(responseBody)
    }

    suspend fun createSession(identifier: String, password: String): Result<CreateSessionResponse> = runCatching {
        val url = "$fallbackPdsUrl/xrpc/com.atproto.server.createSession"
        validateUrl(url)

        val bodyJson = json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(identifier = identifier, password = password))
        val response = ktorClient.post(url) {
            header("Content-Type", "application/json")
            setBody(bodyJson)
        }
        val responseBody = response.bodyAsText()

        if (response.status.value != 200) {
            throw Exception("Session creation failed")
        }

        json.decodeFromString<CreateSessionResponse>(responseBody)
    }

    suspend fun fetchRecord(did: String, collection: String, rkey: String): Result<JsonElement> = runCatching {
        val url = "$fallbackPdsUrl/xrpc/com.atproto.repo.getRecord?repo=${encodeURIComponent(did)}&collection=${encodeURIComponent(collection)}&rkey=${encodeURIComponent(rkey)}"
        validateUrl(url)
        val response = ktorClient.get(url) {
            header("Content-Type", "application/json")
        }
        val body = response.bodyAsText()
        if (response.status.value != 200) {
            throw Exception("Record fetch failed")
        }
        val jsonObj = json.decodeFromString<JsonObject>(body)
        jsonObj["value"] ?: throw Exception("No value in record response")
    }

    suspend fun xrpcRequest(
        method: String,
        endpoint: String,
        accessJwt: String,
        pdsUrl: String,
        body: String? = null
    ): Result<String> = runCatching {
        val url = "$pdsUrl/xrpc/$endpoint"
        validateUrl(url)
        when (method.uppercase()) {
            "GET" -> ktorClient.get(url) { header("Authorization", "Bearer $accessJwt") }.bodyAsText()
            "POST" -> ktorClient.post(url) {
                header("Authorization", "Bearer $accessJwt")
                header("Content-Type", "application/json")
                setBody(body ?: "{}")
            }.bodyAsText()
            "PUT" -> ktorClient.put(url) {
                header("Authorization", "Bearer $accessJwt")
                header("Content-Type", "application/json")
                setBody(body ?: "{}")
            }.bodyAsText()
            "DELETE" -> ktorClient.delete(url) { header("Authorization", "Bearer $accessJwt") }.bodyAsText()
            else -> throw IllegalArgumentException("Unsupported HTTP method")
        }
    }

    fun isValidDid(did: String): Boolean {
        return did.matches(Regex("^did:(plc|web):[a-zA-Z0-9._:%-]+$"))
    }

    fun isValidHandle(handle: String): Boolean {
        return handle.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"))
    }

    suspend fun resolveIdentifier(identifier: String): Result<Triple<String, String, String>> = runCatching {
        when {
            identifier.startsWith("did:") -> {
                if (!isValidDid(identifier)) {
                    throw IllegalArgumentException("Invalid DID format")
                }
                try {
                    val miniDoc = resolveMiniDoc(identifier).getOrThrow()
                    Triple(miniDoc.did, miniDoc.handle, miniDoc.pds)
                } catch (e: Exception) {
                    val didDoc = resolveDid(identifier).getOrThrow()
                    val handle = didDoc.alsoKnownAs.firstOrNull()
                        ?.removePrefix("at://")
                        ?: throw Exception("No handle found in DID document")
                    val pdsService = didDoc.service.firstOrNull { it.type == "AtprotoPersonalDataServer" }
                        ?: throw Exception("No PDS service found in DID document")

                    val serviceEndpoint = pdsService.serviceEndpoint
                    val uri = try {
                        URI.create(serviceEndpoint)
                    } catch (e: Exception) {
                        throw Exception("Invalid serviceEndpoint URI")
                    }

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

                    validateNotPrivateNetwork(uri.host)
                    validateDnsResolution(uri.host)

                    val cleanPdsUrl = "${uri.scheme}://${uri.host}${uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
                    Triple(identifier, handle, cleanPdsUrl)
                }
            }
            else -> {
                if (!isValidHandle(identifier)) {
                    throw IllegalArgumentException("Invalid handle format")
                }
                try {
                    val miniDoc = resolveMiniDoc(identifier).getOrThrow()
                    Triple(miniDoc.did, miniDoc.handle, miniDoc.pds)
                } catch (e: Exception) {
                    val did = resolveHandle(identifier).getOrThrow()
                    val didDoc = resolveDid(did).getOrThrow()
                    val handle = didDoc.alsoKnownAs.firstOrNull()
                        ?.removePrefix("at://")
                        ?: identifier
                    val pdsService = didDoc.service.firstOrNull { it.type == "AtprotoPersonalDataServer" }
                    val cleanPdsUrl = pdsService?.let { s ->
                        val ep = s.serviceEndpoint
                        val u = URI.create(ep)
                        validateNotPrivateNetwork(u.host)
                        "${u.scheme}://${u.host}${u.port.takeIf { it != -1 }?.let { ":$it" } ?: ""}"
                    } ?: fallbackPdsUrl
                    Triple(did, handle, cleanPdsUrl)
                }
            }
        }
    }

    private fun encodeURIComponent(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
    }

    private fun validateUrl(url: String) {
        try {
            val uri = URI.create(url)

            if (uri.scheme != "https" && uri.host != "localhost") {
                throw IllegalArgumentException("Only HTTPS URLs are allowed")
            }

            require(uri.host != null) { "URL must have a host" }

            validateNotPrivateNetwork(uri.host)

            if (!uri.host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                validateDnsResolution(uri.host)
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL format")
        }
    }

    private fun validateNotPrivateNetwork(host: String) {
        val localhostPatterns = listOf(
            Regex("^localhost$", RegexOption.IGNORE_CASE),
            Regex("^127\\..*"),
            Regex("^::1$"),
            Regex("^::ffff:127\\..*"),
            Regex("^0:0:0:0:0:0:0:1$")
        )

        val privateIPv4 = listOf(
            Regex("^10\\..*"),
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"),
            Regex("^192\\.168\\..*"),
            Regex("^169\\.254\\..*")
        )

        val privateIPv6 = listOf(
            Regex("^fc00:.*", RegexOption.IGNORE_CASE),
            Regex("^fd[0-9a-f]{2}:.*", RegexOption.IGNORE_CASE),
            Regex("^fe80:.*", RegexOption.IGNORE_CASE)
        )

        val cloudMetadata = listOf(
            Regex("^169\\.254\\.169\\.254$"),
            Regex("^metadata\\.google\\.internal$", RegexOption.IGNORE_CASE),
            Regex("^100\\.100\\.100\\.200$")
        )

        val allPatterns = localhostPatterns + privateIPv4 + privateIPv6 + cloudMetadata

        if (allPatterns.any { it.containsMatchIn(host) }) {
            SecurityAuditor.logSuspiciousActivity(null, "Blocked access to private network: $host")
            throw IllegalArgumentException("Access to private networks is not allowed")
        }
    }

    private fun validateDnsResolution(hostname: String) {
        try {
            val addresses = InetAddress.getAllByName(hostname)

            addresses.forEach { addr ->
                val hostAddress = addr.hostAddress

                if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress || addr.isAnyLocalAddress
                ) {
                    SecurityAuditor.logSuspiciousActivity(null, "DNS resolved to private address: $hostAddress for $hostname")
                    throw IllegalArgumentException("DNS resolved to private address")
                }

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
