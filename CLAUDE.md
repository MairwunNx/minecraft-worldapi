# WorldAPI: notes for AI agents

A Paper 26.3 plugin that serves the server's state over REST and a WebSocket: worlds, players, statistics, events, and admin actions, plus Java scripts for those with the `code` scope. README.md explains it for humans; read it first. Two things depend on it: the MCP server in MairwunNx/minecraft-mcp, and the Moonlands site in MairwunNx/minecraft.

## Talking to the owner

- Reply in Russian, address the owner as «вы». Quotes «ёлочки», nested „лапки“, an em dash with spaces « — ».
- Code, comments, commit messages, and README.md are in English: curly quotes, an em dash without spaces or an en dash with spaces, never a hyphen as a dash.

## How it fits together

| Path | What it does |
|---|---|
| `WorldApiPlugin.kt`, `core/` | Startup and shutdown; `MainThread.kt` runs Bukkit calls on the server thread, with a timeout |
| `config/` | `config.yml` through kaml in strict mode; an unknown key is an error, not a silent default |
| `api/` | Ktor server, the endpoint DSL, auth and scopes, rate limits, trusted proxies, and the OpenAPI generator |
| `routes/` | One file per area; each endpoint declares its scope, so the router and `docs/openapi.json` come from the same list |
| `events/` | `Topic` (every stream and its privacy), listeners, the sampler for state topics, snapshots, and `Visibility`, which strips what anonymous clients mustn't see |
| `ws/` | The WebSocket hub: per-client outboxes, state topics coalesce, event topics drop the oldest |
| `stats/` | Statistics from live players and the stats files, leaderboards, aggregates |
| `store/` | Event history, sessions, audit log, experience: Exposed DSL over HikariCP on SQLite, PostgreSQL, or MySQL/MariaDB. Writes queue on one thread; reads wait for the writes queued before them. Table and column names stay as 1.x made them in SQLite |
| `plan/` | The section on Plan's pages. Plan is optional: its classes load only when it's enabled, and it calls the extension from its own threads, so it reads only thread-safe state |
| `tokens/`, `command/` | Issued tokens (stored as SHA-256), the in-game token menu, `/worldapi` |
| `script/`, `src/main/java/.../script/api` | Java scripts: compiled by the bundled ECJ, loaded by a filtered class loader, checked against blocked calls |

## Rules that keep it working

- Bukkit calls happen on the server thread through `core/MainThread.kt`; IO, the database, and network never do.
- Anonymous clients never get coordinates, inventories, addresses, or chat. A new topic or field declares its `Privacy` in `Topic.kt`, and `Visibility` must strip it for anonymous clients. Moonlands opens topics to anonymous visitors in its own config, so check what a new field would show there.
- `admin` doesn't include `code`. Scripts run arbitrary Java on the server; they stay off by default.
- Never log or print a token. The token menu shows a new one once, to the player who made it.
- Statistics use vanilla ids, as in the stats files. Bukkit names some differently (PLAY_ONE_MINUTE is `minecraft:play_time`); map them in `vanillaKey`, or online players drop out of boards.
- A changed field the Moonlands site reads (`site/data.js` in MairwunNx/minecraft) can break the site. Add fields; don't rename or remove them.

## Changing endpoints

1. Change the route and its data classes.
2. `WORLDAPI_UPDATE_OPENAPI=1 ./gradlew test` rewrites `docs/openapi.json`; a stale document fails the tests.
3. In minecraft-mcp, `npm run generate` and fix what the typecheck flags.

## Building and testing

- `./gradlew build` runs the tests and builds the shaded jar in `build/libs`; Java 25.
- There is no MockBukkit for Paper 26.3; tests use the small fakes in `src/test`. Anything that needs a real server goes to a local Paper in Docker (the compose snippet in README.md).
- Dependencies are shaded and relocated under `com.mairwunnx.worldapi.libs`. The SQLite driver comes from Paper; the PostgreSQL and MariaDB drivers are relocated too, so the store passes Hikari their class names from class references. `WORLDAPI_TEST_DATABASES=postgresql://u:p@host:port/db,mysql://...` runs `StoreTest` against real servers.

## Checking a change

1. `./gradlew build`: the tests and the jar.
2. A real Paper with the jar, started and stopped by the script, which fails on any error about the plugin:
   ```sh
   python3 -I .claude/scripts/paper_smoke.py --game 26.3 --plugin build/libs/worldapi-dev.jar --modrinth plan --expect "Listening on .* endpoints" --expect "Registered extension: WorldAPI" --ignore 'Plan\] Failed to enable geolocation'
   ```
   Pass `--game` the version the project targets, and `--build` to pin a Paper build. The script downloads Paper and Modrinth plugins into `~/.cache/paper-smoke` and keeps the server folder when something fails.
   Plan reports a failing extension method only as a warning and a file in `plugins/Plan/logs/`, which the script doesn't catch: after a change to `plan/`, add `--keep` and check that folder is empty. Player values appear only once a player joins.
