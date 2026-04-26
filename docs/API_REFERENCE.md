# Developer Guide: Complete API Reference

## Table of Contents
1. [Authentication & Session Management](#authentication--session-management)
2. [Record Management](#record-management)
3. [Security & Utilities](#security--utilities)
4. [Configuration & Storage](#configuration--storage)
5. [Command System](#command-system)

---

## Authentication & Session Management

### AtProtoSessionManager

The `AtProtoSessionManager` handles all authentication, token management, and session lifecycle.

#### Key Methods

**Authentication**

```kotlin
suspend fun authenticateWithPassword(
    playerUuid: UUID,
    handle: String,
    appPassword: String
): Result<Session>
```
Authenticates a player with their handle and app password.

**Parameters:**
- `playerUuid`: Minecraft player UUID
- `handle`: AT Protocol handle (e.g., "alice.bsky.social")
- `appPassword`: AT Protocol app password

**Returns:** `Session` object containing DID, access token, refresh token

**Example:**
```kotlin
val session = sessionManager.authenticateWithPassword(
    UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
    "alice.bsky.social",
    "abcd-1234-efgh-5678"
).onSuccess { session ->
    println("Authenticated as ${session.did}")
}.onFailure { error ->
    println("Auth failed: ${error.message}")
}
```

---

**Get Active Session**

```kotlin
suspend fun getSession(playerUuid: UUID): Result<Session>
```
Retrieves the active session for a player, auto-refreshing if needed.

**Parameters:**
- `playerUuid`: Minecraft player UUID

**Returns:** Active `Session` or error if not authenticated

---

**Logout**

```kotlin
suspend fun logout(playerUuid: UUID): Result<Unit>
```
Invalidates a player's session and clears stored tokens.

---

**Link Identity**

```kotlin
suspend fun linkIdentity(
    playerUuid: UUID,
    handle: String
): Result<DidInfo>
```
Links a Minecraft UUID to an AT Protocol DID (read-only, no authentication).

---

### Session Data Model

```kotlin
@Serializable
data class Session(
    val did: String,                    // AT Protocol DID
    val handle: String,                 // Handle
    val accessToken: String,            // JWT access token
    val refreshToken: String?,          // JWT refresh token
    val accessTokenExpiry: Long,        // Expiration time (millis)
    val createdAt: Long,                // Creation time
    val refreshedAt: Long?              // Last refresh time
)
```

---

## Record Management

### RecordManager

Provides type-safe CRUD operations for AT Protocol records.

#### Create Operations

**Create Record (Auto-generated TID)**

```kotlin
suspend fun createRecord(
    playerUuid: UUID,
    collection: String,
    record: JsonElement,
    validate: Boolean = true
): Result<StrongRef>
```

**Parameters:**
- `playerUuid`: Player UUID
- `collection`: Lexicon collection name
- `record`: JSON record data (must include `$type`)
- `validate`: Whether to validate against lexicon schema

**Returns:** `StrongRef` with URI and CID

**Example:**
```kotlin
val statsRecord = json.parseToJsonElement("""
{
  "$type": "com.jollywhoppers.minecraft.player.stats",
  "player": {"uuid": "$playerUuid", "username": "Alice"},
  "statistics": [{"key": "minecraft.mined.oak_log", "value": 1250}],
  "playtimeMinutes": 7200,
  "level": 34,
  "gamemode": "survival",
  "syncedAt": "${Instant.now()}"
}
""")

recordManager.createRecord(
    playerUuid,
    "com.jollywhoppers.minecraft.player.stats",
    statsRecord
).onSuccess { ref ->
    println("Record created: ${ref.uri}")
}
```

---

**Create Typed Record**

```kotlin
suspend inline fun <reified T> createTypedRecord(
    playerUuid: UUID,
    collection: String,
    record: T,
    validate: Boolean = true
): Result<StrongRef>
```

Convenience method with automatic serialization.

---

#### Read Operations

**Get Single Record**

```kotlin
suspend fun getRecord(
    playerUuid: UUID,
    collection: String,
    rkey: String,
    cid: String? = null
): Result<RecordData>
```

**Parameters:**
- `rkey`: Record key (TID or "self" for literal records)
- `cid`: Optional specific version

**Returns:** `RecordData` with URI, value, and CID

---

**List Records (Paginated)**

```kotlin
suspend fun listRecords(
    playerUuid: UUID,
    collection: String,
    limit: Int = 100,
    cursor: String? = null
): Result<RecordPage>
```

**Returns:** `RecordPage` with records and pagination cursor

---

#### Update Operations

**Put Record (Update or Create)**

```kotlin
suspend fun putRecord(
    playerUuid: UUID,
    collection: String,
    rkey: String,
    record: JsonElement,
    validate: Boolean = true
): Result<StrongRef>
```

---

#### Delete Operations

**Delete Record**

```kotlin
suspend fun deleteRecord(
    playerUuid: UUID,
    collection: String,
    rkey: String
): Result<Unit>
```

---

## Security & Utilities

### SecurityUtils

Cryptography and validation utilities.

#### Encryption

**Encrypt Data**

```kotlin
fun encryptData(
    data: String,
    key: ByteArray
): Result<String>  // Returns base64-encoded ciphertext
```

Uses AES-256-GCM encryption.

---

**Decrypt Data**

```kotlin
fun decryptData(
    encryptedData: String,  // Base64-encoded
    key: ByteArray
): Result<String>
```

---

#### Key Generation

**Generate Encryption Key**

```kotlin
fun generateEncryptionKey(): ByteArray  // 32 bytes for AES-256
```

---

### SecurityAuditor

Security event logging and monitoring.

**Log Event**

```kotlin
fun logEvent(
    level: AuditLevel,
    eventType: String,
    playerId: String?,
    message: String,
    metadata: Map<String, String>? = null
)
```

**Event Types:**
- `AUTH_SUCCESS`
- `AUTH_FAILURE`
- `RATE_LIMIT_EXCEEDED`
- `SESSION_CREATED`
- `SESSION_DELETED`
- `TOKEN_REFRESH`
- `RECORD_CREATED`
- `RECORD_DELETED`

---

### RateLimiter

Prevents brute-force attacks.

**Check Rate Limit**

```kotlin
fun checkRateLimit(
    identifier: String,
    maxAttempts: Int = 3,
    windowMinutes: Int = 15,
    lockoutMinutes: Int = 30
): Result<Unit>
```

Returns `Result.success()` if within limits, or `Result.failure()` if rate limited.

---

## Configuration & Storage

### PlayerIdentityStore

Persistent UUID ↔ DID/handle mapping storage.

```kotlin
suspend fun saveIdentity(
    playerUuid: UUID,
    did: String,
    handle: String
): Result<Unit>

suspend fun getIdentity(playerUuid: UUID): Result<DidInfo?>

suspend fun getAllIdentities(): Result<List<PlayerIdentity>>

suspend fun removeIdentity(playerUuid: UUID): Result<Unit>
```

**Data Model:**
```kotlin
data class PlayerIdentity(
    val playerUuid: UUID,
    val did: String,
    val handle: String,
    val createdAt: Instant,
    val verifiedAt: Instant?
)
```

---

### PlayerSyncPreferencesStore

Sync consent management (single source of truth).

```kotlin
suspend fun getSyncPreferences(playerUuid: UUID): Result<SyncPreferences>

suspend fun updateSyncPreferences(
    playerUuid: UUID,
    preferences: SyncPreferences
): Result<Unit>
```

**Data Model:**
```kotlin
@Serializable
data class SyncPreferences(
    val playerUuid: UUID,
    val syncStats: Boolean = false,
    val syncSessions: Boolean = false,
    val syncAchievements: Boolean = false,
    val syncServerStatus: Boolean = false,
    val syncIntervalMinutes: Int = 60,
    val updatedAt: Long = System.currentTimeMillis()
)
```

---

### Configuration Files

**Location:** `config/atproto-connect/`

**Files:**
- `player-identities.json` - UUID↔DID mappings
- `player-sessions.json` - Encrypted auth tokens
- `sync-preferences/` - Per-player settings
- `.encryption.key` - AES-256 master key
- `security-audit.log` - Security events

---

## Command System

### AtProtoCommands

All player-facing commands are implemented in `AtProtoCommands.kt`.

#### Command Structure

```
/atproto <subcommand> [arguments]
```

#### Available Commands

**Identity Management**

| Command | Purpose |
|---------|---------|
| `/atproto link <handle\|DID>` | Link Minecraft UUID to AT Protocol identity |
| `/atproto unlink` | Remove identity link |
| `/atproto whoami` | Show linked identity and status |
| `/atproto whois <player\|handle>` | Look up another player's identity |

**Authentication**

| Command | Purpose |
|---------|---------|
| `/atproto login <handle> <app-password>` | Authenticate with app password |
| `/atproto logout` | Remove authentication |
| `/atproto status` | Check authentication status |

**Sync Control**

| Command | Purpose |
|---------|---------|
| `/atproto sync` | View sync consent settings |
| `/atproto sync stats <on\|off>` | Toggle stat syncing |
| `/atproto sync sessions <on\|off>` | Toggle session tracking |
| `/atproto sync achievements <on\|off>` | Toggle achievement syncing |
| `/atproto sync server-status <on\|off>` | Toggle server status snapshots |

**Utilities**

| Command | Purpose |
|---------|---------|
| `/atproto help` | Show help message |
| `/atproto version` | Show mod version |

---

## Service Integration

### How Services Work Together

```
Player Command
    ↓
AtProtoCommands (Coroutine handler)
    ↓
AtProtoSessionManager (Get/create session)
    ↓
PlayerSyncPreferencesStore (Check sync settings)
    ↓
RecordManager (Create/read/update records)
    ↓
AtProtoClient (Make HTTP requests via XRPC)
    ↓
SecurityAuditor (Log security events)
    ↓
AT Protocol Network (Firehose, PDS, etc.)
```

---

## Error Handling

All services return `Result<T>` types for composable error handling.

**Pattern:**
```kotlin
service.doSomething()
    .onSuccess { result ->
        println("Success: $result")
    }
    .onFailure { error ->
        println("Error: ${error.message}")
        securityAuditor.logEvent(
            AuditLevel.WARNING,
            "OPERATION_FAILED",
            playerId,
            error.message ?: "Unknown error"
        )
    }
```

---

## Performance Considerations

1. **Session Caching**: Sessions are cached to avoid repeated token refresh
2. **Rate Limiting**: Prevents brute-force attacks without blocking legitimate users
3. **Encryption**: AES-256-GCM is fast while maintaining security
4. **Async Operations**: All I/O uses Kotlin Coroutines for non-blocking execution
5. **Pagination**: Record listings use cursors to handle large datasets

---

## Thread Safety

- All mutable state is protected with proper synchronization
- Session storage uses atomic writes
- Configuration file access is serialized
- Security audit logging is thread-safe

---

## Testing

See [TEST_GUIDE.md](TEST_GUIDE.md) for comprehensive testing documentation.

---

## Troubleshooting

### Common Issues

**"Authentication failed: Invalid token"**
- App password may be expired or revoked
- Create a new app password and login again

**"Session not found"**
- Player needs to authenticate first with `/atproto login`
- Check `config/atproto-connect/player-sessions.json` exists

**"Rate limit exceeded"**
- Too many failed login attempts
- Wait 30 minutes or check `security-audit.log` for details

**"Record creation failed: Invalid collection"**
- Collection name may be misspelled
- Verify against defined lexicon namespaces

---

## Examples

See `docs/examples/` for complete working code examples:
- `RecordCreationExample.kt` - Creating and syncing records
- `AppViewExample.kt` - Building an AppView service
- `RecordManagerExamples.kt` - CRUD operations

---

## API Stability

This API is currently in **Alpha**. Breaking changes may occur in minor version updates.
