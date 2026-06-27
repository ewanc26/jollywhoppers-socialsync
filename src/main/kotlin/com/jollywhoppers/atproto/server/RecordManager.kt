package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityUtils
import io.github.kikin81.atproto.com.atproto.repo.RepoService
import io.github.kikin81.atproto.com.atproto.repo.CreateRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.GetRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.ListRecordsRequest
import io.github.kikin81.atproto.com.atproto.repo.PutRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.DeleteRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.ApplyWritesRequest
import io.github.kikin81.atproto.com.atproto.repo.ApplyWritesCreate
import io.github.kikin81.atproto.com.atproto.repo.ApplyWritesUpdate
import io.github.kikin81.atproto.com.atproto.repo.ApplyWritesDelete
import io.github.kikin81.atproto.com.atproto.repo.ApplyWritesRequestWritesUnion
import io.github.kikin81.atproto.com.atproto.repo.CommitMeta
import io.github.kikin81.atproto.runtime.XrpcClient
import io.github.kikin81.atproto.runtime.Nsid
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.RecordKey
import io.github.kikin81.atproto.runtime.Cid
import io.github.kikin81.atproto.runtime.AtField
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.util.UUID
import com.jollywhoppers.atproto.server.Tid

class RecordManager(
    val xrpcClient: XrpcClient,
    val json: Json,
    private val sessionManager: AtProtoSessionManager
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:RecordManager")
    private val repoService = RepoService(xrpcClient)

    suspend fun createRecord(
        playerUuid: UUID,
        collection: String,
        record: JsonElement,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        logger.info("Creating record in collection: $collection for player: ${SecurityUtils.sanitizeForLog(playerUuid.toString())}")
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        val request = CreateRecordRequest(
            collection = Nsid(collection),
            record = record.jsonObject,
            repo = AtIdentifier(session.did),
            rkey = AtField.Missing,
            swapCommit = AtField.Missing,
            validate = if (validate) AtField.Missing else AtField.Defined(false)
        )
        val response = repoService.createRecord(request)
        logger.info("Record created successfully: ${response.uri}")
        StrongRef(uri = response.uri.toString(), cid = response.cid.toString())
    }

    suspend inline fun <reified T> createTypedRecord(
        playerUuid: UUID,
        collection: String,
        record: T,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        val jsonElement = json.encodeToJsonElement(serializer<T>(), record)
        createRecord(playerUuid, collection, jsonElement, validate).getOrThrow()
    }

    suspend fun getRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        cid: String? = null
    ): Result<RecordData> = runCatching {
        logger.info("Fetching record: $collection/$rkey")
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        val request = GetRecordRequest(
            cid = cid?.let { Cid(it) },
            collection = Nsid(collection),
            repo = AtIdentifier(session.did),
            rkey = RecordKey(rkey)
        )
        val response = repoService.getRecord(request)
        logger.info("Record retrieved successfully")
        RecordData(
            uri = response.uri.toString(),
            value = response.value,
            cid = response.cid?.toString()
        )
    }

    suspend inline fun <reified T> getTypedRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        cid: String? = null
    ): Result<TypedRecordData<T>> = runCatching {
        val recordData = getRecord(playerUuid, collection, rkey, cid).getOrThrow()
        TypedRecordData(
            uri = recordData.uri,
            value = json.decodeFromJsonElement(serializer<T>(), recordData.value),
            cid = recordData.cid
        )
    }

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
        val request = ListRecordsRequest(
            collection = Nsid(collection),
            cursor = cursor,
            limit = limit.toLong(),
            repo = AtIdentifier(session.did),
            reverse = if (reverse) true else null
        )
        val response = repoService.listRecords(request)
        logger.info("Retrieved ${response.records.size} records")
        RecordList(
            records = response.records.map {
                RecordData(uri = it.uri.toString(), value = it.value, cid = it.cid?.toString())
            },
            cursor = response.cursor
        )
    }

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
            collection = Nsid(collection),
            record = record.jsonObject,
            repo = AtIdentifier(session.did),
            rkey = RecordKey(rkey),
            swapCommit = swapCommit?.let { AtField.Defined(Cid(it)) } ?: AtField.Missing,
            swapRecord = swapRecord?.let { AtField.Defined(Cid(it)) } ?: AtField.Missing,
            validate = if (validate) AtField.Missing else AtField.Defined(false)
        )
        val response = repoService.putRecord(request)
        logger.info("Record updated successfully: ${response.uri}")
        StrongRef(uri = response.uri.toString(), cid = response.cid.toString())
    }

    suspend inline fun <reified T> putTypedRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String,
        record: T,
        swapRecord: String? = null,
        swapCommit: String? = null,
        validate: Boolean = true
    ): Result<StrongRef> = runCatching {
        val jsonElement = json.encodeToJsonElement(serializer<T>(), record)
        putRecord(playerUuid, collection, rkey, jsonElement, swapRecord, swapCommit, validate).getOrThrow()
    }

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
            collection = Nsid(collection),
            repo = AtIdentifier(session.did),
            rkey = RecordKey(rkey),
            swapCommit = swapCommit?.let { AtField.Defined(Cid(it)) } ?: AtField.Missing,
            swapRecord = swapRecord?.let { AtField.Defined(Cid(it)) } ?: AtField.Missing
        )
        val response = repoService.deleteRecord(request)
        logger.info("Record deleted successfully")
        DeleteRecordResponse(commit = response.commit)
    }

    suspend fun applyWrites(
        playerUuid: UUID,
        writes: List<WriteOperation>,
        validate: Boolean = true,
        swapCommit: String? = null
    ): Result<ApplyWritesResponse> = runCatching {
        logger.info("Applying ${writes.size} write operations")
        val session = sessionManager.getSession(playerUuid).getOrThrow()
        val request = ApplyWritesRequest(
            repo = AtIdentifier(session.did),
            swapCommit = swapCommit?.let { AtField.Defined(Cid(it)) } ?: AtField.Missing,
            validate = if (validate) AtField.Missing else AtField.Defined(false),
            writes = writes.map { it.toLibraryWrite() }
        )
        repoService.applyWrites(request)
        logger.info("Writes applied successfully")
        ApplyWritesResponse()
    }

    fun generateTID(): String = Tid.generate()

    fun parseAtUri(uri: String): AtUriComponents? {
        if (!uri.startsWith("at://")) return null
        val parts = uri.removePrefix("at://").split("/")
        if (parts.size != 3) return null
        return AtUriComponents(did = parts[0], collection = parts[1], rkey = parts[2])
    }

    // ============================================================================
    // DATA CLASSES
    // ============================================================================

    data class StrongRef(
        val uri: String,
        val cid: String
    )

    data class RecordData(
        val uri: String,
        val value: JsonElement,
        val cid: String?
    )

    data class TypedRecordData<T>(
        val uri: String,
        val value: T,
        val cid: String?
    )

    data class RecordList(
        val records: List<RecordData>,
        val cursor: String?
    )

    data class AtUriComponents(
        val did: String,
        val collection: String,
        val rkey: String
    )

    data class DeleteRecordResponse(
        val commit: CommitMeta? = null
    )

    data class ApplyWritesResponse(
        val commit: JsonObject? = null,
        val results: List<JsonObject>? = null
    )

    sealed class WriteOperation {
        abstract fun toLibraryWrite(): ApplyWritesRequestWritesUnion

        data class Create(
            val collection: String,
            val rkey: String? = null,
            val value: JsonElement
        ) : WriteOperation() {
            override fun toLibraryWrite(): ApplyWritesRequestWritesUnion = ApplyWritesCreate(
                collection = Nsid(collection),
                rkey = rkey?.let { AtField.Defined(RecordKey(it)) } ?: AtField.Missing,
                value = value.jsonObject
            )
        }

        data class Update(
            val collection: String,
            val rkey: String,
            val value: JsonElement
        ) : WriteOperation() {
            override fun toLibraryWrite(): ApplyWritesRequestWritesUnion = ApplyWritesUpdate(
                collection = Nsid(collection),
                rkey = RecordKey(rkey),
                value = value.jsonObject
            )
        }

        data class Delete(
            val collection: String,
            val rkey: String
        ) : WriteOperation() {
            override fun toLibraryWrite(): ApplyWritesRequestWritesUnion = ApplyWritesDelete(
                collection = Nsid(collection),
                rkey = RecordKey(rkey)
            )
        }
    }
}
