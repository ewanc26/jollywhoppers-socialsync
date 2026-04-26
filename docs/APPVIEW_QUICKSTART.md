# AppView Quick Start Guide

## For Server Operators

This guide explains how to set up and run the Minecraft AppView to display player data from your social-sync enabled server.

### What is an AppView?

An AppView is a service that:
- Indexes Minecraft data published to AT Protocol
- Provides APIs for querying leaderboards, player stats, and achievements
- Can be integrated with Bluesky custom feeds
- Runs independently from your Minecraft server

### Prerequisites

- Minecraft server with social-sync mod installed
- Players linking their AT Protocol identities
- Data being synced to AT Protocol (verify with `/atproto sync`)
- A server to host the AppView service

### Setup Steps

#### 1. Enable Data Syncing on Your Server

Ensure players are syncing data:

```bash
# In-game, ask players to run:
/atproto sync stats on
/atproto sync achievements on
/atproto sync sessions on
```

Or enable in the config screen: `Mod Menu → atproto-connect → Sync Preferences`

#### 2. Deploy the AppView Service

The AppView service needs to run separately from Minecraft. Here's the minimal setup:

**Option A: Using Docker**

```dockerfile
FROM openjdk:17
WORKDIR /app
COPY AppViewService.jar .
EXPOSE 8080
CMD ["java", "-jar", "AppViewService.jar"]
```

**Option B: Running Directly**

```bash
java -jar appview-service.jar \
  --port 8080 \
  --db-host localhost \
  --db-port 5432 \
  --db-name appview
```

#### 3. Set Up Data Subscription

The AppView needs to subscribe to updates. In production, this means:

1. **Subscribe to AT Protocol Firehose**: Real-time record updates
2. **Run scheduled queries**: Refresh leaderboards periodically
3. **Maintain a database**: Store indexed records

Simple implementation in Kotlin:

```kotlin
class FirehoseSubscriber(val appViewService: AppViewService) {
    suspend fun subscribe() {
        // Connect to AT Protocol firehose
        // Example: https://jetstream.atproto.tools/
        
        for (event in firehose.subscribe()) {
            if (event.commit?.collection?.startsWith("com.jollywhoppers.minecraft") == true) {
                appViewService.indexRecord(event)
            }
        }
    }
}
```

#### 4. Test the Service

Once running, test with:

```bash
# Check health
curl http://localhost:8080/health

# Query a player (replace UUID)
curl http://localhost:8080/player/550e8400-e29b-41d4-a716-446655440000

# Get leaderboard
curl http://localhost:8080/leaderboard/minecraft.mined.oak_log

# Search players
curl "http://localhost:8080/search?q=Alice"
```

#### 5. Make Public (Optional)

To integrate with Bluesky:

1. Deploy to a public URL (e.g., `https://minecraft-appview.example.com`)
2. Set up CORS for Bluesky requests
3. Register in the AT Protocol registry

```bash
# Register AppView endpoint
curl -X POST https://your-pds.example.com/xrpc/com.atproto.repo.createRecord \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "repo": "your.did",
    "collection": "app.bsky.graph.follow",
    "record": {
      "subject": "did:web:minecraft-appview.example.com"
    }
  }'
```

### Monitoring

Monitor your AppView with these metrics:

```bash
# Check indexing performance
tail -f logs/appview.log | grep "Indexed"

# Monitor API response times
curl -w "Time: %{time_total}s\n" http://localhost:8080/leaderboard/minecraft.mined.oak_log

# Database size
SELECT COUNT(*) FROM indexed_records;
```

### Configuration

Create `config.yaml`:

```yaml
server:
  port: 8080
  host: 0.0.0.0

database:
  host: localhost
  port: 5432
  name: appview
  user: appview
  password: secure_password

firehose:
  enabled: true
  endpoint: https://jetstream.atproto.tools
  reconnect_interval: 30

cache:
  enabled: true
  ttl_minutes: 60
  max_entries: 10000

logging:
  level: INFO
  file: logs/appview.log
```

### Common Issues

**Q: No data showing up**
- A: Ensure `/atproto sync` is enabled on players' clients
- A: Verify Firehose subscription is receiving events
- A: Check database is initialized and accessible

**Q: Leaderboard queries are slow**
- A: Add database indexes on statistic keys
- A: Implement Redis caching layer
- A: Pre-compute leaderboards on schedule

**Q: Players aren't finding data**
- A: Check player privacy settings (publicStats flag)
- A: Verify records were published to AT Protocol
- A: Review server logs for sync errors

### Performance Tips

1. **Database Indexes**
   ```sql
   CREATE INDEX idx_player_uuid ON indexed_records(player_uuid);
   CREATE INDEX idx_statistic_key ON indexed_records(statistic_key);
   CREATE INDEX idx_synced_at ON indexed_records(synced_at DESC);
   ```

2. **Caching Strategy**
   - Cache leaderboards for 10 minutes
   - Cache player profiles for 5 minutes
   - Cache trending achievements for 1 hour

3. **Query Optimization**
   - Paginate results (max 100 records)
   - Use time-based filtering (last 30 days)
   - Pre-compute common queries

4. **Resource Limits**
   - Connection pool: 20 connections
   - Request timeout: 30 seconds
   - Max result size: 10 MB

### Integration Examples

#### Bluesky Custom Feed

Embed AppView data in a Bluesky custom feed:

```javascript
// Fetch top builders from AppView
const topBuilders = await fetch(
  'https://minecraft-appview.example.com/leaderboard/minecraft.mined.oak_log?limit=20'
).then(r => r.json());

// Create feed posts from the data
topBuilders.data.forEach(entry => {
  createFeedItem({
    author: entry.username,
    text: `🏆 ${entry.username} is #${topBuilders.data.indexOf(entry) + 1} in block mining!`,
    metadata: {
      statistic: entry.statistic,
      value: entry.value
    }
  });
});
```

#### Web Dashboard

Display leaderboards on a website:

```html
<div id="leaderboard"></div>
<script>
fetch('https://minecraft-appview.example.com/leaderboard/minecraft.mined.oak_log')
  .then(r => r.json())
  .then(data => {
    const html = data.data.map((entry, i) => `
      <tr>
        <td>${i + 1}</td>
        <td>${entry.username}</td>
        <td>${entry.value}</td>
      </tr>
    `).join('');
    document.getElementById('leaderboard').innerHTML = `<table>${html}</table>`;
  });
</script>
```

### Production Checklist

- [ ] Database is backed up daily
- [ ] Firehose subscription has reconnection logic
- [ ] CORS is properly configured
- [ ] Rate limiting is enabled
- [ ] Logging is configured
- [ ] Monitoring alerts are set up
- [ ] SSL/HTTPS is enabled
- [ ] Privacy policies are published
- [ ] AppView is registered in AT Protocol registry
- [ ] Load testing has been performed

### Support

- Report bugs: Check the Issues page
- Ask questions: Start a Discussion
- See examples: `docs/examples/AppViewExample.kt`
- Full docs: `docs/APPVIEW.md`

### Next Steps

1. Deploy the AppView to your infrastructure
2. Subscribe to AT Protocol Firehose
3. Set up monitoring and alerts
4. Create custom feeds for your community
5. Share leaderboards with players!
