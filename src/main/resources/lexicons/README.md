# atproto-connect Lexicons

This directory contains Lexicon schema definitions for the `com.jollywhoppers.minecraft.*` namespace, enabling Minecraft data to be stored and shared on the AT Protocol network.

## Schema Overview

### Core Definitions

**`com.jollywhoppers.minecraft.defs`**
- Common type definitions used across all Minecraft lexicons
- Includes `playerReference`, `serverReference`, and `statistic` types

### Player Data

**`com.jollywhoppers.minecraft.player.profile`** (key: `literal:self`)
- Links a Minecraft player UUID to their AT Protocol identity
- Single record per account serving as the primary identity record
- Includes privacy controls for stats and session visibility

**`com.jollywhoppers.minecraft.player.stats`** (key: `tid`)
- Snapshots of player statistics (blocks mined, mobs killed, etc.)
- Suitable for cross-server leaderboards
- Can be synced periodically or on significant milestones

**`com.jollywhoppers.minecraft.player.session`** (key: `tid`)
- Individual play session records (join/leave times)
- Tracks playtime and connection history
- Useful for activity tracking and analytics

### Achievements

**`com.jollywhoppers.minecraft.achievement`** (key: `tid`)
- Records when players earn achievements/advancements
- Supports both vanilla and custom achievements
- Can be scoped to specific servers or global

### Leaderboards

**`com.jollywhoppers.minecraft.leaderboard`** (key: `tid`)
- Pre-computed leaderboard entries for specific statistics
- Supports server-specific and global leaderboards
- Can track different time periods (all-time, monthly, weekly, daily)

### Server Status

**`com.jollywhoppers.minecraft.server.status`** (key: `literal:self`)
- Server information and current status
- Player counts, version info, server settings
- Useful for server discovery and monitoring

## Record Keys

- **`tid`**: Time-ordered identifier - used for records that occur multiple times
- **`literal:self`**: Single instance record - only one per account

## Usage Example

When a player completes a milestone, the mod would:

1. Read current stats from the Minecraft player data
2. Create a `com.jollywhoppers.minecraft.player.stats` record
3. Optionally update the `com.jollywhoppers.minecraft.leaderboard` if they rank
4. Publish to the player's AT Protocol repository

## Data Flow

```
Minecraft Server (Fabric)
    ↓
Player Events (blocks mined, mobs killed, etc.)
    ↓
atproto-connect Mod
    ↓
Create/Update Lexicon Records
    ↓
AT Protocol PDS
    ↓
Federated Network (visible to all indexers)
    ↓
Custom AppViews (leaderboards, stats displays)
```

## Privacy Considerations

- Players control what data is synced via `publicStats` and `publicSessions` flags
- Server operators can configure which statistics are tracked
- All data is published to the player's own AT Protocol repository
- Players can delete their data at any time

## Future Enhancements

- Event records (player kills, deaths, notable achievements)
- Trading/economy tracking
- Guild/team statistics
- Custom server-specific lexicons
- Rich media attachments (screenshots of achievements)

## References

- [AT Protocol Lexicon Specification](https://atproto.com/specs/lexicon)
- [AT Protocol Data Model](https://atproto.com/specs/data-model)
- [Lexicon Style Guide](https://atproto.com/guides/lexicon-style-guide)
