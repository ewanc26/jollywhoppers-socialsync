# Social Sync Documentation Index

Welcome to the Social Sync documentation. This index helps you find the information you need.

## Getting Started

- **First Time?** Start with [README.md](../README.md)
- **Want to install?** See [Installation](../README.md#installation)
- **Want to set up AppView?** See [AppView Quick Start](APPVIEW_QUICKSTART.md)

## Documentation Structure

### For Users

| Document | Purpose | Audience |
|----------|---------|----------|
| [README.md](../README.md) | Project overview & features | Everyone |
| [APPVIEW_QUICKSTART.md](APPVIEW_QUICKSTART.md) | Deploy AppView service | Server Operators |
| [TEST_GUIDE.md](TEST_GUIDE.md) | Run and write tests | Developers |

### For Developers

| Document | Purpose | Audience |
|----------|---------|----------|
| [API_REFERENCE.md](API_REFERENCE.md) | Complete API documentation | API Developers |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design & internals | Core Developers |
| [TEST_GUIDE.md](TEST_GUIDE.md) | Testing framework & practices | QA Engineers |

### For System Administrators

| Document | Purpose | Audience |
|----------|---------|----------|
| [ARCHITECTURE.md](ARCHITECTURE.md#deployment-scenarios) | Deployment options | DevOps/Admins |
| [APPVIEW_QUICKSTART.md](APPVIEW_QUICKSTART.md) | AppView deployment | DevOps/Admins |
| [ARCHITECTURE.md](ARCHITECTURE.md#monitoring--operations) | Monitoring & operations | Operators |

### For Community

| Document | Purpose | Audience |
|----------|---------|----------|
| [APPVIEW.md](APPVIEW.md) | How AppView works | Community Members |
| [APPVIEW.md](APPVIEW.md#integration-with-at-protocol-clients) | Bluesky integration | Integrators |

## Quick Links by Topic

### Authentication & Security
- [Authentication Overview](../README.md#authentication--security)
- [API Authentication Methods](API_REFERENCE.md#authentication--session-management)
- [Security Best Practices](../README.md#security-best-practices)

### Commands
- [Command List](../README.md#available-commands)
- [Command Examples](../README.md#example-workflow)
- [Command System Architecture](ARCHITECTURE.md#1-command-system)

### Data Syncing
- [Data Syncing Overview](../README.md#data-syncing)
- [Sync Services Architecture](ARCHITECTURE.md#4-data-syncing-services)
- [Record Management API](API_REFERENCE.md#record-management)

### AppView
- [AppView Overview](APPVIEW.md#overview)
- [AppView API](APPVIEW.md#api-endpoints)
- [AppView Setup](APPVIEW_QUICKSTART.md#setup-steps)
- [AppView Architecture](ARCHITECTURE.md#33-record-management)

### Testing
- [Test Guide](TEST_GUIDE.md)
- [Running Tests](TEST_GUIDE.md#running-tests)
- [Writing Tests](TEST_GUIDE.md#writing-new-tests)

### Deployment
- [Installation](../README.md#installation)
- [Configuration](../README.md#configuration-files)
- [Deployment Guide](ARCHITECTURE.md#deployment-scenarios)
- [Server Setup](APPVIEW_QUICKSTART.md#setup-steps)

### Troubleshooting
- [Common Issues](ARCHITECTURE.md#troubleshooting)
- [AppView Issues](APPVIEW.md#troubleshooting)
- [Test Issues](TEST_GUIDE.md#troubleshooting)

## API Reference by Component

### Session Management
- [AtProtoSessionManager](API_REFERENCE.md#atprotosessionmanager) - Authentication & sessions
- [Session Data Model](API_REFERENCE.md#session-data-model) - Session structure

### Record Management
- [RecordManager](API_REFERENCE.md#recordmanager) - CRUD operations
- [Create Operations](API_REFERENCE.md#create-operations) - Creating records
- [Read Operations](API_REFERENCE.md#read-operations) - Retrieving records
- [Update Operations](API_REFERENCE.md#update-operations) - Updating records
- [Delete Operations](API_REFERENCE.md#delete-operations) - Deleting records

### Security
- [SecurityUtils](API_REFERENCE.md#securityutils) - Encryption & validation
- [SecurityAuditor](API_REFERENCE.md#securityauditor) - Event logging
- [RateLimiter](API_REFERENCE.md#ratelimiter) - Brute-force protection

### Storage
- [PlayerIdentityStore](API_REFERENCE.md#playeridentitystore) - UUID↔DID mapping
- [PlayerSyncPreferencesStore](API_REFERENCE.md#playersyncpreferencesstore) - Sync settings
- [Configuration Files](API_REFERENCE.md#configuration-files) - File storage

### AppView
- [AppViewService](APPVIEW.md#appviewservice) - Indexing & querying
- [AppViewHttpServer](APPVIEW.md#appviewhttpserver) - HTTP API
- [Query Endpoints](APPVIEW.md#query-operations) - Available queries

## Code Examples

### Quick Examples
- [AppView Examples](examples/AppViewExample.kt) - 10 working scenarios
- [Record Creation](examples/RecordCreationExample.kt) - Creating records
- [Record Manager](examples/RecordManagerExamples.kt) - CRUD operations

### Advanced Topics
- [End-to-End Workflow](ARCHITECTURE.md#example-2-stats-syncing-flow) - Complete data flow
- [Custom AppView](APPVIEW_QUICKSTART.md#integration-examples) - Building integrations
- [Performance Optimization](ARCHITECTURE.md#performance-optimization) - Tuning

## Glossary

| Term | Definition |
|------|-----------|
| **AT Protocol** | Decentralized social protocol powering Bluesky |
| **DID** | Decentralized Identifier (user identity on AT Protocol) |
| **Handle** | Human-readable username (e.g., alice.bsky.social) |
| **PDS** | Personal Data Server (stores user's data) |
| **AppView** | Service that displays/queries published records |
| **Lexicon** | Schema definitions for records |
| **XRPC** | AT Protocol's RPC mechanism |
| **TID** | Timestamp-based ID for records |
| **rkey** | Record key (TID or "self" for literal records) |

## File Structure

```
docs/
├── README.md                    # This index
├── API_REFERENCE.md            # Complete API documentation (500+ lines)
├── ARCHITECTURE.md             # System design & deployment (600+ lines)
├── APPVIEW.md                  # AppView guide (600+ lines)
├── APPVIEW_QUICKSTART.md       # AppView setup (400+ lines)
├── APPVIEW_INDEX.md            # AppView documentation index
├── APPVIEW_COMPLETION.md       # Feature completion summary
├── TEST_GUIDE.md               # Testing documentation (300+ lines)
└── examples/
    ├── AppViewExample.kt       # 10 working examples
    ├── RecordCreationExample.kt
    └── RecordManagerExamples.kt

src/
├── main/
│   ├── kotlin/com/jollywhoppers/
│   │   ├── socialsync.kt              # Main initializer
│   │   └── atproto/
│   │       ├── server/
│   │       │   ├── AppViewService.kt      # AppView indexing
│   │       │   ├── AppViewHttpServer.kt   # AppView HTTP API
│   │       │   ├── RecordManager.kt       # Record CRUD
│   │       │   ├── At*.kt                 # Core services
│   │       │   └── *SyncService.kt        # Sync services
│   │       ├── security/
│   │       │   ├── SecurityUtils.kt
│   │       │   ├── RateLimiter.kt
│   │       │   └── SecurityAuditor.kt
│   │       └── client/
│   │           └── *
│   └── resources/
│       ├── lexicons/           # Lexicon schemas
│       └── assets/
└── test/
    └── kotlin/com/jollywhoppers/
        ├── CoreTests.kt        # Main test suite (20+ tests)
        └── ...
```

## Documentation Statistics

| Metric | Value |
|--------|-------|
| Total Documentation | 3,500+ lines |
| API Reference | 500+ lines |
| Architecture Guide | 600+ lines |
| Test Guide | 300+ lines |
| Code Examples | 10 complete scenarios |
| Test Coverage | 20+ tests |

## Contributing to Documentation

If you're adding a new feature:

1. **Add API Documentation** - Document new methods in API_REFERENCE.md
2. **Add Examples** - Create example code in docs/examples/
3. **Add Tests** - Add test cases in src/test/
4. **Update Architecture** - Update ARCHITECTURE.md if it changes system design
5. **Add to Index** - Link from this index

## Maintenance Schedule

| Task | Frequency |
|------|-----------|
| Update examples | Monthly |
| Review API docs | Quarterly |
| Update architecture | As needed |
| Audit test coverage | Monthly |

## Support

**Questions?**
- Check the index above
- Search in relevant documentation
- Check code examples
- Review troubleshooting sections

**Found an issue?**
- Report in Issues
- Include documentation link
- Suggest improvements

**Want to contribute?**
- Follow contribution guidelines
- Submit pull request with docs
- Link to this index from new docs

## Additional Resources

### External Links
- [AT Protocol Documentation](https://atproto.com/)
- [Bluesky Documentation](https://docs.bsky.app/)
- [Lexicon Specification](https://atproto.com/specs/lexicon)
- [XRPC Reference](https://atproto.com/specs/xrpc)

### Related Docs
- [Project README](../README.md)
- [License](../LICENSE)
- [Contributing Guide](../README.md#contributing)

---

**Last Updated**: April 2026
**Version**: 0.5.0
**Status**: Complete ✅

## Quick Navigation

- [🏠 Home](../README.md)
- [📚 Full Docs](../docs/)
- [🔧 API Reference](API_REFERENCE.md)
- [🏗️ Architecture](ARCHITECTURE.md)
- [🧪 Tests](TEST_GUIDE.md)
- [👀 AppView](APPVIEW.md)
- [💾 Examples](examples/)
