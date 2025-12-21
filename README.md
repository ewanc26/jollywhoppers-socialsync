# atproto-connect

A Fabric mod for Minecraft 1.21.10 that bridges the game with the AT Protocol, enabling decentralized data synchronization and social features.

## Project Status

**This project is in active development and is NOT ready for production use.** Identity linking and authentication are now fully implemented with enterprise-grade security features. Stat syncing and other data collection features are planned but not yet implemented.

## Overview

atproto-connect aims to integrate Minecraft gameplay with the AT Protocol (the protocol powering Bluesky), allowing game data to be synced to AT Protocol lexicons. This enables decentralized storage and sharing of Minecraft data across the federated network.

## Current Features

### Identity Linking & Authentication ✓

Players can link their Minecraft accounts to their AT Protocol identities and authenticate to enable data syncing:

**Basic Commands:**

- **`/atproto link <handle or DID>`** - Link your Minecraft UUID to your AT Protocol identity (no login required)
- **`/atproto login <handle> <app-password>`** - Authenticate to enable data syncing
- **`/atproto logout`** - Remove authentication (keeps identity link)
- **`/atproto unlink`** - Remove identity link and authentication
- **`/atproto whoami`** - View your linked identity and auth status
- **`/atproto status`** - Quick status check
- **`/atproto whois <player or handle>`** - Look up another player's identity

**Example Workflow:**

```plaintext
# 1. Link your identity (read-only)
/atproto link alice.bsky.social
✓ Successfully linked to AT Protocol!
  Handle: alice.bsky.social
  DID: did:plc:abcdef123456
  PDS: https://morel.us-east.host.bsky.network

# 2. Authenticate with an App Password
/atproto login alice.bsky.social abcd-1234-efgh-5678
✓ Successfully authenticated!
You can now sync your Minecraft data to AT Protocol!

# 3. Check your status
/atproto whoami
━━━ Your AT Protocol Identity ━━━
Handle: alice.bsky.social
DID: did:plc:abcdef123456
Linked: 5 minutes ago
Last Verified: 5 minutes ago

Authentication: ✓ Active
You can sync data to AT Protocol
```

### Security Features

The mod implements enterprise-grade security measures to protect player data and prevent abuse:

- **AES-256-GCM Encryption**: Session data encrypted at rest with server-specific keys
- **Rate Limiting**: 3 authentication attempts per 15 minutes, 30-minute lockout on abuse
- **Security Audit Logging**: All sensitive operations logged for monitoring
- **Sanitized Error Messages**: No sensitive data exposed in error messages or logs
- **Restricted File Permissions**: Owner-only access to configuration files
- **Atomic File Writes**: Prevents corruption during saves
- **Thread-Safe Operations**: Concurrent access protection for all shared data
- **Automatic Token Refresh**: Access tokens refreshed before expiration
- **Path Validation**: Protection against directory traversal attacks

All passwords are handled securely - app passwords are never logged or stored (only the resulting JWT tokens are kept).

### Key Features

