# Social Sync

Right, so this is a Fabric mod that connects Minecraft to the AT Protocol — the protocol Bluesky runs on, but it lives on its own PDS and you don't need Bluesky to use it. The idea is roughly: your gameplay stats, achievements, play sessions, all of that, end up on your own AT Protocol identity rather than sitting in a server database nobody gets to see. They're yours, you own them, and if you want to share them, that's your call.

**This is very much in active development. It's not production ready. Don't put it on a server with real players and expect it to hold together without you keeping an eye on it.**

## What It Actually Does

At the moment it does a handful of things, and they mostly work:

- **Links your Minecraft UUID to an AT Protocol identity.** This is read-only — no password needed, no session created. It's just a mapping saying "this Minecraft player is that AT Proto handle."

- **Authenticates you, either via OAuth or app password.** OAuth opens your browser and handles the whole flow. App passwords work the same way they do in Bluesky itself — you generate one from your PDS settings and paste it in. Your password never touches the server. It stays on your machine, does its one job, and isn't logged or stored anywhere.

- **Syncs stats to your PDS.** The mod periodically takes a snapshot of your non-sensitive Minecraft stats — blocks mined, items crafted, distance walked, stuff like that — and writes them as a record on your AT Protocol identity. The filtering is per-server configurable: server operators decide which stat prefixes and categories are allowed. `player_kills` and `leave_game` are blocked by default, everything else in the vanilla set goes through. Playtime is included, deliberately — it felt like a useful datapoint for leaderboards rather than something worth hiding.

- **Syncs achievements.** When you earn an advancement, it gets written to your PDS. Same consent model — you decide whether it's enabled.

- **Tracks play sessions.** Join and leave times, per session, written as records.

- **Snapshots server status.** Periodic info about the server itself — online players, MOTD, that sort of thing.

Every sync respects per-player consent. You opt in per category (stats, sessions, achievements, server status), and you can turn any of them off whenever you like. The data is public by design — that's how AT Protocol works — so the consent control is about whether it gets written at all, not who can read it.

## Why Bother?

I've been building on AT Protocol for a while because I genuinely think the model is better than the alternative. Your data lives on your PDS, under your control, not in someone else's database behind an API you have to ask permission to use. A mod syncing Minecraft stats to it is a small example of the pattern, but it's the same pattern — you played the game, the data belongs to you, and here's a place for it that isn't locked inside a server you don't own.

There's also a pragmatic angle. Mojang's own statistics screen is fine for glancing at, but it doesn't export anywhere useful. If you want to track your playtime across sessions or compare stats with other players, you're either modding it in or you're not doing it. This mod gives you a decent answer to "where does that data go?" that isn't nowhere.

## Project Status

Core features are shipped. What's still missing:

