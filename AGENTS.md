# AGENTS.md

Guidance for agents working on Social Sync, a Kotlin Paper plugin linking Minecraft UUIDs with AT Protocol identities.

## Architecture and boundaries

- `src/main/kotlin/` contains Paper lifecycle/commands, identity resolution, auth/session behavior, persistence, and shaded network clients.
- `src/main/resources/` defines plugin metadata/configuration.
- `docs/` records operator and protocol design; `logs/` is runtime output and should not grow through source changes.
- Plugin data under `plugins/SocialSync/` is a persistent compatibility contract.

## Rules

- Treat Minecraft UUIDs and DIDs as stable identifiers; handles are mutable. Verify ownership/authentication before linking.
- Keep app passwords/tokens encrypted or minimally persisted according to existing design and never log them.
- Run PDS/network work asynchronously and Paper player/world operations on the server thread.
- Preserve stable Paper 26.1/Java 25 compatibility and shaded dependency relocation.
- Configuration and stored-data changes need safe defaults/migrations.

## Validation

Run `./gradlew build` and tests. On a test server exercise link/login/unlink, handle changes, DID resolution, session refresh, restart persistence, permissions, PDS timeout, invalid credentials, dependency shading, and clean disable. Never use production player credentials or commit runtime logs/data.