3. Endpoints and the WebSocket need a running server to call: the Docker compose snippet in README.md, then `curl` with the admin token from its `config.yml`. After a release reaches Moonlands, `curl -s https://minecraft.mairwunnx.com/worldapi/api/v1/server` shows its `worldApiVersion`, and the site and `scripts/live-check.mjs` in minecraft-mcp read it.

## Versions

Where the versions live, for `/update-deps` and `/update-minecraft` (both in `.claude/skills/`, the same files in every project of the family):

- The game: `io.papermc.paper:paper-api` (twice: `compileOnly` and `testImplementation`), `gameVersions` (Modrinth) and `platformVersions` (Hangar) in `build.gradle.kts`, and `api-version` in `src/main/resources/paper-plugin.yml`.
- Java: `jvmToolchain` in `build.gradle.kts`; CI's `setup-java` in `.github/workflows/`.
- Gradle in `gradle/wrapper/gradle-wrapper.properties`; Kotlin and the Gradle plugins in the `plugins` block; libraries, the JDBC drivers and ECJ among them, in `dependencies`.
- Plan: `compileOnly("com.djrapitops:plan-api:…")` from `repo.playeranalytics.net`, never shaded. Use only what the Plan servers run offers: graphs and statistics (`DATA_EXTENSION_GRAPH_API`, `DATA_EXTENSION_STATISTICS_API`) need a Plan newer than 5.8.

## Docs

- README.md and README.RU.md are for server owners and people who build on WorldAPI: who it's for, install, tokens, config, safety. Change both together, and MODRINTH.md when what the plugin does changes. MODRINTH.md is also the Hangar page: no build, stack, AI, or related projects, and no Moonlands banner.
- `docs/reference.md` holds the API reference: endpoint groups, conventions, leaderboards, the WebSocket and its topics, Java scripts. A new endpoint or topic goes there, not in the README.
- Internals stay out of the READMEs; this file keeps them.

## Releasing and Moonlands

1. Add a “## X.Y.Z” section to CHANGELOG.md, written for server owners. After a change that reaches what the project ships, add it to CHANGELOG.md in the same push: under the topmost “## X.Y.Z” section while that version isn't released (`gh release view vX.Y.Z` fails), or under a new section with the next version: patch for fixes, minor for features, major for breaking changes. A release then needs only the workflow, and the workflow runs only when the owner asks.
2. Run the “Release” workflow: `gh workflow run release.yml -R MairwunNx/minecraft-worldapi -f version=X.Y.Z -f modrinth=true -f hangar=true`. Its `changelog` field overrides the CHANGELOG.md section. It builds with `-PreleaseVersion`, tags `vX.Y.Z`, and attaches `worldapi-X.Y.Z.jar` to a GitHub release. Nothing in the repo holds the version; local and CI builds are `dev`.
3. Modrinth: project `worldapi` (id `Q5IDSOEP`), submitted for review on 2026-10-13; `./gradlew modrinth` uploads the jar and syncs MODRINTH.md. Hangar: project `MairwunNx/WorldAPI`; `./gradlew publishPluginPublicationToHangar` adds a version to the Release channel for Paper 26.3 that links to the GitHub release jar, since the jar is over Hangar's 15 MB limit; the page doesn't sync, so edit it on the site. Both use secrets (`MODRINTH_TOKEN`, `HANGAR_TOKEN`) that also sit in `~/.secrets/cloud.env`; never put them in the repo. The Hangar key (made 2026-10-13 with `create_version`) uploads versions; project settings, the page, and the icon are edited on the site.
4. To put it on Moonlands, follow “Shipping a plugin or backend update” in `~/c/personal/minecraft/CLAUDE.md` (MairwunNx/minecraft). That file also says when a restart may happen without asking.

Why the build looks the way it does: `paper-plugin.yml` because WorldAPI is Paper-only and gets an isolated class loader and Brigadier commands; dependencies shaded so the jar works offline and can't clash with FAWE, BlueMap, Plan, or TAB; ECJ unrelocated because it finds its message bundles by package name; the SQLite driver left to Paper; JDBC drivers named through class references so the relocation holds.

## Commits

Short one-line imperative English messages, one logical change per commit: “Send active effects and freezing with player.health”.
