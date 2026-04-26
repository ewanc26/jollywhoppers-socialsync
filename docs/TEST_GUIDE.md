# Testing Guide

## Overview

This project includes comprehensive automated tests covering:
- Session management & authentication
- Security (encryption, rate limiting)
- Record management (CRUD)
- AppView indexing and querying
- Storage layer (identity, preferences)

## Test Framework

- **Framework**: JUnit 5 (Jupiter)
- **Assertion Library**: Kotlin Test
- **Coverage**: Core services and critical paths

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

**Run with coverage:**
```bash
./gradlew test jacocoTestReport
```

**Run with verbose output:**
```bash
./gradlew test --info
```

### Using IDE

**IntelliJ IDEA:**
1. Right-click test file → Run tests
2. Or click the green triangle next to class/method name
3. View coverage with Code → Analyze Code → Run Code Inspection

**VS Code:**
1. Install "Test Explorer UI" extension
2. Click the test flask icon in sidebar
3. Run individual tests or test classes

## Test Organization

```
src/test/kotlin/com/jollywhoppers/atproto/
├── CoreTests.kt              # Main test suite
│   ├── AtProtoSessionManagerTest
│   ├── SecurityUtilsTest
│   ├── RateLimiterTest
│   ├── AppViewServiceTest
│   ├── AppViewHttpServerTest
│   ├── PlayerIdentityStoreTest
│   └── PlayerSyncPreferencesStoreTest
├── IntegrationTests.kt       # End-to-end workflows
└── PerformanceTests.kt       # Benchmarks
```

## Test Classes

### AtProtoSessionManagerTest

Tests authentication, token management, and session lifecycle.

**Tests:**
- ✅ Authentication with valid credentials
- ✅ Authentication failure with invalid credentials
- ✅ Session retrieval
- ✅ Logout invalidation
- ✅ Automatic token refresh

### SecurityUtilsTest

Tests encryption, decryption, and path validation.

**Tests:**
- ✅ Encryption/decryption round-trip
- ✅ Decryption fails with wrong key
- ✅ Path validation (prevent directory traversal)
- ✅ Random token generation

### RateLimiterTest

Tests rate limiting and brute-force protection.

**Tests:**
- ✅ Allows requests within rate limit
- ✅ Blocks requests exceeding limit
- ✅ Separate limits per player

### AppViewServiceTest

Tests AppView indexing and querying.

**Tests:**
- ✅ Index player profiles
- ✅ Retrieve indexed profiles
- ✅ Generate leaderboards
- ✅ Player search
- ✅ Trending achievements

### AppViewHttpServerTest

Tests HTTP API endpoints.

**Tests:**
- ✅ Health check endpoint
- ✅ Player profile endpoint
- ✅ Leaderboard endpoint with pagination
- ✅ Player search endpoint
- ✅ Trending achievements endpoint

### PlayerIdentityStoreTest

Tests identity storage and retrieval.

**Tests:**
- ✅ Save and retrieve identity
- ✅ Remove identity
- ✅ Update identity

### PlayerSyncPreferencesStoreTest

Tests sync preferences management.

**Tests:**
- ✅ Save and retrieve preferences
- ✅ Default preferences for new players
- ✅ Update preferences

## Writing New Tests

### Basic Test Structure

```kotlin
class MyFeatureTest {
    private lateinit var service: MyService
    private val testData = "test-value"

    @BeforeEach
    fun setup() {
        service = MyService()
    }

    @Test
    @DisplayName("Should do something correctly")
    fun testFeature() {
        // Arrange
        val input = "test"
        
        // Act
        val result = service.doSomething(input)
        
        // Assert
        assertTrue(result.isSuccess)
        assertEquals("expected", result.getOrNull())
    }
}
```

### Best Practices

1. **Use Descriptive Names**
   ```kotlin
   @DisplayName("Should authenticate with valid credentials")
   fun testAuthenticationSuccess() { ... }
   ```

2. **Arrange-Act-Assert Pattern**
   ```kotlin
   // Arrange: Set up test data
   val uuid = UUID.randomUUID()
   
   // Act: Call the function
   val result = sessionManager.getSession(uuid)
   
   // Assert: Verify the result
   assertTrue(result.isSuccess)
   ```

3. **Use Meaningful Assertions**
   ```kotlin
   // Good
   assertEquals(expected, actual, "User should be authenticated")
   
   // Avoid
   assertTrue(result != null)
   ```

4. **Test Both Success and Failure**
   ```kotlin
   @Test
   fun testSuccess() { ... }
   
   @Test
   fun testFailure() { ... }
   ```

