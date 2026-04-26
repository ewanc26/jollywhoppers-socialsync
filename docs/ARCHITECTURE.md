# Architecture & Deployment Guide

## System Architecture

### High-Level Overview

```
┌─────────────────────────────────────┐
│      Minecraft Server (Fabric)      │
│  ┌─────────────────────────────────┐│
│  │   atproto-connect Mod           ││
│  │ ┌─────────────────────────────┐ ││
│  │ │  Command Handlers           │ ││
│  │ │  (/atproto link, login...)  │ ││
│  │ └─────────────────────────────┘ ││
│  │ ┌─────────────────────────────┐ ││
│  │ │  Player Event Hooks         │ ││
│  │ │  (Advancement, Stats, etc)  │ ││
│  │ └─────────────────────────────┘ ││
│  │ ┌─────────────────────────────┐ ││
│  │ │  Sync Services              │ ││
│  │ │  (Achievements, Stats,      │ ││
│  │ │   Sessions, Server Status)  │ ││
│  │ └─────────────────────────────┘ ││
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
            ↓
┌─────────────────────────────────────┐
│   atproto-connect Core Services     │
│ ┌─────────────────────────────────┐ │
│ │  AtProtoSessionManager          │ │
│ │  • OAuth & App Password Auth    │ │
│ │  • Token Management             │ │
│ │  • Session Persistence          │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │  RecordManager                  │ │
│ │  • Create Records               │ │
│ │  • Query Records                │ │
│ │  • Update/Delete                │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │  Security Layer                 │ │
│ │  • Encryption (AES-256-GCM)     │ │
│ │  • Rate Limiting                │ │
│ │  • Audit Logging                │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │  Storage Layer                  │ │
│ │  • Encrypted Sessions           │ │
│ │  • Identity Mappings            │ │
│ │  • Sync Preferences             │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
            ↓
┌─────────────────────────────────────┐
│    AT Protocol Network              │
│ ┌─────────────────────────────────┐ │
│ │  AtProtoClient (HTTP + XRPC)    │ │
│ │  ┌──────────────────────────────┤ │
│ │  │  Player PDS                  │ │
│ │  │  (Data & Identity Storage)   │ │
│ │  └──────────────────────────────┤ │
│ │  ┌──────────────────────────────┤ │
│ │  │  Slingshot Service           │ │
│ │  │  (PDS Resolution)            │ │
│ │  └──────────────────────────────┤ │
│ │  ┌──────────────────────────────┤ │
│ │  │  plc.directory               │ │
│ │  │  (DID Resolution)            │ │
│ │  └──────────────────────────────┤ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

### Module Dependencies

```
Minecraft Mod (client & server)
    ↓
    Command Handlers ← → Session Manager
         ↓                     ↓
    Event Listeners ← → Record Manager
         ↓                     ↓
    Sync Services ← → AT Proto Client
         ↓                     ↓
    Security Layer ← → Storage Layer
         ↓
    File System (config/atproto-connect/)
```

---

## Component Deep Dive

### 1. Command System

**Location:** `AtProtoCommands.kt`

**Responsibility:**
- Parse player commands
- Validate arguments
- Delegate to appropriate services
- Format responses

**Flow:**
```
Player types /atproto command
    ↓
CommandDispatcher catches command
    ↓
AtProtoCommands.onCommand() invoked
    ↓
Validate command & arguments
    ↓
Route to handler (login, link, sync, etc)
    ↓
Handler calls services (SessionManager, StorageLayer)
    ↓
Result formatted and sent to player
```

---

### 2. Session Management

**Location:** `AtProtoSessionManager.kt`

**Responsibility:**
- Handle OAuth flow
- Manage JWT tokens
- Auto-refresh before expiration
- Persist encrypted sessions

**Key Features:**
- Browser-based OAuth with DPoP support
- App password fallback authentication
- Automatic token refresh (2-hour expiry)
- Encrypted token storage (AES-256-GCM)

**State Diagram:**
```
[Not Authenticated]
         ↓
    /atproto login
         ↓
[Authenticating] ←→ (AT Protocol PDS)
         ↓ (Success)
[Authenticated] ←→ (Token Refresh Check)
    ↓         ↑
/atproto logout
    ↓
[Not Authenticated]
```

---

### 3. Record Management

**Location:** `RecordManager.kt`

**Responsibility:**
- Create records (auto-generated TIDs)
- Query records (single and paginated)
- Update records
- Delete records
- Type-safe JSON serialization

**Record Types:**
```
Player Profile (literal:self)
    ↓ (one per account)
    Player stats, sessions, achievements

Player Stats (tid)
    ↓ (multiple, time-ordered)
    Periodic snapshots

Achievements (tid)
    ↓ (multiple, time-ordered)
    Earned advancements

Play Sessions (tid)
    ↓ (multiple, time-ordered)
    Join/leave events

Server Status (literal:self)
    ↓ (one per server)
    MOTD, player count, version
