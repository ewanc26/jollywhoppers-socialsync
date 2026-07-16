# AT Protocol Integration

This package contains the core AT Protocol integration for Social Sync, enabling Minecraft players to link their game accounts to their AT Protocol identities and authenticate to sync data.

## Components

### AtProtoClient.kt
Enhanced HTTP client for interacting with AT Protocol services. Provides methods for:

- **Slingshot Integration**: Uses [Slingshot](https://slingshot.microcosm.blue) for fast, cached PDS resolution via `resolveMiniDoc`
- **Handle Resolution**: Convert AT Protocol handles (e.g., `alice.bsky.social`) to DIDs
- **DID Resolution**: Resolve DIDs to DID Documents (supports `did:plc` and `did:web`)
- **Profile Retrieval**: Fetch user profiles from the AT Protocol network
- **Session Management**: Create and refresh authenticated sessions
- **XRPC Requests**: Make authenticated API calls to PDS instances

The client uses Java's built-in `HttpClient` for HTTP requests and `kotlinx.serialization` for JSON parsing. It automatically falls back to standard resolution methods if Slingshot is unavailable.

### PlayerIdentityStore.kt
Manages the persistent mapping between Minecraft UUIDs and AT Protocol DIDs. Features:

- **In-Memory Cache**: Uses `ConcurrentHashMap` for fast lookups
- **Disk Persistence**: Stores identities in JSON format
- **Bidirectional Lookup**: Find UUID by DID/handle, or DID/handle by UUID
- **Verification Tracking**: Records when identities were linked and last verified

The storage file is located at `config/atproto-connect/player-identities.json`.

### AtProtoSessionManager.kt
Manages authenticated AT Protocol sessions for players. Features:

- **Token Storage**: Securely stores access and refresh tokens
- **Automatic Refresh**: Refreshes access tokens before expiration
- **Session Lifecycle**: Handles login, logout, and token management
- **Authenticated Requests**: Provides helper methods for making authenticated XRPC calls

Sessions are stored at `config/atproto-connect/player-sessions.json`.

### AtProtoCommands.kt
Brigadier command handler providing in-game commands:

#### `/atproto link <handle or DID>`
Links the player's Minecraft UUID to their AT Protocol identity (no authentication required).
- Accepts either a handle (`alice.bsky.social`) or DID (`did:plc:...`)
- Validates the identity exists on the AT Protocol network
- Resolves PDS URL via Slingshot
- Stores the mapping for future use

**Example:**
```
/atproto link alice.bsky.social
✓ Successfully linked to AT Protocol!
  Handle: alice.bsky.social
  DID: did:plc:abcdef123456
  PDS: https://morel.us-east.host.bsky.network
```

#### `/atproto login <handle> <app-password>`
Authenticates the player to enable data syncing.
- **IMPORTANT**: Always use an App Password, never your main account password!
- Creates an authenticated session with the player's PDS
- Stores access and refresh tokens securely
- Enables stat syncing and record creation

**Getting an App Password:**
1. Go to your AT Protocol account settings
2. Navigate to App Passwords
3. Create a new app password with a descriptive name (e.g., "Minecraft Server")
4. Copy the password immediately (you won't see it again!)
5. Use it in the login command

**Example:**
```
/atproto login alice.bsky.social abcd-1234-efgh-5678
✓ Successfully authenticated!
  Handle: alice.bsky.social
  DID: did:plc:abcdef123456
  PDS: https://morel.us-east.host.bsky.network

You can now sync your Minecraft data to AT Protocol!
```

#### `/atproto logout`
Removes the player's authenticated session (but keeps their identity link).

#### `/atproto unlink`
Removes both the identity link and any authenticated session.

#### `/atproto whoami`
Displays the player's linked AT Protocol identity and authentication status.

#### `/atproto status`
Shows a quick overview of identity and authentication status.

#### `/atproto whois <player or handle>`
Looks up another player's AT Protocol identity.

## Architecture

```
Player Command
    ↓
AtProtoCommands (Coroutine Scope)
    ↓
┌─────────────────────────────┐
│   AtProtoSessionManager     │
│  (Token Management)         │
└─────────────────────────────┘
    ↓
┌─────────────────────────────┐
│      AtProtoClient          │
│  (HTTP + Slingshot)         │
└─────────────────────────────┘
    ↓
┌─────────────────────────────┐
│   Slingshot (Microcosm)     │
│  PDS Resolution & Caching   │
└─────────────────────────────┘
    ↓
AT Protocol Network
    - Player's PDS (Authentication)
    - plc.directory (DID Resolution)
    ↓
PlayerIdentityStore (Persistence)
```

## PDS Resolution with Slingshot

The mod uses [Slingshot](https://slingshot.microcosm.blue) for identity resolution, which provides:

1. **Fast Resolution**: Pre-cached PDS endpoints for quick lookups
2. **resolveMiniDoc**: Returns `{did, handle, pds, pdsKnown}` in one call
3. **Reliability**: Automatically falls back to standard resolution methods
4. **Bi-directional Verification**: Only returns verified handle↔DID mappings

**Example resolveMiniDoc response:**
```json
{
  "did": "did:plc:abcdef123456",
  "handle": "alice.bsky.social",
  "pds": "https://morel.us-east.host.bsky.network",
  "pdsKnown": true
}
```

## Authentication Flow

### 1. Initial Setup (Link)
```
Player runs: /atproto link alice.bsky.social
    ↓
Resolve via Slingshot → Get (DID, handle, PDS)
    ↓
Fetch profile to verify identity exists
    ↓
Store UUID ↔ (DID, handle) mapping
```

### 2. Authentication (Login)
```
Player runs: /atproto login alice.bsky.social abcd-1234-efgh-5678
    ↓
Resolve PDS URL via Slingshot
    ↓
POST /xrpc/com.atproto.server.createSession
    Body: {identifier, password}
    ↓
Response: {did, handle, accessJwt, refreshJwt}
    ↓
Store session with tokens
```

### 3. Making Authenticated Requests
```
Mod needs to sync stats
    ↓
sessionManager.makeAuthenticatedRequest(uuid, "POST", "com.atproto.repo.createRecord", body)
    ↓
Check if access token needs refresh (>1.5 hours old)
    ↓ (if needed)
POST /xrpc/com.atproto.server.refreshSession
    Header: Authorization: Bearer {refreshJwt}
    ↓
Update session with new tokens
    ↓
Make actual request with current accessJwt
```

## Data Storage

### Identity Storage (`player-identities.json`)
```json
{
  "version": 1,
  "identities": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "did": "did:plc:abcdef123456",
      "handle": "alice.bsky.social",
      "linkedAt": 1703001600000,
      "lastVerified": 1703001600000
    }
  ]
}
```

### Session Storage (`player-sessions.json`)
```json
{
  "version": 1,
  "sessions": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "did": "did:plc:abcdef123456",
      "handle": "alice.bsky.social",
      "pdsUrl": "https://morel.us-east.host.bsky.network",
      "accessJwt": "eyJ...",
      "refreshJwt": "eyJ...",
      "createdAt": 1703001600000,
      "lastRefreshed": 1703001600000
    }
  ]
}
```

## Security Considerations

- **App Passwords Only**: Players should NEVER use their main account password
- **Token Storage**: Tokens are stored on disk - server operators should secure the config directory
- **Automatic Refresh**: Access tokens are refreshed automatically before expiration
- **No Credential Storage**: App passwords are never stored, only the resulting JWT tokens
- **Per-PDS**: Each player authenticates with their own PDS, maintaining decentralization

## Error Handling

All AT Protocol operations use Kotlin's `Result` type for error handling:
- Network failures → Friendly error messages to players
- Invalid identifiers → Format validation with helpful feedback
- Authentication errors → Clear guidance about app passwords
- Token expiration → Automatic refresh or re-login prompt

Commands run in coroutine scope (`Dispatchers.IO`) to avoid blocking the server thread.

## Creating App Passwords

Players need to create an app password to authenticate:

1. **Bluesky Users**:
   - Go to Settings → Privacy and Security → App Passwords
   - Click "Add App Password"
   - Name it something descriptive (e.g., "Minecraft Server Name")
   - Copy the password immediately

2. **Other PDS Providers**:
   - Check your PDS provider's documentation for app password creation
   - The process is similar but may be in different locations

3. **Security Tips**:
   - Use a unique app password for each Minecraft server
   - Never share your app password
   - Revoke app passwords you're no longer using
   - If compromised, revoke immediately and create a new one

## Future Enhancements

- OAuth device flow for better security (no passwords in chat)
- Automatic session refresh on server restart
- DPoP (Demonstrating Proof of Possession) support
- Support for custom PDS instances with different auth flows
- Integration with the lexicon records for automatic stat syncing
- Web-based authentication portal