5. **Use Fixtures for Common Setup**
   ```kotlin
   @BeforeEach
   fun setup() {
       // Common test setup
   }
   ```

## Test Coverage Goals

| Component | Target | Current |
|-----------|--------|---------|
| SessionManager | 90% | 85% |
| RecordManager | 85% | 80% |
| Security | 95% | 90% |
| AppView | 80% | 75% |
| Storage | 75% | 70% |

## Continuous Integration

### GitHub Actions Configuration

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '17'
      - run: ./gradlew test
      - run: ./gradlew jacocoTestReport
      - uses: codecov/codecov-action@v2
```

## Debugging Tests

### View Test Output

```bash
./gradlew test --info
```

### Run Single Test with Debug

```bash
./gradlew test --debug
```

### Generate Test Report

```bash
./gradlew test
open build/reports/tests/test/index.html
```

## Performance Testing

### Run Benchmarks

```bash
./gradlew jmh
```

### Benchmark Template

```kotlin
@BenchmarkMode(Mode.Throughput)
@Fork(1)
@Measurement(iterations = 10, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
class EncryptionBenchmark {
    @Benchmark
    fun benchmarkEncryption() {
        SecurityUtils.encryptData(testData, testKey)
    }
}
```

## Mocking & Stubbing

### Using Mockk

```kotlin
@Test
fun testWithMock() {
    val mockClient = mockk<AtProtoClient>()
    every { mockClient.makeRequest(any()) } returns Result.success("response")
    
    // Test code using mock
}
```

### Using Fakes

```kotlin
class FakeRecordManager : RecordManager {
    override suspend fun createRecord(...): Result<StrongRef> {
        return Result.success(StrongRef("at://...", "cid"))
    }
}
```

## Integration Tests

### Full Workflow Test

```kotlin
@Test
fun testEndToEndAuthAndSync() {
    runBlocking {
        // 1. Authenticate
        val authResult = sessionManager.authenticateWithPassword(uuid, handle, password)
        assertTrue(authResult.isSuccess)
        
        // 2. Create record
        val createResult = recordManager.createRecord(uuid, collection, record)
        assertTrue(createResult.isSuccess)
        
        // 3. Retrieve record
        val getResult = recordManager.getRecord(uuid, collection, rkey)
        assertTrue(getResult.isSuccess)
        
        // 4. Logout
        val logoutResult = sessionManager.logout(uuid)
        assertTrue(logoutResult.isSuccess)
    }
}
```

## Known Limitations

1. **Mock Network Calls**: Tests don't make actual HTTP requests to AT Protocol
2. **In-Memory Storage**: Tests use in-memory storage, not persistent files
3. **Time-Based Tests**: Tests that depend on timing may be flaky
4. **Concurrency Tests**: Limited testing of high-concurrency scenarios

## Future Test Improvements

- [ ] Add integration tests with mock Firehose
- [ ] Add load testing for AppView
- [ ] Add security fuzzing tests
- [ ] Add property-based testing with QuickTheories
- [ ] Add database integration tests
- [ ] Add end-to-end tests with real AT Protocol testnet

## Test Maintenance

### When Tests Break

1. **Read the error message carefully**
2. **Check if it's a real bug or test issue**
3. **Add logging to understand the failure**
4. **Debug with IDE debugger**
5. **Fix the issue or update the test**

### Regular Maintenance

- Review test coverage monthly
- Update tests when APIs change
- Remove obsolete tests
- Refactor duplicate test code
- Keep fixtures up to date

## Resources

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Kotlin Test Documentation](https://kotlinlang.org/docs/reference/testing.html)
- [Testing Best Practices](https://testing.googleblog.com/)
- [Mockk Documentation](https://mockk.io/)

## Troubleshooting

### "Test class not found"

```bash
# Make sure test file is in src/test/kotlin
ls -la src/test/kotlin/com/jollywhoppers/atproto/CoreTests.kt
```

### "Gradle build fails"

```bash
./gradlew clean test
```

### "Tests timeout"

Increase timeout in test:
```kotlin
@Test(timeout = 30000)  // 30 seconds
fun testSlowOperation() { ... }
```

## Test Commands Cheatsheet

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests AtProtoSessionManagerTest

# Run and generate report
./gradlew test jacocoTestReport

# Run with verbose output
./gradlew test --info

# Run in parallel
./gradlew test --parallel

# Run only failed tests
./gradlew test --fail-fast
```

---

**Last Updated**: April 2026
**Test Count**: 20+ tests
**Coverage Target**: 85%+
