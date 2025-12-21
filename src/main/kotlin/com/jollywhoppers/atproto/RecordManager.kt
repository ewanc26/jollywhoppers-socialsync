package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.security.SecurityUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * Comprehensive record management for AT Protocol repositories.
 * Provides type-safe CRUD operations for Minecraft data records.
 * 
 * Supported operations:
 * - Create: com.atproto.repo.createRecord (generates TID automatically)
 * - Read: com.atproto.repo.getRecord (single record)
 * - List: com.atproto.repo.listRecords (paginated collection listing)
 * - Update: com.atproto.repo.putRecord (update or create with specific rkey)
 * - Delete: com.atproto.repo.deleteRecord
 * 
 * All operations require an authenticated session.
 */
class RecordManager(
    private val sessionManager: AtProtoSessionManager
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:RecordManager")
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    // ============================================================================
    // CREATE OPERATIONS
    // ============================================================================

    /**
     * Creates a new record in the repository with an auto-generated TID.
     * Use this for records that should have unique timestamps (stats, sessions, achievements).
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name (e.g., "com.jollywhoppers.minecraft.player.stats")
     * @param record The record data (must include `$type` field)
     * @param validate Whether to validate the record against the lexicon schema (default: true)
     * @return StrongRef containing the URI and CID of the created record
     */
    suspend fun createRecord(
        playerUuid: UUID,
        collection: String,
        record: JsonElement,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        logger.info("Creating record in collection: $collection for player: ${SecurityUtils.sanitizeForLog(playerUuid.toString())}")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val request = CreateRecordRequest(
            repo = session.did,
            collection = collection,
            record = record,
            validate = validate
        )
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.createRecord",
            body = json.encodeToString(CreateRecordRequest.serializer(), request)
        ).getOrThrow()
        
        val response = json.decodeFromString<CreateRecordResponse>(responseBody)
        logger.info("Record created successfully: ${response.uri}")
        
        StrongRef(uri = response.uri, cid = response.cid)
    }

    /**
     * Creates a typed record with automatic serialization.
     * Convenience method that handles JSON encoding.
     */
    inline fun <reified T : @Serializable Any> createTypedRecord(
        playerUuid: UUID,
        collection: String,
        record: T,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        val jsonElement = json.encodeToJsonElement(record)
        createRecord(playerUuid, collection, jsonElement, validate).getOrThrow()
    }

    // ============================================================================
    // READ OPERATIONS
    // ============================================================================

    /**
     * Retrieves a single record from the repository.
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name
     * @param rkey The record key (TID or literal like "self")
     * @param cid Optional specific version CID
     * @return RecordData containing the URI, value, and CID
     */
    suspend fun getRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        cid: String? = null
    ): Result<RecordData> = runCatching {
        logger.info("Fetching record: $collection/$rkey")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val params = buildString {
            append("repo=${session.did}")
            append("&collection=$collection")
            append("&rkey=$rkey")
            cid?.let { append("&cid=$it") }
        }
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "GET",
            endpoint = "com.atproto.repo.getRecord?$params",
            body = null
        ).getOrThrow()
        
        val response = json.decodeFromString<GetRecordResponse>(responseBody)
        logger.info("Record retrieved successfully")
        
        RecordData(
            uri = response.uri,
            value = response.value,
            cid = response.cid
        )
    }

    /**
     * Retrieves a typed record with automatic deserialization.
     */
    suspend inline fun <reified T : @Serializable Any> getTypedRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        cid: String? = null
    ): Result<TypedRecordData<T>> = runCatching {
        val recordData = getRecord(playerUuid, collection, rkey, cid).getOrThrow()
        TypedRecordData(
            uri = recordData.uri,
            value = json.decodeFromJsonElement(recordData.value),
            cid = recordData.cid
        )
    }

    /**
     * Lists records in a collection with pagination support.
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name
     * @param limit Maximum records to return (1-100, default 50)
     * @param cursor Pagination cursor from previous response
     * @param reverse List in reverse chronological order
     * @return RecordList containing records and optional cursor for next page
     */
    suspend fun listRecords(
        playerUuid: UUID,
        collection: String,
        limit: Int = 50,
        cursor: String? = null,
        reverse: Boolean = false
    ): Result<RecordList> = runCatching {
        require(limit in 1..100) { "Limit must be between 1 and 100" }
        
        logger.info("Listing records from collection: $collection")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val params = buildString {
            append("repo=${session.did}")
            append("&collection=$collection")
            append("&limit=$limit")
            cursor?.let { append("&cursor=$it") }
            if (reverse) append("&reverse=true")
        }
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "GET",
            endpoint = "com.atproto.repo.listRecords?$params",
            body = null
        ).getOrThrow()
        
        val response = json.decodeFromString<ListRecordsResponse>(responseBody)
        logger.info("Retrieved ${response.records.size} records")
        
        RecordList(
            records = response.records.map { 
                RecordData(uri = it.uri, value = it.value, cid = it.cid) 
            },
            cursor = response.cursor
        )
    }

    /**
     * Lists all records in a collection, handling pagination automatically.
     * WARNING: This will fetch ALL records, which could be many requests for large collections.
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name
     * @param batchSize Records per request (1-100, default 50)
     * @param maxRecords Maximum total records to fetch (default: unlimited)
     * @return List of all records
     */
    suspend fun listAllRecords(
        playerUuid: UUID,
        collection: String,
        batchSize: Int = 50,
        maxRecords: Int? = null
    ): Result<List<RecordData>> = runCatching {
        val allRecords = mutableListOf<RecordData>()
        var cursor: String? = null
        var remainingLimit = maxRecords
        
        do {
            val currentLimit = when {
                remainingLimit == null -> batchSize
                remainingLimit < batchSize -> remainingLimit
                else -> batchSize
            }
            
            val result = listRecords(
                playerUuid = playerUuid,
                collection = collection,
                limit = currentLimit,
                cursor = cursor
            ).getOrThrow()
            
            allRecords.addAll(result.records)
            cursor = result.cursor
            
            remainingLimit = remainingLimit?.minus(result.records.size)
            
            if (remainingLimit != null && remainingLimit <= 0) break
        } while (cursor != null)
        
        logger.info("Fetched total of ${allRecords.size} records from $collection")
        allRecords
    }

    // ============================================================================
    // UPDATE OPERATIONS
    // ============================================================================

    /**
     * Updates a record or creates it if it doesn't exist (upsert).
     * Use this for singleton records with literal rkeys like "self" (profile).
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name
     * @param rkey The record key
     * @param record The new record data (must include `$type` field)
     * @param swapRecord Optional CID for compare-and-swap (prevents race conditions)
     * @param swapCommit Optional commit CID for compare-and-swap
     * @param validate Whether to validate the record against the lexicon schema
     * @return StrongRef containing the URI and CID of the updated record
     */
    suspend fun putRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        record: JsonElement,
        swapRecord: String? = null,
        swapCommit: String? = null,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        logger.info("Putting record in collection: $collection with rkey: $rkey")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val request = PutRecordRequest(
            repo = session.did,
            collection = collection,
            rkey = rkey,
            record = record,
            swapRecord = swapRecord,
            swapCommit = swapCommit,
            validate = validate
        )
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.putRecord",
            body = json.encodeToString(PutRecordRequest.serializer(), request)
        ).getOrThrow()
        
        val response = json.decodeFromString<PutRecordResponse>(responseBody)
        logger.info("Record updated successfully: ${response.uri}")
        
        StrongRef(uri = response.uri, cid = response.cid)
    }

    /**
     * Updates a typed record with automatic serialization.
     */
    suspend inline fun <reified T : @Serializable Any> putTypedRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        record: T,
        swapRecord: String? = null,
        swapCommit: String? = null,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        val jsonElement = json.encodeToJsonElement(record)
        putRecord(playerUuid, collection, rkey, jsonElement, swapRecord, swapCommit, validate).getOrThrow()
    }

    // ============================================================================
    // DELETE OPERATIONS
    // ============================================================================

    /**
     * Deletes a record from the repository.
     * 
     * @param playerUuid The player's UUID
     * @param collection The lexicon collection name
     * @param rkey The record key to delete
     * @param swapRecord Optional CID for compare-and-swap (prevents accidental deletion)
     * @param swapCommit Optional commit CID for compare-and-swap
     */
    suspend fun deleteRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        swapRecord: String? = null,
        swapCommit: String? = null
    ): Result<DeleteRecordResponse> = runCatching {
        logger.info("Deleting record: $collection/$rkey")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val request = DeleteRecordRequest(
            repo = session.did,
            collection = collection,
            rkey = rkey,
            swapRecord = swapRecord,
            swapCommit = swapCommit
        )
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.deleteRecord",
            body = json.encodeToString(DeleteRecordRequest.serializer(), request)
        ).getOrThrow()
        
        logger.info("Record deleted successfully")
        
        // Delete endpoint returns empty object or commit info
        json.decodeFromString<DeleteRecordResponse>(responseBody)
    }

    // ============================================================================
    // BATCH OPERATIONS
    // ============================================================================

    /**
     * Applies multiple writes (create, update, delete) in a single atomic transaction.
     * All operations succeed or all fail together.
     * 
     * @param playerUuid The player's UUID
     * @param writes List of write operations to perform
     * @param validate Whether to validate records
     * @param swapCommit Optional commit CID for compare-and-swap
     * @return Commit information for the transaction
     */
    suspend fun applyWrites(
        playerUuid: UUID,
        writes: List<WriteOperation>,
        validate: Boolean = true,
        swapCommit: String? = null
    ): Result<ApplyWritesResponse> = runCatching {
        logger.info("Applying ${writes.size} write operations")
        
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        
        val request = ApplyWritesRequest(
            repo = session.did,
            writes = writes.map { it.toJson() },
            validate = validate,
            swapCommit = swapCommit
        )
        
        val responseBody = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.applyWrites",
            body = json.encodeToString(ApplyWritesRequest.serializer(), request)
        ).getOrThrow()
        
        val response = json.decodeFromString<ApplyWritesResponse>(responseBody)
        logger.info("Writes applied successfully")
        response
    }

    // ============================================================================
    // UTILITY METHODS
    // ============================================================================

    /**
     * Generates a new TID (Timestamp Identifier) for use as a record key.
     * TIDs are sortable timestamps with sub-millisecond precision.
     */
    fun generateTID(): String {
        // TID format: base32-encoded timestamp + clock ID
        val timestamp = Instant.now().toEpochMilli()
        val clockId = Random().nextInt(1024)
        
        // Simplified TID generation (real implementation would use proper base32)
        // For production, use a proper TID library
        return "${timestamp.toString(32)}${clockId.toString(32)}"
    }

    /**
     * Parses an AT URI into its components.
     * Format: at://did/collection/rkey
     */
    fun parseAtUri(uri: String): AtUriComponents? {
        if (!uri.startsWith("at://")) return null
        
        val parts = uri.removePrefix("at://").split("/")
        if (parts.size != 3) return null
        
        return AtUriComponents(
            did = parts[0],
            collection = parts[1],
            rkey = parts[2]
        )
    }

    // ============================================================================
    // DATA CLASSES
    // ============================================================================

    @Serializable
    data class CreateRecordRequest(
        val repo: String,
        val collection: String,
        val record: JsonElement,
        val rkey: String? = null,
        val validate: Boolean? = null,
        val swapCommit: String? = null
    )

    @Serializable
    data class CreateRecordResponse(
        val uri: String,
        val cid: String
    )

    @Serializable
    data class GetRecordResponse(
        val uri: String,
        val cid: String?,
        val value: JsonElement
    )

    @Serializable
    data class ListRecordsResponse(
        val cursor: String? = null,
        val records: List<RecordItem>
    )

    @Serializable
    data class RecordItem(
        val uri: String,
        val cid: String,
        val value: JsonElement
    )

    @Serializable
    data class PutRecordRequest(
        val repo: String,
        val collection: String,
        val rkey: String,
        val record: JsonElement,
        val validate: Boolean? = null,
        val swapRecord: String? = null,
        val swapCommit: String? = null
    )

    @Serializable
    data class PutRecordResponse(
        val uri: String,
        val cid: String
    )

    @Serializable
    data class DeleteRecordRequest(
        val repo: String,
        val collection: String,
        val rkey: String,
        val swapRecord: String? = null,
        val swapCommit: String? = null
    )

    @Serializable
    data class DeleteRecordResponse(
        val commit: JsonObject? = null
    )

    @Serializable
    data class ApplyWritesRequest(
        val repo: String,
        val writes: List<JsonElement>,
        val validate: Boolean? = null,
        val swapCommit: String? = null
    )

    @Serializable
    data class ApplyWritesResponse(
        val commit: JsonObject? = null,
        val results: List<JsonObject>? = null
    )

    /**
     * Reference to a record (URI + CID)
     */
    data class StrongRef(
        val uri: String,
        val cid: String
    )

    /**
     * Record data with untyped value
     */
    data class RecordData(
        val uri: String,
        val value: JsonElement,
        val cid: String?
    )

    /**
     * Record data with typed value
     */
    data class TypedRecordData<T>(
        val uri: String,
        val value: T,
        val cid: String?
    )

    /**
     * Paginated list of records
     */
    data class RecordList(
        val records: List<RecordData>,
        val cursor: String?
    )

    /**
     * Components of an AT URI
     */
    data class AtUriComponents(
        val did: String,
        val collection: String,
        val rkey: String
    )

    /**
     * Base class for batch write operations
     */
    sealed class WriteOperation {
        abstract fun toJson(): JsonElement
        
        @Serializable
        data class Create(
            val collection: String,
            val rkey: String? = null,
            val value: JsonElement
        ) : WriteOperation() {
            override fun toJson(): JsonElement = json.encodeToJsonElement(
                JsonObject(mapOf(
                    "\$type" to JsonPrimitive("com.atproto.repo.applyWrites#create"),
                    "collection" to JsonPrimitive(collection),
                    "rkey" to (rkey?.let { JsonPrimitive(it) } ?: JsonNull),
                    "value" to value
                ))
            )
        }
        
        @Serializable
        data class Update(
            val collection: String,
            val rkey: String,
            val value: JsonElement
        ) : WriteOperation() {
            override fun toJson(): JsonElement = json.encodeToJsonElement(
                JsonObject(mapOf(
                    "\$type" to JsonPrimitive("com.atproto.repo.applyWrites#update"),
                    "collection" to JsonPrimitive(collection),
                    "rkey" to JsonPrimitive(rkey),
                    "value" to value
                ))
            )
        }
        
        @Serializable
        data class Delete(
            val collection: String,
            val rkey: String
        ) : WriteOperation() {
            override fun toJson(): JsonElement = json.encodeToJsonElement(
                JsonObject(mapOf(
                    "\$type" to JsonPrimitive("com.atproto.repo.applyWrites#delete"),
                    "collection" to JsonPrimitive(collection),
                    "rkey" to JsonPrimitive(rkey)
                ))
            )
        }
        
        companion object {
            private val json = Json { prettyPrint = false }
        }
    }
}
