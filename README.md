# atproto-connect

A Fabric mod for Minecraft 1.21.10 that bridges the game with the AT Protocol, enabling decentralized data synchronization and social features.

## ⚠️ Project Status

**This project is in early planning stages and is NOT ready for production use.** The current repository contains a Fabric mod template to establish the initial project structure. Active development and implementation are ongoing.

## Overview

atproto-connect aims to integrate Minecraft gameplay with the AT Protocol (the protocol powering Bluesky), allowing game data to be synced to AT Protocol lexicons. This enables decentralized storage and sharing of Minecraft data across the federated network.

## Goals

- **Decentralized Data Sync**: Publish Minecraft gameplay data to AT Protocol repositories
- **Cross-Server Statistics**: Enable player statistics to persist across different servers via AT Protocol
- **Social Integration**: Connect Minecraft achievements and activities with the broader AT Protocol ecosystem
- **Lexicon-Based Storage**: Utilize AT Protocol's schema system for structured game data

## Use Cases

### Player Statistics & Leaderboards

Sync player statistics (blocks mined, mobs killed, distance traveled, etc.) to AT Protocol lexicons, enabling:

- Global leaderboards that work across multiple servers
- Historical stat tracking independent of individual server databases
- Player achievement portfolios visible on AT Protocol clients

### Future Possibilities

- Server announcements via AT Protocol feeds
- Cross-server player reputation systems
- Decentralized mod/plugin distribution
- In-game social features tied to AT Protocol identities

## Technical Stack

- **Minecraft Version**: 1.21.10
- **Mod Loader**: Fabric API
- **Protocol**: AT Protocol (atproto)
- **Language**: Kotlin (with Java interop)

## Installation

### For Users

*Installation instructions will be added once the mod reaches a usable state.*

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

## Development Roadmap

- [ ] Design lexicon schemas for Minecraft data types
- [ ] Implement AT Protocol client integration
- [ ] Create configuration system for AT Protocol credentials
- [ ] Build data collection hooks for player statistics
- [ ] Develop sync engine for pushing data to AT Protocol
- [ ] Add privacy controls and data filtering options
- [ ] Create example lexicons and test environments
- [ ] Write comprehensive documentation

## Architecture (Planned)

```plaintext
Minecraft Server (Fabric)
    ↓
atproto-connect Mod
    ↓
AT Protocol Client Library
    ↓
AT Protocol PDS (Personal Data Server)
    ↓
Federated AT Protocol Network
```

## Contributing

As this project is in early development, contribution guidelines will be established once the core architecture is defined. If you're interested in contributing, please open an issue to discuss your ideas.

## AT Protocol Resources

- [AT Protocol Documentation](https://atproto.com/)
- [Lexicon Specifications](https://atproto.com/specs/lexicon)
- [AT Protocol SDKs](https://atproto.com/sdks)

## License

License information to be added.

## Disclaimer

This is an experimental project exploring the intersection of decentralized protocols and gaming. It is not affiliated with or endorsed by Mojang, Microsoft, or the official AT Protocol team.

---

**Repository**: `git@tangled.sh:ewancroft.uk/atproto-connect`