```

---

### 4. Data Syncing Services

**Location:** `atproto/server/*SyncService.kt`

**Services:**
- `PlayerStatSyncService` - Periodic stat snapshots
- `AchievementSyncService` - Achievement records
- `PlayerSessionSyncService` - Session tracking
- `ServerStatusSyncService` - Server info snapshots

**Sync Workflow:**
```
Player Event (stat update, achievement earned)
    ↓
Event Hook triggered
    ↓
Check sync preferences
    ↓ (if enabled)
Format record
    ↓
RecordManager.createRecord()
    ↓
AT Protocol PDS
```

---

### 5. Security Layer

**Location:** `security/*.kt`

**Components:**

**SecurityUtils**
- AES-256-GCM encryption/decryption
- Path validation (prevent directory traversal)
- Random token generation

**RateLimiter**
- 3 failed attempts per 15 minutes
- 30-minute lockout
- Per-UUID and per-handle tracking

**SecurityAuditor**
- Log all security events
- Separate file: `security-audit.log`
- Events: auth, rate limits, session ops, errors

---

### 6. Storage Layer

**Location:** `PlayerIdentityStore.kt`, `PlayerSyncPreferencesStore.kt`

**Files:**
```
config/atproto-connect/
├── player-identities.json          (plaintext UUID↔DID mapping)
├── player-sessions.json            (AES-256-GCM encrypted)
├── sync-preferences/               (per-player settings)
│   ├── {uuid}.json
│   └── {uuid}.json
├── .encryption.key                 (32-byte AES-256 key)
└── security-audit.log              (security events)
```

**Access Pattern:**
```
Request for player data
    ↓
Check in-memory cache
    ↓ (miss)
Load from file
    ↓ (if encrypted, decrypt with .encryption.key)
Cache in memory
    ↓
Return data
```

---

## Data Flow Examples

### Example 1: Player Authentication Flow

```
Player: /atproto login alice.bsky.social abcd-1234-efgh-5678
    ↓
AtProtoCommands.handleLogin()
    ↓
SessionManager.authenticateWithPassword()
    ↓
AtProtoClient.makeAuthenticatedRequest("com.atproto.server.createSession")
    ↓
AT Protocol PDS (Validates credentials)
    ↓
Returns: {access_token, refresh_token, did, handle}
    ↓
SessionManager encrypts and stores in player-sessions.json
    ↓
Session cached in memory
    ↓
Player: "✓ Successfully authenticated!"
```

---

### Example 2: Stats Syncing Flow

```
Player earns stats (blocks mined, mobs killed, etc)
    ↓
Minecraft event: StatUpdateEvent
    ↓
PlayerStatSyncService.onStatUpdate()
    ↓
Check: is sync enabled? (PlayerSyncPreferencesStore)
    ↓ (yes)
Format: PlayerStatsRecord {player, server, stats, level, gamemode}
    ↓
RecordManager.createRecord("com.jollywhoppers.minecraft.player.stats")
    ↓
Serialize to JSON with $type field
    ↓
SessionManager.getSession() (auto-refresh if needed)
    ↓
AtProtoClient.makeAuthenticatedRequest("com.atproto.repo.createRecord")
    ↓
Include: repo (DID), collection, record, validate=true
    ↓
AT Protocol PDS creates record with auto-generated TID
    ↓
Returns: {uri, cid}
    ↓
SecurityAuditor.logEvent("RECORD_CREATED", ...)
    ↓
Data now visible on AT Protocol network
```

---

### Example 3: AppView Leaderboard Query

```
User queries: GET /leaderboard/minecraft.mined.oak_log
    ↓
AppViewHttpServer.handleGetLeaderboard()
    ↓
AppViewService.getLeaderboard("minecraft.mined.oak_log", limit=20)
    ↓
Query in-memory leaderboard data structure
    ↓
Sort by value descending
    ↓
Take top 20
    ↓
Format as LeaderboardEntryView JSON
    ↓
Response: [{username, value, recordedAt}, ...]
    ↓
Client renders leaderboard UI
```

---

## Deployment Scenarios

### Scenario 1: Single Server (Local)

**Setup:**
- Minecraft server (local or cloud VM)
- Config stored on server
- No AppView

**Considerations:**
- Simple setup, minimal overhead
- No cross-server leaderboards
- Players' data only visible on their own PDS

---

### Scenario 2: Multiple Servers

**Setup:**
- Multiple Minecraft servers (same cluster)
- Shared config storage (database or shared filesystem)
- Players' data synced to AT Protocol independently

**Considerations:**
- Each server manages its own data syncing
- All servers' data visible on players' PDS
- Can create unified AppView across servers

---

### Scenario 3: With AppView

**Setup:**
- Minecraft server(s) syncing to AT Protocol
- AppView service (separate deployment)
- Firehose subscription for real-time updates
- PostgreSQL for indexing
- Redis for caching

**Architecture:**
```
Minecraft Servers
    ↓ (publish records)
AT Protocol Network
    ↓ (Firehose subscription)
AppView Service
    ├─ Index to PostgreSQL
    ├─ Cache in Redis
    └─ Serve HTTP API
        ↓
    Bluesky Custom Feeds / Web Dashboard
```

---

### Scenario 4: Enterprise Deployment

**Setup:**
- Multiple Minecraft servers across regions
- Centralized session management
- Dedicated AppView cluster
- Full monitoring & alerting
- Database replication & backups

**Security:**
- All traffic encrypted (TLS)
- Rate limiting on all endpoints
- DDoS protection
- Security audit logging
- Regular security audits

---

## Installation & Configuration

### For Minecraft Server

**Step 1: Install Dependencies**
```bash
# Install Fabric Loader for 1.21.10
# Install Fabric API 0.138.4+1.21.10
# Install Fabric Language Kotlin 1.13.8+kotlin.2.3.0
```

**Step 2: Install Mod**
```bash
cp social-sync.jar mods/
```

**Step 3: Configure (Optional)**
Create `config/atproto-connect/config.json`:
```json
{
  "sync_interval_minutes": 60,
  "log_level": "INFO",
  "enable_security_audit": true,
  "rate_limit_attempts": 3,
  "rate_limit_window_minutes": 15,
  "rate_limit_lockout_minutes": 30
}
```

**Step 4: Start Server**
```bash
java -Xmx4G -jar server.jar nogui
```

**Step 5: Player Setup**
```
Player joins, then runs:
/atproto link alice.bsky.social
/atproto login alice.bsky.social abcd-1234-efgh-5678
/atproto sync stats on
```

---

## Monitoring & Operations

### Health Checks

**Mod Status:**
```
/atproto status
```

**Security Audit Log:**
```bash
tail -f config/atproto-connect/security-audit.log
```

---

### Common Operations

**Backup Configuration:**
```bash
cp -r config/atproto-connect/ backup/atproto-connect-$(date +%s)/
```

**Rotate Encryption Key** (requires re-encryption):
```bash
# Generate new key
# Re-encrypt all sessions with new key
# This is complex - should be automated
```

**Clear Expired Sessions:**
```bash
# Remove sessions older than 30 days
# Periodically clear stale data
```

---

## Performance Optimization

### Caching Strategy

**Session Cache:**
- TTL: 5 minutes
- Size: ~1KB per entry
- Invalidate on logout

**Identity Cache:**
- TTL: 60 minutes
- Size: ~100 bytes per entry
- Invalidate on link/unlink

---

### Database Indexing (for AppView)

```sql
-- For leaderboard queries
CREATE INDEX idx_stat_value ON indexed_records(statistic_key, value DESC);

-- For player queries
CREATE INDEX idx_player_uuid ON indexed_records(player_uuid);

-- For time-range queries
CREATE INDEX idx_synced_at ON indexed_records(synced_at DESC);

-- For search
CREATE INDEX idx_username ON indexed_records(username);
```

---

## Troubleshooting

### Issue: Sessions not persisting

**Cause:** `.encryption.key` missing or corrupted

**Solution:**
```bash
rm config/atproto-connect/.encryption.key
# Server will regenerate on next start
# Players must re-authenticate
```

---

### Issue: High memory usage

**Cause:** In-memory caches growing unbounded

**Solution:**
- Implement cache eviction policy
- Reduce cache TTL
- Monitor with `jmap -histo <pid>`

---

### Issue: Slow record creation

**Cause:** Network latency to PDS

**Solution:**
- Use Slingshot for fast PDS resolution
- Implement request batching
- Add local caching layer

---

## Scaling Considerations

### Vertical Scaling
- Increase Java heap: `-Xmx8G`
- Use NVMe for faster config I/O
- Scale to 100+ concurrent players

### Horizontal Scaling
- Run multiple Minecraft servers
- Share config via database
- Deploy AppView across multiple nodes

---

## Security Hardening

1. **File Permissions:**
   ```bash
   chmod 600 config/atproto-connect/.encryption.key
   chmod 600 config/atproto-connect/player-sessions.json
   chmod 700 config/atproto-connect/
   ```

2. **Network:**
   - Use TLS for AppView (https://...)
   - Whitelist CORS origins
   - Enable rate limiting

3. **Secrets:**
   - Rotate encryption keys regularly
   - Never commit config to version control
   - Use environment variables for sensitive data

---

## Backup & Recovery

### Backup Strategy

**Daily Backups:**
```bash
tar -czf backup-$(date +%Y%m%d).tar.gz config/atproto-connect/
```

**Offsite Storage:**
- S3/cloud storage for critical backups
- 30-day retention

### Recovery Procedure

1. Stop mod/server
2. Restore from backup
3. Verify file permissions
4. Restart server
5. Test player commands

---

## References

- [System Architecture](../README.md#architecture)
- [Security Guide](../README.md#authentication--security)
- [Configuration Files](../README.md#configuration-files)
- [API Reference](API_REFERENCE.md)
- [AppView Guide](APPVIEW.md)
