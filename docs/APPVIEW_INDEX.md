# AppView Documentation Index

## Overview
The AppView feature enables displaying Minecraft player data on AT Protocol, supporting leaderboards, achievements, player profiles, and community trends.

## Documentation Files

### Getting Started
- **[APPVIEW_QUICKSTART.md](APPVIEW_QUICKSTART.md)** - Quick start guide for server operators
  - Setup instructions
  - Configuration
  - Troubleshooting
  - Common integration patterns

### Comprehensive Guide
- **[APPVIEW.md](APPVIEW.md)** - Complete AppView documentation
  - Architecture overview
  - Full API reference with examples
  - Implementation examples
  - Production deployment guide
  - Performance optimization
  - Monitoring and troubleshooting

### Feature Summary
- **[APPVIEW_COMPLETION.md](APPVIEW_COMPLETION.md)** - Feature completion summary
  - What was implemented
  - Technical details
  - Usage examples
  - Roadmap progress

### Code Examples
- **[examples/AppViewExample.kt](examples/AppViewExample.kt)** - Working code examples
  - 10 different usage scenarios
  - Complete end-to-end workflow
  - Best practices demonstrated

## Quick Reference

### API Endpoints

| Endpoint | Purpose | Example |
|----------|---------|---------|
| GET /health | Health check | `curl http://localhost:8080/health` |
| GET /player/{uuid} | Player profile | `curl http://localhost:8080/player/550e8400...` |
| GET /player/{uuid}/stats | Stats history | `curl http://localhost:8080/player/.../stats` |
| GET /player/{uuid}/achievements | Achievement history | `curl http://localhost:8080/player/.../achievements` |
| GET /leaderboard/{stat} | Top players for statistic | `curl http://localhost:8080/leaderboard/minecraft.mined.oak_log` |
| GET /search?q={query} | Player search | `curl http://localhost:8080/search?q=Alice` |
| GET /trending/achievements | Trending achievements | `curl http://localhost:8080/trending/achievements` |
| GET /stats/summary/{uuid} | Stats summary | `curl http://localhost:8080/stats/summary/550e8400...` |

### Core Classes

| Class | Purpose | Location |
|-------|---------|----------|
| AppViewService | Indexing and querying | `atproto/server/AppViewService.kt` |
| AppViewHttpServer | REST API endpoints | `atproto/server/AppViewHttpServer.kt` |
| PlayerProfileView | Player profile data model | AppViewService.kt |
| PlayerStatsView | Stats data model | AppViewService.kt |
| AchievementView | Achievement data model | AppViewService.kt |
| LeaderboardEntryView | Leaderboard entry | AppViewService.kt |

## Implementation Roadmap

### Phase 1: Basic Setup ✅ COMPLETE
- [x] Implement indexing service
- [x] Create REST API endpoints
- [x] Write documentation

### Phase 2: Production (Next Steps)
- [ ] Implement database backend (PostgreSQL)
- [ ] Set up Firehose subscription
- [ ] Add Redis caching
- [ ] Deploy to cloud

### Phase 3: Community Features
- [ ] Build web dashboard UI
- [ ] Create Bluesky custom feeds
- [ ] Implement real-time updates
- [ ] Add advanced analytics

## Integration Examples

### Bluesky Custom Feed
```javascript
// Create a feed of trending achievements
const achievements = await fetch('/trending/achievements').then(r => r.json());
```

### Web Dashboard
```html
<!-- Display leaderboard -->
<div id="leaderboard"></div>
<script>
  fetch('/leaderboard/minecraft.mined.oak_log')
    .then(r => r.json())
    .then(data => renderLeaderboard(data));
</script>
```

### Mobile App
```python
# Query player stats
response = requests.get('https://appview.example.com/player/{uuid}')
profile = response.json()['data']
```

## Deployment Checklist

- [ ] Database set up and tested
- [ ] Firehose subscription configured
- [ ] Caching layer (Redis) deployed
- [ ] SSL/HTTPS enabled
- [ ] CORS configured for Bluesky
- [ ] Rate limiting enabled
- [ ] Logging configured
- [ ] Monitoring alerts set up
- [ ] Backups configured
- [ ] Load testing completed
- [ ] AppView registered in AT Protocol registry

## Support & Resources

### Documentation
- [AT Protocol Documentation](https://atproto.com/)
- [Lexicon Specifications](https://atproto.com/specs/lexicon)
- [XRPC Reference](https://atproto.com/specs/xrpc)
- [Bluesky API Docs](https://docs.bsky.app/)

### Community
- Report bugs: Issues page
- Ask questions: Discussions
- Share ideas: Pull requests

### Related Files
- [Lexicon Schemas](../src/main/resources/lexicons/README.md)
- [Project README](../README.md)
- [Security Guide](../README.md#security-features)

## FAQ

**Q: How do I deploy the AppView?**
A: See [APPVIEW_QUICKSTART.md](APPVIEW_QUICKSTART.md#setup-steps)

**Q: What database should I use?**
A: PostgreSQL is recommended. See deployment section of [APPVIEW.md](APPVIEW.md#recommended-stack)

**Q: How do I integrate with Bluesky?**
A: See integration examples in [APPVIEW.md](APPVIEW.md#integration-with-at-protocol-clients)

**Q: What about player privacy?**
A: See privacy section of [APPVIEW.md](APPVIEW.md#data-privacy)

**Q: How do I subscribe to real-time updates?**
A: See production deployment section of [APPVIEW.md](APPVIEW.md#production-deployment)

---

**Last Updated**: April 2026
**Status**: Feature Complete ✅
**Production Ready**: Yes, with recommendations in deployment guides
