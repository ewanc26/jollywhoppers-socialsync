# Testing Guide

## Overview

This project includes comprehensive automated tests covering:
- Session management & authentication
- Security (encryption, rate limiting, audit logging)
- Record management (CRUD, AT URI parsing)
- AppView indexing and querying
- Storage layer (identity, preferences, stat sync, achievement sync)
- Sync service pure functions (category extraction, quit reason normalization)
- Network packet serialization

## Test Framework

- **Framework**: JUnit 5 (Jupiter)
- **Assertions**: Kotlin Test (`kotlin.test`)
- **Coverage**: 130+ tests across 14 test classes

## Running Tests

### Using Gradle

**Run all tests:**
```bash
./gradlew test
```

**Run specific test class:**
```bash
./gradlew test --tests *SessionManagerTest
```

**Run with verbose output:**
```bash
./gradlew test --info
```

### Using IDE

**IntelliJ IDEA:**
1. Right-click test file → Run tests
2. Or click the green triangle next to class/method name

**VS Code:**
1. Install "Test Explorer UI" extension
2. Click the test flask icon in sidebar
3. Run individual tests or test classes

## Test Organization

```
src/test/kotlin/com/jollywhoppers/atproto/
├── CoreTests.kt                          # Core service tests
│   ├── AtProtoSessionManagerTest         # Session lifecycle, persistence
│   ├── SecurityUtilsTest                 # Encryption/decryption, path validation
│   ├── RateLimiterTest                   # Attempt counting, lockout, per-player isolation
│   ├── PlayerIdentityStoreTest           # Link/unlink, lookup, persistence
│   ├── PlayerSyncPreferencesTest         # Data class defaults, shouldSync, frequencies
│   └── AppViewServiceTest                # Profile indexing, leaderboards, search, trending
├── AchievementSyncStoreTests.kt          # Achievement sync state persistence
├── AppViewServiceTests.kt                # Extended AppView querying (pagination, sorting)
├── AtProtoCommandsTests.kt               # DID/handle validation, AT URI parsing, TID generation
├── AtProtoPacketsSerializationTests.kt   # Network packet JSON serialization round-trips
├── PlayerStatSyncStoreTests.kt           # Stat sync state, fingerprint hashing, concurrency
├── SecurityAuditorTests.kt               # Audit log formatting, file I/O, event types
├── SecurityTests.kt                      # Extended security: key generation, permissions, sanitization
└── SyncServicePureFunctionTests.kt       # extractCategory, normalizeQuitReason
```

## Test Classes

### AtProtoSessionManagerTest (8 tests)

Tests session lifecycle, persistence, and auth types.

**Tests:**
- ✅ No session initially
- ✅ storeVerifiedSession makes hasSession true
- ✅ getSession returns stored data
- ✅ getSession returns failure for unknown player
- ✅ deleteSession removes the session
- ✅ deleteSession returns false for non-existent
- ✅ getAllSessions contains all stored sessions
- ✅ Persistence across manager reload (encrypted)
- ✅ OAuth authType stored correctly

### SecurityUtilsTest (7 tests | 15 more in SecurityTests.kt = 22 total)

- ✅ All 22 tests cover: encryption round-trip, wrong key fails, non-deterministic IV, path validation (in/out), sanitizeForLog masking/truncation, clearCharArray, key generation, key persistence, permissions, loadOrGenerateServerKey

### RateLimiterTest (7 tests)

- ✅ Fresh player allowed with all attempts
- ✅ Countdown after failures
- ✅ Lockout after max attempts
- ✅ Per-player isolation
- ✅ Success clears counter
- ✅ clearLimit removes failures and lockouts

### PlayerIdentityStoreTest (8 tests)

- ✅ Not linked initially
- ✅ linkIdentity makes isLinked true
- ✅ getIdentity returns DID and handle
- ✅ getIdentity returns null for unlinked
- ✅ unlinkIdentity removes mapping
- ✅ getUuidByDid lookup
- ✅ getUuidByHandle case-insensitive
- ✅ Persistence across store restart

### PlayerSyncPreferencesTest (5 tests)

- ✅ Default values (stats/sessions/achievements on, server_status off)
- ✅ shouldSync maps category to correct boolean
- ✅ isAnySyncEnabled false when all disabled
- ✅ isAnySyncEnabled true when one enabled
- ✅ getSyncFrequency returns per-type values

### AppViewServiceTest (7 tests | 8 more in AppViewServiceTests.kt = 15 total)

- ✅ Index and retrieve player profile
- ✅ Get profile returns null for unknown player
- ✅ Leaderboard from stats
- ✅ Leaderboard updates with newer stats
- ✅ Search players by username substring
- ✅ Trending achievements by count
- ✅ Player stats summary (top 5)
- ✅ Multi-player leaderboard sorting
- ✅ Search by display name
- ✅ Pagination: limit and offset
- ✅ Unknown player edge cases

### AchievementSyncStoreTest (10 tests)

- ✅ isSynced returns false for unknown player
- ✅ markSynced makes isSynced true
- ✅ Different advancement not synced
- ✅ removeSynced removes sync
- ✅ removeSynced no-op for unsynced
- ✅ Persistence across store reload
- ✅ Multiple players tracked independently
- ✅ Multiple advancements per player
- ✅ removeSynced on unknown player no-op
- ✅ Concurrent access does not throw

