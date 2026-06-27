package com.jollywhoppers.atproto.client

import io.github.kikin81.atproto.com.atproto.identity.IdentityService
import io.github.kikin81.atproto.com.atproto.identity.ResolveHandleRequest
import io.github.kikin81.atproto.com.atproto.server.CreateSessionRequest
import io.github.kikin81.atproto.com.atproto.server.CreateSessionResponse
import io.github.kikin81.atproto.com.atproto.server.RefreshSessionRequest
import io.github.kikin81.atproto.com.atproto.server.ServerService
import io.github.kikin81.atproto.runtime.BearerTokenAuth
import io.github.kikin81.atproto.runtime.NoAuth
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder

class ClientAtProtoClient(
    private val identityServiceUrl: String = "https://bsky.social"
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

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

    data class ExtendedSessionResponse(
        val did: String,
        val handle: String,
        val email: String?,
        val accessJwt: String,
        val refreshJwt: String,
        val pdsUrl: String
    )

    suspend fun resolveHandle(handle: String): Result<String> = runCatching {
        logger.info("Resolving handle via official endpoint: ${sanitize(handle)}")
        val xrpcClient = XrpcClient(identityServiceUrl, httpClient, json, NoAuth)
        val response = IdentityService(xrpcClient).resolveHandle(ResolveHandleRequest(handle))
        logger.info("Resolved handle to DID: ${sanitize(response.did)}")
        response.did
    }

    suspend fun resolveDID(did: String): Result<String> = runCatching {
        logger.info("Resolving DID document: ${sanitize(did)}")
        when {
            did.startsWith("did:plc:") -> {
                val url = "https://plc.directory/$did"
                val response = httpClient.get(url)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    throw Exception("DID resolution failed with status ${response.status.value}")
                }
                val didDoc = json.decodeFromString<DIDDocument>(body)
                val pdsService = didDoc.service?.find { it.type == "AtprotoPersonalDataServer" }
                    ?: throw Exception("No PDS service found in DID document")
                logger.info("Found PDS: ${pdsService.serviceEndpoint}")
                pdsService.serviceEndpoint
            }
            did.startsWith("did:web:") -> {
                val domain = did.removePrefix("did:web:")
                val url = "https://$domain/.well-known/did.json"
                val response = httpClient.get(url)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    throw Exception("DID resolution failed with status ${response.status.value}")
                }
                val didDoc = json.decodeFromString<DIDDocument>(body)
                val pdsService = didDoc.service?.find { it.type == "AtprotoPersonalDataServer" }
                    ?: throw Exception("No PDS service found in DID document")
                logger.info("Found PDS: ${pdsService.serviceEndpoint}")
                pdsService.serviceEndpoint
            }
            else -> throw Exception("Unsupported DID method")
        }
    }

    suspend fun createSession(identifier: String, password: String): Result<ExtendedSessionResponse> = runCatching {
        logger.info("Creating session for: ${sanitize(identifier)}")

        val pdsUrl = if (identifier.startsWith("did:")) {
            resolveDID(identifier).getOrElse {
                logger.warn("Could not resolve DID, using fallback PDS")
                identityServiceUrl
            }
        } else {
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
        val xrpcClient = XrpcClient(pdsUrl, httpClient, json, NoAuth)
        val sessionResponse = ServerService(xrpcClient).createSession(CreateSessionRequest(identifier, password))
        logger.info("Session created successfully")
        ExtendedSessionResponse(
            did = sessionResponse.did,
            handle = sessionResponse.handle,
            email = sessionResponse.email,
            accessJwt = sessionResponse.accessJwt,
            refreshJwt = sessionResponse.refreshJwt,
            pdsUrl = pdsUrl
        )
    }

    suspend fun refreshSession(refreshJwt: String, pdsUrl: String): Result<CreateSessionResponse> = runCatching {
        logger.info("Refreshing session")
        val xrpcClient = XrpcClient(pdsUrl, httpClient, json, BearerTokenAuth(refreshJwt))
        ServerService(xrpcClient).refreshSession(RefreshSessionRequest(refreshJwt))
    }

    suspend fun xrpcRequest(
        method: String,
        endpoint: String,
        accessJwt: String,
        pdsUrl: String,
        body: String? = null
    ): Result<String> = runCatching {
        val url = "$pdsUrl/xrpc/$endpoint"

        val response = when (method.uppercase()) {
            "GET" -> httpClient.get(url) {
                header("Authorization", "Bearer $accessJwt")
                header("Content-Type", "application/json")
            }
            "POST" -> httpClient.post(url) {
                header("Authorization", "Bearer $accessJwt")
                contentType(ContentType.Application.Json)
                setBody(body ?: "{}")
            }
            "PUT" -> httpClient.put(url) {
                header("Authorization", "Bearer $accessJwt")
                contentType(ContentType.Application.Json)
                setBody(body ?: "{}")
            }
            "DELETE" -> httpClient.delete(url) {
                header("Authorization", "Bearer $accessJwt")
                header("Content-Type", "application/json")
            }
            else -> throw IllegalArgumentException("Unsupported HTTP method")
        }

        val responseBody = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw Exception("Request failed with status ${response.status.value}")
        }
        responseBody
    }

    suspend fun xrpcRequestWithDpop(
        method: String,
        endpoint: String,
        accessJwt: String,
        pdsUrl: String,
        body: String? = null,
        dpopProof: String,
    ): Result<String> = runCatching {
        val url = "$pdsUrl/xrpc/$endpoint"

        val response = when (method.uppercase()) {
            "GET" -> httpClient.get(url) {
                header("Authorization", "DPoP $accessJwt")
                header("DPoP", dpopProof)
                header("Content-Type", "application/json")
            }
            "POST" -> httpClient.post(url) {
                header("Authorization", "DPoP $accessJwt")
                header("DPoP", dpopProof)
                contentType(ContentType.Application.Json)
                setBody(body ?: "{}")
            }
            "PUT" -> httpClient.put(url) {
                header("Authorization", "DPoP $accessJwt")
                header("DPoP", dpopProof)
                contentType(ContentType.Application.Json)
                setBody(body ?: "{}")
            }
            "DELETE" -> httpClient.delete(url) {
                header("Authorization", "DPoP $accessJwt")
                header("DPoP", dpopProof)
                header("Content-Type", "application/json")
            }
            else -> throw IllegalArgumentException("Unsupported HTTP method")
        }

        val responseBody = response.bodyAsText()

        if (response.status.value == 401) {
            val dpopNonce = response.headers["DPoP-Nonce"]
            if (dpopNonce != null) {
                throw DpopNonceRequiredException(dpopNonce, url)
            }
        }

        if (response.status.value !in 200..299) {
            throw Exception("Request failed with status ${response.status.value}")
        }

        responseBody
    }

    class DpopNonceRequiredException(
        val nonce: String,
        val url: String,
    ) : Exception("DPoP nonce required from server")

    private fun encodeURIComponent(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
    }

    private fun sanitize(input: String): String {
        return when {
            input.length <= 8 -> "***"
            else -> "${input.take(4)}...${input.takeLast(4)}"
        }
    }
}
