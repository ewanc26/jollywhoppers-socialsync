# AppView Guide: Displaying Minecraft Data on AT Protocol

## Overview

An **AppView** is a custom service that indexes AT Protocol records and provides rich display and query capabilities. The social-sync AppView allows players and community members to:

- 📊 **View leaderboards** of top players by various statistics
- 🏆 **Browse achievements** earned across the community
- 👤 **See player profiles** with stats summaries
- 🔥 **Discover trending** achievements and active players
- 🔍 **Search** for players by username or display name

## Architecture

```
AT Protocol Network
  ↓ (Published Records)
Firehose Subscription
  ↓ (Real-time Events)
AppView Service (Index & Query)
  ↓ (HTTP Endpoints)
Web Clients / Bluesky Custom Feeds
```

### Components

1. **AppViewService**: In-memory indexing and querying (uses database in production)
2. **AppViewHttpServer**: REST API for serving data to clients
3. **Firehose Subscription**: Real-time updates from AT Protocol
4. **Data Models**: Records synced from Minecraft via social-sync mod

## How It Works

### 1. Publishing Records

When a player syncs data with the social-sync mod:

```kotlin
// Player syncs their stats
val stats = PlayerStatsRecord(
    player = PlayerReference("uuid", "AlicePlayer"),
    statistics = listOf(
        Statistic("minecraft.mined.oak_log", 1250)
    ),
    playtimeMinutes = 7200,
    level = 34,
    syncedAt = now()
)

// Record published to AT Protocol
recordManager.createRecord(playerUuid, "com.jollywhoppers.minecraft.player.stats", stats)
// Result: at://did:plc:alice/com.jollywhoppers.minecraft.player.stats/8l6rvp4j6d3e2c4b9
```

### 2. Indexing Records

The AppView subscribes to repository events and indexes published records:

```kotlin
appViewService.indexPlayerStats(uri, statsJson)
// Now searchable and queryable through leaderboards
```

### 3. Querying Data

Clients query the AppView through HTTP endpoints:

```bash
# Get player stats
curl https://appview.example.com/player/550e8400-e29b-41d4-a716-446655440000/stats

# Get leaderboard
curl https://appview.example.com/leaderboard/minecraft.mined.oak_log

# Search players
curl "https://appview.example.com/search?q=Alice"
```

## API Endpoints

### Health Check

```
GET /health

Response:
{
  "success": true,
  "data": {
    "status": "healthy",
    "version": "1.0.0",
    "uptime": 3600
  }
}
```

### Get Player Profile

```
GET /player/{uuid}

Response:
{
  "success": true,
  "data": {
    "profile": {
      "did": "did:plc:alice123",
      "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
      "username": "AlicePlayer",
      "displayName": "Alice",
      "bio": "Minecraft enthusiast and builder",
      "publicStats": true,
      "publicSessions": true,
      "createdAt": "2026-04-20T10:30:00Z"
    },
    "latestStats": {
      "level": 34,
      "playtimeMinutes": 7200,
      "gamemode": "survival"
    },
    "statsCount": 5,
    "achievementCount": 23
  },
  "timestamp": 1703004000000
}
```

### Get Player Stats History

```
GET /player/{uuid}/stats?limit=10&offset=0

Response:
{
  "success": true,
  "data": [
    {
      "uri": "at://did:plc:alice123/.../8l6rvp4j6d3e2c4b9",
      "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
      "username": "AlicePlayer",
      "server": "Main SMP",
      "level": 34,
      "playtimeMinutes": 7200,
      "gamemode": "survival",
      "statistics": [
        {"key": "minecraft.mined.oak_log", "value": 1250},
        {"key": "minecraft.killed.zombie", "value": 425}
      ],
      "syncedAt": "2026-04-25T14:22:00Z"
    }
  ],
  "pagination": {
    "limit": 10,
    "offset": 0,
    "count": 1
  }
}
```

### Get Player Achievements

