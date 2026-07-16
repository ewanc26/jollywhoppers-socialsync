# AGENTS.md

Guidance for Social Sync, a Kotlin Paper 26.1 plugin that links Minecraft UUIDs to AT Protocol identities, stores player/server sessions, publishes Minecraft lexicon and Bluesky records, consumes Jetstream, and exposes an embedded read-only AppView/dashboard.

## Runtime map

- `SocialSyncPaperPlugin` owns lifecycle, `/atproto` and `/socialsync` commands, schedulers, listeners, update checking, and component startup/shutdown. Paper API calls and Adventure messages belong on the region/global scheduler; PDS and disk work belongs in the supervised IO scope.
- `AtProtoClient`, `AtProtoSessionManager`, `RecordManager`, and `ServerAccount` resolve identities, authenticate, encrypt JWT sessions, and read/write records. `PlayerIdentityStore`, achievement/stat sync stores, and tracker classes persist deduplication and publication state under Paper's `plugins/SocialSync/` data folder.
- `PaperAchievementTracker` publishes each advancement once. `PaperServerDataTracker` emits disconnect sessions, changed server-status posts, and periodic player summaries. Keep remote publication and local “already synced” state ordered so failures remain retryable.
- `AppViewService` indexes Social Sync records; `FirehoseSubscriber` currently connects to the hard-coded Bluesky Jetstream endpoint and only applies create/update events for selected collections. `AppViewHttpServer` starts unconditionally on port 8080 and exposes unauthenticated profile/stat/search/dashboard routes.
- `src/main/resources/lexicons/` is the wire contract for `com.jollywhoppers.minecraft.*`; `plugin.yml` defines Paper compatibility, commands, and permissions. The Shadow JAR bundles Kotlin, Ktor, serialization, and AT Protocol runtime dependencies.

## Identity, credentials, and network safety

- Minecraft UUIDs and DIDs are stable identifiers; handles are mutable. `/atproto link` deliberately creates an unauthenticated public association, while `/atproto login` proves account control and stores a write-capable session. Do not describe a plain link as ownership verification or allow it to authorize writes.
- The login command accepts an app password as a command argument. Never echo it, include it in audit/error logs, tab completion, command history, console forwarding, or tests. Main account passwords should not be encouraged; OAuth/device flow remains future work.
- Session JSON is AES-GCM encrypted with `.encryption.key` in the same plugin data directory and migrated from legacy plaintext. Preserve atomic writes, restrictive permissions, key durability/backups, and failure visibility; losing or silently replacing the key makes stored sessions unusable.
- PDS/DID/Slingshot/Jetstream endpoints and remote records are untrusted. Retain HTTPS, DNS/private-address and redirect/SSRF protections, timeouts, response bounds, identifier/NSID/rkey validation, and per-PDS routing. The `bsky.social` fallback must not be used as the repository endpoint for an already resolved non-Bluesky DID.
- The embedded AppView has no authentication and currently accepts unbounded/negative-looking pagination strings before service handling. Any exposure/config change must add explicit bind address, port, access policy, input caps, CORS, and privacy review; do not assume port 8080 is loopback-only.
- Preserve Bluesky's grapheme/UTF-8 facet limits, AT record validation, TID monotonicity, strong refs, commit swaps where required, crawler/feed semantics, and the custom lexicons. Treat remote firehose deletes and reconnect cursors explicitly rather than claiming a complete durable index.

## Documentation and compatibility

- `gradle.properties` is authoritative at version `0.8.0-Paper`; the README installation filename still says `0.7.0-Paper`. Update release strings, `plugin.yml` expansion, docs, and examples together.
- The nested AT Protocol README's old `config/atproto-connect/` paths and several “future” claims no longer match the Paper data folder/current implementation. Verify documentation against source before repeating it.
- Target Java bytecode 25 and Paper `26.1.2.build.72-stable`; Gradle wrapper 9.6.1 supports the Java 26 development runtime. Preserve compile-only Paper APIs, Folia/Paper scheduler safety, service-file merging, and an actually self-contained Shadow JAR.
- Stored JSON versions and collection schemas are compatibility boundaries. Add migrations and failure tests before renaming fields or files; do not discard corrupt state or mark remote writes complete when persistence fails.

## Validation

- Run `./gradlew clean test build`; inspect `build/libs/socialsync-0.8.0-Paper.jar`, its expanded `plugin.yml`, shaded classes/service files, and absence of duplicate/unshaded dependency failures. `flake.nix` is an additional reproducible environment boundary when changed.
- Use a disposable Paper 26.1 server and disposable AT Protocol accounts to test link versus authenticated login, logout/unlink, admin server login, permissions/console use, handle/PDS changes, refresh rotation, encrypted restart/migration, corrupt state, record retries, achievement/stat deduplication, and clean disable.
- Exercise AppView bind conflicts, pagination bounds, unauthenticated access, Jetstream reconnect/create/update/delete behavior, offline Slingshot/PDS/Jetstream, and scheduler/thread assertions. Never point tests at real player repositories or a public AppView.
- Preserve the unrelated deletion of `docs/COMPLETION_SUMMARY.md`. Do not commit plugin data, `.encryption.key`, sessions/JWTs, app passwords, audit/runtime logs, test-server worlds, `.gradle/`, or `build/`.