- **Slingshot Integration**: Uses [Slingshot by Microcosm](https://slingshot.microcosm.blue) for fast, cached PDS resolution
- **App Password Support**: Secure authentication using AT Protocol app passwords (never use your main password!)
- **Automatic Token Refresh**: Access tokens are automatically refreshed before expiration
- **Multi-PDS Support**: Works with any AT Protocol PDS, not just Bluesky
- **Persistent Sessions**: Authentication survives server restarts
- **Encrypted Storage**: Session data protected with AES-256-GCM encryption

### Getting an App Password

1. Go to your AT Protocol account settings (e.g., Bluesky Settings → Privacy and Security → App Passwords)
2. Create a new app password with a descriptive name (e.g., "Minecraft Server")
3. Copy it immediately (you won't see it again!)
4. Use it in `/atproto login`
5. **Never share your app password or use your main account password!**

### Future Possibilities

- Automatic stat syncing at configurable intervals
- Achievement announcements via AT Protocol feeds
- Cross-server player reputation systems
- Privacy controls for what data gets synced
- In-game social features tied to AT Protocol identities

## Technical Stack

- **Minecraft Version**: 1.21.10
- **Mod Loader**: Fabric API
- **Protocol**: AT Protocol (atproto)
- **Language**: Kotlin (with Java interop)
- **Build System**: Gradle 8.x
- **Dependencies**:
  - Fabric Loader 0.18.3
  - Fabric API 0.138.4+1.21.10
  - Fabric Language Kotlin 1.13.8+kotlin.2.3.0
  - kotlinx-serialization for JSON handling
  - kotlinx-coroutines for async operations
- **Identity Resolution**: [Slingshot](https://slingshot.microcosm.blue) by Microcosm
- **Authentication**: AT Protocol OAuth/App Passwords

## Installation

### For Users

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.10
2. Download and install [Fabric API](https://modrinth.com/mod/fabric-api) version 0.138.4+1.21.10 or compatible
3. Download and install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) version 1.13.8+kotlin.2.3.0 or compatible
4. Place the atproto-connect JAR in your `mods` folder
5. Launch the game and use `/atproto help` to see available commands

### For Developers

Clone the repository:

```bash
git clone git@tangled.sh:ewancroft.uk/atproto-connect
cd atproto-connect
```

Build the project:

```bash
./gradlew build
```

The built JAR will be in `build/libs/`.

For development with auto-reload:

```bash
./gradlew runClient
```

## Project Structure

```plaintext
src/main/
├── kotlin/com/jollywhoppers/
│   ├── Atprotoconnect.kt                    # Main mod initializer
│   └── atproto/
│       ├── AtProtoClient.kt                 # HTTP client with Slingshot integration
│       ├── AtProtoSessionManager.kt         # Authentication & token management
│       ├── AtProtoCommands.kt               # Command handlers
│       ├── PlayerIdentityStore.kt           # UUID<->DID mapping storage
│       ├── security/
│       │   ├── RateLimiter.kt              # Rate limiting for auth attempts
│       │   ├── SecurityAuditor.kt          # Security event logging
│       │   └── SecurityUtils.kt            # Cryptography & path validation
│       └── examples/
│           └── RecordCreationExample.kt    # Example code for data syncing
└── resources/
    ├── fabric.mod.json                      # Mod metadata
    └── lexicons/                            # Lexicon schemas
        ├── README.md                        # Lexicon documentation
        ├── com.jollywhoppers.minecraft.defs.json
        ├── com.jollywhoppers.minecraft.player.profile.json
        ├── com.jollywhoppers.minecraft.player.stats.json
        ├── com.jollywhoppers.minecraft.player.session.json
        ├── com.jollywhoppers.minecraft.achievement.json
        ├── com.jollywhoppers.minecraft.leaderboard.json
        └── com.jollywhoppers.minecraft.server.status.json
```

## Lexicon Schemas

The mod defines several AT Protocol lexicon schemas under the `com.jollywhoppers.minecraft.*` namespace:

- **Player Profile** (`literal:self`) - Links Minecraft UUIDs to AT Protocol DIDs with privacy controls
- **Player Stats** (`tid`) - Snapshots of player statistics for leaderboards
- **Player Sessions** (`tid`) - Play session tracking (join/leave times)
- **Achievements** (`tid`) - Records of earned achievements/advancements
- **Leaderboards** (`tid`) - Pre-computed leaderboard entries
- **Server Status** (`literal:self`) - Server information and status

See `src/main/resources/lexicons/README.md` for detailed schema documentation.

## Architecture

```plaintext
Player Commands (/atproto login, /atproto link, etc.)
    ↓
AtProtoCommands (Kotlin Coroutines)
    ↓
┌────────────────────────────────────────┐
│    AtProtoSessionManager               │
│    • Authentication & Token Storage     │
│    • AES-256-GCM Encryption            │
│    • Automatic Token Refresh           │
└────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────┐
│         AtProtoClient                  │
│    • HTTP + XRPC + Slingshot          │
│    • Identity Resolution               │
└────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────┐
│    Security Layer                      │
│    • RateLimiter (3/15min)            │
│    • SecurityAuditor (Event Logging)   │
│    • SecurityUtils (Crypto & Validation)│
└────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────┐
│    Slingshot (Microcosm Blue)         │
│    Fast PDS Resolution & Caching       │
└────────────────────────────────────────┘
    ↓
AT Protocol Network
    - Player's PDS (Data & Auth)
    - plc.directory (DID Resolution)
    ↓
Local Storage
    - player-identities.json (UUID↔DID mappings)
    - player-sessions.json (Encrypted auth tokens)
    - .encryption.key (AES-256 key)
    - security-audit.log (Security events)
```

## Authentication & Security

### How It Works

1. **Link Identity**: Players link their Minecraft UUID to their AT Protocol DID (read-only, no login required)
2. **Authenticate**: Players log in with their handle and an app password to create an authenticated session
3. **Token Management**: The mod stores JWT access and refresh tokens securely with AES-256-GCM encryption
4. **Auto-Refresh**: Access tokens are automatically refreshed before expiration
5. **Data Syncing**: Authenticated players will be able to sync their Minecraft data to their PDS (coming soon)

### Security Best Practices

**For Players:**

- **Always use App Passwords**, never main account passwords
- Create a unique app password for each Minecraft server
- Revoke unused app passwords regularly
- If you suspect compromise, revoke the app password immediately

**For Server Operators:**

- Secure the `config/atproto-connect/` directory with appropriate file permissions
- Monitor `security-audit.log` for suspicious activity
- Keep the `.encryption.key` file secure (never share or commit to version control)
- Regular backups of configuration files
- Update the mod regularly for security patches

### Token Storage

- **Location**: `config/atproto-connect/player-sessions.json`
- **Encryption**: AES-256-GCM with server-specific key
- **Contents**: Access and refresh JWTs for authenticated players
- **Security**: Encrypted at rest, owner-only file permissions
- **Lifetime**: Access tokens expire after ~2 hours, refresh tokens last longer

### Rate Limiting

To prevent brute-force attacks:

- **Maximum Attempts**: 3 failed login attempts
- **Time Window**: 15 minutes
- **Lockout Duration**: 30 minutes after exceeding limit
- **Tracking**: Per-player UUID and per-identifier

### Security Audit Log

All security-sensitive operations are logged to `config/atproto-connect/security-audit.log`:

- Authentication attempts (success/failure)
- Rate limit violations
- Session creation/deletion
- Token refresh operations
- Security errors

## Configuration Files

All configuration files are stored in `config/atproto-connect/`:

- **`player-identities.json`** - UUID to DID/handle mappings (plaintext as these are both publicly accessible anyway)
- **`player-sessions.json`** - Encrypted authentication sessions
- **`.encryption.key`** - AES-256 encryption key (auto-generated, keep secure!)
- **`security-audit.log`** - Security event log

**Important**: Never commit `.encryption.key` or `player-sessions.json` to version control!

## Development Roadmap

- [x] Design lexicon schemas for Minecraft data types
- [x] Implement AT Protocol client with Slingshot integration
- [x] Create identity linking system
- [x] Implement authentication with app passwords
- [x] Build session management with automatic token refresh
- [x] Add encryption for session storage
- [x] Implement rate limiting and security auditing
- [ ] Build data collection hooks for player statistics
- [ ] Implement authenticated record creation (writing stats)
- [ ] Add automatic stat syncing at configurable intervals
- [ ] Add privacy controls and data filtering options
- [ ] Create example AppView for displaying Minecraft data
- [ ] Implement OAuth device flow for better UX
- [ ] Add DPoP support
- [ ] Write comprehensive documentation
- [ ] Add automated tests
- [ ] Publish to Modrinth/CurseForge

## Contributing

This project is in active development. If you're interested in contributing:

1. Check the Issues page for open tasks
2. Fork the repository
3. Create a feature branch
4. Submit a pull request with a clear description

Please follow Kotlin coding conventions and include tests for new features.

## Resources

### AT Protocol

- [AT Protocol Documentation](https://atproto.com/)
- [Lexicon Specifications](https://atproto.com/specs/lexicon)
- [XRPC API Reference](https://atproto.com/specs/xrpc)
- [OAuth Specification](https://atproto.com/specs/oauth)
- [Bluesky API Docs](https://docs.bsky.app/)

### Microcosm

- [Slingshot Documentation](https://slingshot.microcosm.blue/)
- [Microcosm Project](https://microcosm.blue/)

### Minecraft Modding

- [Fabric Documentation](https://fabricmc.net/wiki/)
- [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin)

## License

[CC0-1.0](LICENSE) - Public Domain

This project is released into the public domain. You are free to use, modify, and distribute this software for any purpose without restriction.

## Disclaimer

This is an experimental project exploring the intersection of decentralized protocols and gaming. It is not affiliated with or endorsed by Mojang, Microsoft, or the official AT Protocol team.

## Acknowledgments

- [Microcosm](https://microcosm.blue) for Slingshot, which makes PDS resolution fast and reliable
- The AT Protocol team for building an open, decentralized social network protocol
- The Fabric community for excellent mod development tools
- The Kotlin community for a great language and ecosystem

---

**Version**: 0.0.1  
**Repository**: `git@tangled.sh:ewancroft.uk/atproto-connect`  
**Status**: Alpha - Not Production Ready
