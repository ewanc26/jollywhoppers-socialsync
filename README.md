# Social Sync for Paper

Social Sync is a Paper plugin that links Minecraft player UUIDs to AT Protocol identities. This branch targets the stable Paper `26.1.2.build.72-stable` API.

## Requirements

- Paper 26.1.2 or newer within the 26.1 line
- Java 25 or newer
- Network access to the player's AT Protocol PDS

## Installation

1. Build with `./gradlew build`.
2. Copy `build/libs/socialsync-0.7.0-Paper.jar` into the server's `plugins/` directory.
3. Restart the server.

Plugin data is stored under `plugins/SocialSync/`. The plugin shades its Kotlin, Ktor, serialization, and AT Protocol runtime dependencies, so no companion plugin is required.

## Commands

| Command | Description |
|---|---|
| `/atproto link <handle or DID>` | Resolve and link an AT Protocol identity |
| `/atproto unlink` | Remove the player's identity link |
| `/atproto whoami` | Display the linked identity |
| `/atproto status` | Display plugin and link-store status |
| `/atproto help` | Display command help |

`/socialsync` is an alias for `/atproto`. Player commands require `socialsync.use`, which defaults to true.

## Platform migration

The Paper build is server-side only. Fabric client features—ModMenu, client OAuth, mixins, custom payloads, and client-side preferences—are not available on Paper and have been removed from this branch. Identity resolution, secure persistence, AT Protocol records, AppView components, security auditing, and the HTTP client remain platform-neutral.

## Development

```bash
./gradlew clean test build
```

The produced JAR contains `plugin.yml` with `api-version: '26.1.2'`. Paper releases use the `-Paper` version suffix. The build uses Gradle 9.6.1 because it supports the current Java 26 development runtime while emitting Java 25-compatible bytecode.

## License

AGPL-3.0-only. See [LICENSE](LICENSE).