```
GET /player/{uuid}/achievements?limit=25&offset=0

Response:
{
  "success": true,
  "data": [
    {
      "uri": "at://did:plc:alice123/.../8l6rvp4j6d3e2c5a7",
      "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
      "username": "AlicePlayer",
      "server": "Main SMP",
      "achievementId": "minecraft:adventure/kill_a_mob",
      "achievementName": "Monster Hunter",
      "achievementDescription": "Kill any type of monster",
      "category": "adventure",
      "isChallenge": false,
      "achievedAt": "2026-04-24T15:45:00Z"
    }
  ],
  "pagination": {
    "limit": 25,
    "offset": 0,
    "count": 1
  }
}
```

### Get Leaderboard

```
GET /leaderboard/{statistic}?limit=20

Example: /leaderboard/minecraft.mined.oak_log

Response:
{
  "success": true,
  "data": [
    {
      "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
      "username": "AlicePlayer",
      "server": "Main SMP",
      "statistic": "minecraft.mined.oak_log",
      "value": 5250,
      "recordedAt": "2026-04-25T14:22:00Z"
    },
    {
      "playerUuid": "660e8400-e29b-41d4-a716-446655440001",
      "username": "BobBuilder",
      "server": "Main SMP",
      "statistic": "minecraft.mined.oak_log",
      "value": 4100,
      "recordedAt": "2026-04-25T13:15:00Z"
    }
  ],
  "pagination": {
    "limit": 20,
    "offset": 0,
    "count": 2
  }
}
```

### Search Players

```
GET /search?q={query}

Example: /search?q=Alice

Response:
{
  "success": true,
  "data": [
    {
      "did": "did:plc:alice123",
      "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
      "username": "AlicePlayer",
      "displayName": "Alice",
      "bio": "Minecraft enthusiast and builder",
      "publicStats": true,
      "publicSessions": true
    }
  ],
  "timestamp": 1703004000000
}
```

### Get Trending Achievements

```
GET /trending/achievements?limit=10

Response:
{
  "success": true,
  "data": [
    {
      "achievementId": "minecraft:adventure/kill_a_mob",
      "achievementName": "Monster Hunter",
      "category": "adventure",
      "timesEarned": 47,
      "recentlyEarnedBy": ["AlicePlayer", "BobBuilder", "Charlie"]
    },
    {
      "achievementId": "minecraft:story/mine_stone",
      "achievementName": "Stone Age",
      "category": "story",
      "timesEarned": 89,
      "recentlyEarnedBy": ["Diana", "Eve", "Frank"]
    }
  ],
  "timestamp": 1703004000000
}
```

### Get Stats Summary

```
GET /stats/summary/{uuid}

Response:
{
  "success": true,
  "data": {
    "playerUuid": "550e8400-e29b-41d4-a716-446655440000",
    "username": "AlicePlayer",
    "playtimeMinutes": 7200,
    "level": 34,
    "gamemode": "survival",
    "server": "Main SMP",
    "topStatistics": [
      {"key": "minecraft.mined.oak_log", "value": 5250},
      {"key": "minecraft.mined.stone", "value": 4100},
      {"key": "minecraft.killed.zombie", "value": 425}
    ],
    "lastSyncedAt": "2026-04-25T14:22:00Z"
  },
  "timestamp": 1703004000000
}
```

## Implementation Examples

### Example 1: Index a Player Profile

```kotlin
val profileRecord = json.parseToJsonElement("""
{
  "$type": "com.jollywhoppers.minecraft.player.profile",
  "player": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "username": "AlicePlayer"
  },
  "displayName": "Alice",
  "bio": "Minecraft enthusiast",
  "publicStats": true,
  "publicSessions": true,
  "createdAt": "2026-04-20T10:30:00Z"
}
""")

appViewService.indexPlayerProfile(
  uri = "at://did:plc:alice123/com.jollywhoppers.minecraft.player.profile/self",
  record = profileRecord
)
```

### Example 2: Query Leaderboard

```kotlin
val leaderboard = appViewService.getLeaderboard(
  statisticKey = "minecraft.mined.oak_log",
  limit = 10
).getOrNull()

leaderboard?.forEach { entry ->
  println("${entry.username}: ${entry.value} blocks")
}
```