- Publishing to Modrinth or CurseForge (I keep meaning to get round to it)
- A proper AppView web dashboard for browsing synced data (there's a basic one, it works, but it's not where I want it yet)
- More polish on the config screen in ModMenu (it's functional, I'm not embarrassed by it, there's room)

## Commands

| Command | What it does |
|---|---|
| `/atproto link <handle or DID>` | Link your Minecraft UUID to your AT Protocol identity |
| `/atproto unlink` | Remove the link |
| `/atproto logout` | Log out without unlinking |
| `/atproto whoami` | See your linked identity and auth status |
| `/atproto status` | Quick status check |
| `/atproto whois <player or handle>` | Look up another player's identity |
| `/atproto sync` | View and toggle sync consent |
| `/atproto sync stats <on|off>` | Stats sync toggle |
| `/atproto sync sessions <on|off>` | Sessions sync toggle |
| `/atproto sync achievements <on|off>` | Achievements sync toggle |
| `/atproto sync server-status <on|off>` | Server status sync toggle |
| `/atproto admin ...` | Admin commands (op level 4) — includes force-sync diagnostics and reloading the stats filter config |

The simpler workflow is the ModMenu config screen, which covers authentication and all the sync toggles without typing anything.

## Getting an App Password

If you're not using OAuth:

1. Go to your PDS settings (if you're on Bluesky, it's Settings → Privacy and Security → App Passwords)
2. Create one with a sensible name, like "Minecraft Server"
3. Copy it immediately — you won't see it again
4. Paste it in the config screen or use `/atproto login`

Never use your main account password. That's what app passwords exist to avoid.

## Installation

### For players

1. Install Fabric Loader for Minecraft 1.21.10
2. Install Fabric API and Fabric Language Kotlin
3. Drop the JAR in your `mods` folder
4. Launch the game, open the ModMenu config screen or use `/atproto help`

### For developers

```bash
git clone git@tangled.sh:jollywhoppers.com/socialsync
cd socialsync

# If you use Nix:
nix develop

# Build it:
./gradlew build

# Or run it directly:
./gradlew runClient
```

The built JAR ends up in `build/libs/`.

## Security

The mod takes this reasonably seriously:

- Session data is encrypted at rest with AES-256-GCM using a server-specific key
- Failed login attempts are rate-limited (3 per 15 minutes, 30-minute lockout)
- Security events are logged to a dedicated audit log
- Error messages don't leak sensitive data
- File writes are atomic to avoid corruption
- All shared data structures are thread-safe
- Paths are validated against traversal attacks
- Access tokens are automatically refreshed before they expire

## Configuration

Everything lives in `config/atproto-connect/`:

| File | What it is |
|---|---|
| `player-identities.json` | UUID to DID/handle mappings (plaintext — both are public) |
| `sync-preferences/` | Per-player sync consent settings |
| `player-sessions.json` | Encrypted authentication sessions |
| `stats-filter-config.json` | Per-server stat filter rules |
| `.encryption.key` | AES-256 key, auto-generated |
| `security-audit.log` | Security event log |

Never commit `.encryption.key` or `player-sessions.json` to version control.

## Technical Bits

- **Minecraft**: 1.21.10
- **Loader**: Fabric
- **Protocol**: AT Protocol (the real one, not a custom thing that looks like it)
- **Language**: Kotlin
- **Build**: Gradle 8.x
- **Identity resolution**: [Slingshot](https://slingshot.microcosm.blue) by Microcosm — this is what makes PDS discovery fast and cached rather than hitting the directory every time
- **Auth**: OAuth with DPoP and PKCE as the primary path, app passwords as the fallback

## Dependencies

- Fabric Loader >=0.18.3
- Fabric API for 1.21.10
- Fabric Language Kotlin 1.13.8+kotlin.2.3.0
- kotlinx-serialization and kotlinx-coroutines (bundled)

## Affiliation

I started this as my own project while messing around with Minecraft and AT Protocol. It's now maintained by the **Jollywhoppers** coding group as a whole. The code lives at **[tangled.org/jollywhoppers.com/socialsync](https://tangled.org/jollywhoppers.com/socialsync)** and is hosted on **[Tangled](https://tangled.org/)**.

## Contributing

It's early days. If you want to help:

1. Check the issue tracker for open tasks
2. Fork the repo
3. Create a feature branch
4. Send a pull request with a clear description

Use Kotlin conventions, include tests for new stuff, and don't be precious about feedback.

## Documentation

There's more detailed docs in the repository:

- [API Reference](docs/API_REFERENCE.md)
- [Architecture Guide](docs/ARCHITECTURE.md)
- [AppView Guide](docs/APPVIEW.md)
- [AppView Quick Start](docs/APPVIEW_QUICKSTART.md)
- [Testing Guide](docs/TEST_GUIDE.md)
- [Lexicon Schemas](src/main/resources/lexicons/README.md)
- [Examples](docs/examples/)

## License

[AGPL-3.0](LICENSE). If you run this as a network service, you're required to provide the complete corresponding source code including any modifications. That's deliberate — improvements to the project should stay open.

## Disclaimer

This is an experimental project exploring the intersection of decentralised protocols and games. Not affiliated with Mojang, Microsoft, or the AT Protocol team.

## Acknowledgments

- [Microcosm](https://microcosm.blue) for Slingshot and for generally building good things
- The AT Protocol team for building a genuinely open protocol instead of yet another walled garden
- The Fabric community for solid modding tools
- The Kotlin community for a language that makes all of this considerably less painful than it would be in Java

---

**Version**: 0.5.1
**Repository**: `git@tangled.sh:jollywhoppers.com/socialsync`
**Status**: Alpha — don't rely on it yet
