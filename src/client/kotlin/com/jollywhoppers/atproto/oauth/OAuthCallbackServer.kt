package com.jollywhoppers.atproto.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Lightweight localhost HTTP server that captures the OAuth redirect callback.
 *
 * ATProto OAuth supports `http://localhost` as a client_id for development
 * and desktop apps. This server listens on a random available port, captures
 * the authorization code from the redirect, and returns a simple HTML page
 * telling the user to return to Minecraft.
 *
 * Lifecycle:
 * 1. Start the server before opening the browser
 * 2. User authenticates in browser → browser redirects here with ?code=...
 * 3. Server captures the code and stops
 * 4. Caller retrieves the code via [awaitCode]
 */
class OAuthCallbackServer {
    private val logger = LoggerFactory.getLogger("atproto-connect:oauth-callback")
    private var server: HttpServer? = null
    private var port: Int = 0
    private val codeFuture = CompletableFuture<String>()
    private val errorFuture = CompletableFuture<String>()

    /**
     * Starts the callback server on a random available port.
     * @return The port the server is listening on
     */
    fun start(): Int {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/oauth/callback") { exchange -> handleCallback(exchange) }
        httpServer.createContext("/") { exchange -> handleRoot(exchange) }
        httpServer.executor = null // use calling thread (simple enough for a one-shot server)
        httpServer.start()

        server = httpServer
        port = httpServer.address.port
        logger.info("OAuth callback server started on http://127.0.0.1:$port")
        return port
    }

    /**
     * The redirect URI that should be registered with the OAuth flow.
     */
    fun getRedirectUri(): String = "http://127.0.0.1:$port/oauth/callback"

    /**
     * Waits for the authorization code from the callback.
     * @param timeoutSeconds Maximum time to wait
     * @return The authorization code, or null on timeout
     */
    fun awaitCode(timeoutSeconds: Long = 300): String? {
        return try {
            codeFuture.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            logger.warn("OAuth callback timed out after ${timeoutSeconds}s")
            null
        } catch (e: Exception) {
            logger.error("Error waiting for OAuth callback", e)
            null
        }
    }

    /**
     * Checks if an OAuth error was received instead of a code.
     */
    fun awaitError(timeoutSeconds: Long = 300): String? {
        return try {
            errorFuture.get(1, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stops the callback server.
     */
    fun stop() {
        server?.stop(0)
        server = null
        logger.info("OAuth callback server stopped")
    }

    /**
     * Handles the OAuth redirect callback.
     * Extracts the authorization code or error from query parameters.
     */
    private fun handleCallback(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val params = parseQuery(query)

        val code = params["code"]
        val error = params["error"]
        val errorDescription = params["error_description"]

        val responseHtml = when {
            code != null -> {
                codeFuture.complete(code)
                """
                <!DOCTYPE html>
                <html>
                <head><title>SocialSync - Authorised</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:3rem;">
                    <h1 style="color:#4CAF50;">Authorised!</h1>
                    <p>You can close this tab and return to Minecraft.</p>
                </body>
                </html>
                """.trimIndent()
            }
            error != null -> {
                val message = errorDescription ?: error
                errorFuture.complete(message)
                """
                <!DOCTYPE html>
                <html>
                <head><title>SocialSync - Authorisation Failed</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:3rem;">
                    <h1 style="color:#F44336;">Authorisation Failed</h1>
                    <p>$message</p>
                    <p>You can close this tab and return to Minecraft.</p>
                </body>
                </html>
                """.trimIndent()
            }
            else -> {
                """
                <!DOCTYPE html>
                <html>
                <head><title>SocialSync - Error</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:3rem;">
                    <h1 style="color:#F44336;">Unexpected Response</h1>
                    <p>No authorisation code or error received.</p>
                </body>
                </html>
                """.trimIndent()
            }
        }

        val responseBytes = responseHtml.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.write(responseBytes)
        exchange.responseBody.close()

        // Stop the server after handling the callback
        stop()
    }

    /**
     * Handles requests to the root path with a simple status page.
     */
    private fun handleRoot(exchange: HttpExchange) {
        val response = """
        <!DOCTYPE html>
        <html>
        <head><title>SocialSync OAuth</title></head>
        <body style="font-family:sans-serif;text-align:center;padding:3rem;">
            <h1>SocialSync OAuth Callback</h1>
            <p>Waiting for authorisation redirect...</p>
        </body>
        </html>
        """.trimIndent()

        val responseBytes = response.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.write(responseBytes)
        exchange.responseBody.close()
    }

    /**
     * Parses URL query parameters into a map.
     */
    private fun parseQuery(query: String): Map<String, String> {
        return query.split("&")
            .filter { it.contains("=") }
            .associate {
                val (key, value) = it.split("=", limit = 2)
                key to java.net.URLDecoder.decode(value, Charsets.UTF_8)
            }
    }
}