### Example 3: Search for Players

```kotlin
val results = appViewService.searchPlayers("Alice").getOrNull()

results?.forEach { player ->
  println("${player.username} - ${player.displayName}")
}
```

## Production Deployment

### Recommended Stack

- **Framework**: Ktor or Spring Boot for HTTP server
- **Database**: PostgreSQL for record indexing and caching
- **Cache**: Redis for leaderboard and trending queries
- **Messaging**: AT Protocol Firehose for real-time updates
- **Hosting**: Docker container on cloud provider

### Key Considerations

1. **Indexing**: Subscribe to the AT Protocol Firehose to capture published records
2. **Databases**: Use PostgreSQL to store indexed records for fast queries
3. **Caching**: Cache leaderboards and trending data with Redis
4. **Pagination**: Implement cursor-based pagination for large result sets
5. **Filtering**: Support filtering by server, time period, or category
6. **Performance**: Add indexes on frequently queried fields
7. **Privacy**: Respect `publicStats` and `publicSessions` flags
8. **Registration**: Register your AppView in the AT Protocol registry

### Sample Ktor Implementation

```kotlin
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*

fun main() {
    embeddedServer(Netty, port = 8080) {
        routing {
            get("/player/{uuid}") {
                val uuid = call.parameters["uuid"]
                val profile = appViewService.getPlayerProfile(uuid!!)
                call.respond(httpServer.handleGetPlayerProfile(uuid))
            }
            
            get("/leaderboard/{stat}") {
                val stat = call.parameters["stat"]
                val limit = call.parameters["limit"]?.toInt() ?: 20
                call.respond(httpServer.handleGetLeaderboard(stat!!, limit.toString()))
            }
        }
    }.start(wait = true)
}
```

## Integration with AT Protocol Clients

### Bluesky Custom Feeds

AppViews can be used to create custom feeds in Bluesky:

- **"Top Builders"**: Feed of recent high-value block mining achievements
- **"Achievement Feeds"**: Achievements in specific categories
- **"Speedrun Records"**: Fastest times for specific challenges
- **"Server Highlights"**: Notable stats from specific servers

### Web Dashboard

Embed AppView data in a custom web dashboard:

```html
<div id="leaderboard">
  <!-- JavaScript fetches from /leaderboard/minecraft.mined.oak_log -->
</div>
```

## Data Privacy

- **Public by Default**: All AT Protocol data is public
- **Opt-in Syncing**: Players control sync consent in `/atproto sync`
- **Respect Flags**: Check `publicStats` and `publicSessions` before displaying
- **Anonymous Option**: Players can disable syncing entirely

## Monitoring

Monitor your AppView with these metrics:

- **Indexing Latency**: Time from publish to index
- **Query Performance**: Response times for popular queries
- **Cache Hit Ratio**: Percentage of queries served from cache
- **Record Count**: Total indexed records by type
- **Active Players**: Number of players with data published

## Troubleshooting

### No Data in Queries

1. Ensure Firehose subscription is active and receiving events
2. Verify records are being published by players (check `/atproto sync` settings)
3. Check indexing function is being called on record events

### Slow Leaderboard Queries

1. Add database indexes on statistic keys
2. Implement caching layer (Redis)
3. Consider pre-computing leaderboards periodically

### Missing Records

1. Verify player privacy settings allow public stats
2. Check server subscription is capturing all record types
3. Review error logs for indexing failures

## Examples

See `docs/examples/AppViewExample.kt` for complete working examples.

## References

- [AT Protocol AppView Documentation](https://atproto.com/specs/app-view)
- [Lexicon Specifications](https://atproto.com/specs/lexicon)
- [XRPC API Reference](https://atproto.com/specs/xrpc)
- [Bluesky Custom Feeds](https://docs.bsky.app/docs/tutorials/custom-feeds)

## Next Steps

1. **Deploy AppView service** to a public URL
2. **Subscribe to Firehose** for real-time record updates
3. **Set up database** for production indexing
4. **Register AppView** in AT Protocol registry
5. **Publish to Bluesky** for community integration