### AtProtoCommandsTests (19 tests)

- ✅ DID validation: valid PLC, valid web, empty, non-did, unsupported methods
- ✅ Handle validation: valid, empty, leading dash, trailing dash, special chars
- ✅ AT URI parsing: valid decomposes, null for non-uri, null for missing parts, null for empty
- ✅ TID generation: non-empty, unique
- ✅ WriteOperation data classes: Create, Update, Delete, Create without rkey

### AtProtoPacketsSerializationTest (8 tests)

- ✅ AuthenticatePacket serialization round-trip
- ✅ Default authType is app_password
- ✅ AuthenticateResponsePacket success
- ✅ AuthenticateResponsePacket failure
- ✅ LogoutPacket round-trip
- ✅ SyncPreferencesPacket all fields round-trip
- ✅ SyncPreferencesPacket all false

### PlayerStatSyncStoreTest (12 tests)

- ✅ New player has no state
- ✅ shouldSync true for unknown player
- ✅ shouldSync false when hash matches
- ✅ shouldSync true when hash differs
- ✅ recordSuccess updates hash and timestamp
- ✅ recordFailure stores error message
- ✅ recordAttempt updates lastAttemptAt
- ✅ Success clears previous error
- ✅ State persists across reload
- ✅ Multiple players survive reload
- ✅ Error truncated to 500 chars
- ✅ Concurrent access does not throw

### SecurityAuditorTest (14 tests)

- ✅ Initialize creates audit file with system entry
- ✅ logAuthSuccess writes formatted entry
- ✅ logAuthFailure with IP address
- ✅ logRateLimitHit writes entry
- ✅ logRateLimitLockout includes minutes
- ✅ logIdentityLink writes entry
- ✅ logIdentityUnlink writes entry
- ✅ logSessionRefresh writes entry
- ✅ logLogout writes entry
- ✅ logSuspiciousActivity with null UUID
- ✅ logSyncPreferenceChange with all fields
- ✅ logSecurityEvent custom event type
- ✅ All entries have timestamp prefix
- ✅ Multiple entries appended sequentially

### SyncServicePureFunctionTest (19 tests)

**extractCategory (9 tests):**
- ✅ Story, nether, end, adventure, husbandry categories
- ✅ Sub-advancements match parent category
- ✅ Unknown namespace falls back to prefix
- ✅ No slash falls back to "other"
- ✅ Empty string falls back to "other"

**normalizeQuitReason (10 tests):**
- ✅ server_stop, reconnected exact match
- ✅ Case-insensitive matching
- ✅ "kicked" substring detection
- ✅ "timeout" substring detection
- ✅ Unknown reason defaults to "disconnected"
- ✅ Empty string defaults to "disconnected"
- ✅ Long reasons truncated to 256 chars

## Writing New Tests

### Basic Test Structure

```kotlin
class MyFeatureTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: MyService

    @BeforeEach
    fun setup() {
        service = MyService(tempDir.resolve("data.json"))
    }

    @Test
    fun `should do something correctly`() {
        val result = service.doSomething("test")
        assertTrue(result.isSuccess)
        assertEquals("expected", result.getOrNull())
    }
}
```

### Best Practices

1. **Use descriptive backtick names:**
   ```kotlin
   fun `persistence survives multiple players`() { ... }
   ```

2. **Arrange-Act-Assert pattern**

3. **Prefer `kotlin.test` assertions**

4. **Test both success and failure paths**

5. **Use `@TempDir` for file-backed stores** rather than hardcoded paths

## Test Coverage Goals

| Component | Target | Current (approx) |
|-----------|--------|-------------------|
| SessionManager | 90% | 85% |
| RecordManager | 85% | 80% |
| Security | 95% | 92% |
| Storage (stores) | 85% | 80% |
| AppView | 80% | 75% |
| Network packets | 90% | 85% |

## Known Limitations

1. **No Minecraft runtime**: Tests run in plain JUnit — services depending on `ServerPlayer`, `MinecraftServer`, `FabricLoader` are tested at the data-class or pure-function level only
2. **No HTTP mocking**: Tests that require AT Protocol HTTP calls are not covered (use `expectSuccess = false` Ktor client to avoid actual connections)
3. **Singleton objects**: `PlayerSyncPreferencesStore` is an `object` with a `FabricLoader` dependency — only its data class (`PlayerSyncPreferences`) is directly tested
4. **Client module**: All 14 client-side files (OAuth, PKCE, DPoP, config screens) have zero test coverage

## Debugging Tests

### View Test Output

```bash
./gradlew test --info
```

### Generate Test Report

```bash
./gradlew test
open build/reports/tests/test/index.html
```

## Test Commands Cheatsheet

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests AchievementSyncStoreTest

# Run specific method
./gradlew test --tests "*extractCategory*"

# Run with verbose output
./gradlew test --info

# Clean build
./gradlew clean test
```

---

**Last Updated**: July 2026
**Test Count**: 130+ tests across 14 classes
**Coverage Focus**: Core services, stores, pure functions
