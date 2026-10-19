# WorldAPI: notes for AI agents

A Paper 26.3 plugin that serves the server's state over REST and a WebSocket: worlds, players, statistics, events, and admin actions, plus Java scripts for those with the `code` permission, configs and logs as files, and an admin panel at `/admin`. README.md explains it for humans; read it first. Two things depend on it: the MCP server in MairwunNx/minecraft-mcp, and the Moonlands site in MairwunNx/minecraft.

## Talking to the owner

- Reply in Russian, address the owner as «вы». Quotes «ёлочки», nested „лапки“, an em dash with spaces « — ».
- Code, comments, commit messages, and README.md are in English: curly quotes, an em dash without spaces or an en dash with spaces, never a hyphen as a dash.

## How it fits together

| Path | What it does |
|---|---|
| `WorldApiPlugin.kt`, `core/` | Startup and shutdown; `MainThread.kt` runs Bukkit calls on the server thread, with a timeout |
| `config/` | `config.yml` through kaml in strict mode; an unknown key is an error, not a silent default |
| `api/` | Ktor server, the endpoint DSL, auth, permissions and groups (`Scope.kt`), rate limits, trusted proxies, the HTTPS warning (`Exposure.kt`), and the OpenAPI generator |
| `files/` | `/files`: which paths may be read or written (`ServerFiles`), paged and grep reads, writes with backups, and key edits in YAML, .properties, and JSON (`ConfigValues`) |
| `routes/` | One file per area; each endpoint declares its permission (and `orScopes`, `also` where they apply), so the router and `docs/openapi.json` come from the same list |
| `events/` | `Topic` (every stream and its privacy), listeners, the sampler for state topics, snapshots, and `Visibility`, which strips what anonymous clients mustn't see |
| `ws/` | The WebSocket hub: per-client outboxes, state topics coalesce, event topics drop the oldest |
| `stats/` | Statistics from live players and the stats files, leaderboards, aggregates |
| `store/` | Event history, sessions, audit log, experience: Exposed DSL over HikariCP on SQLite, PostgreSQL, or MySQL/MariaDB. Writes queue on one thread; reads wait for the writes queued before them. Table and column names stay as 1.x made them in SQLite |
| `plan/` | The section on Plan's pages. Plan is optional: its classes load only when it's enabled, and it calls the extension from its own threads, so it reads only thread-safe state |
| `bukkit/Vanish.kt` | `Player.isVanished` (the `vanished` metadata vanish plugins set, server thread only) and `Vanished`, the thread-safe set the server thread refreshes every 5 ticks for code off it |
| `extensions/` | Other plugins' data: `Bridge` (the `Function` in Bukkit's ServicesManager that plugins call with maps), `Extensions` (their namespaces and resources), `JsonValues` (their plain values as JSON); `routes/ExtensionRoutes.kt` serves them, and their topics are `Topic.extension` |
| `tokens/`, `command/` | Issued tokens (stored as SHA-256), the in-game token menu, `/worldapi` |
| `script/`, `src/main/java/.../script/api` | Java scripts: compiled by the bundled ECJ, loaded by a filtered class loader, checked against blocked calls |
| `admin/` (Kotlin side in `admin/*.kt`) | The admin panel: React, Vite, shadcn/ui, TanStack Router and Query, built with Bun into the jar under `admin/`; `Panel.kt` serves it at `/admin` and holds the sign-in endpoints, `Accounts.kt` the accounts and sessions |

## Rules that keep it working

- Bukkit calls happen on the server thread through `core/MainThread.kt`; IO, the database, and network never do.
- Anonymous clients never get coordinates, inventories, addresses, or chat. A new topic or field declares its `Privacy` in `Topic.kt`, and `Visibility` must strip it for anonymous clients. Moonlands opens topics to anonymous visitors in its own config, so check what a new field would show there.
- Permissions live in `Scope.kt`; groups in config.yml `auth.groups`, with defaults in `Scope.defaultGroups` that the shipped config.yml must match (a test checks). Only `owner` has `files:write` and `code` by default; keep it so. Anyone grants only what they have (`canGrant`).
- `/files` never reaches WorldAPI's own folder, hidden files, keys, databases, jars, or a link out of the server folder; logs and files the server keeps in memory stay read-only. A new rule goes into `ServerFiles` with a test in `FilesTest`.
- Never log or print a token. The token menu shows a new one once, to the player who made it.
- Vanished players look offline to every client without `players:moderate`. A new endpoint or field that names or counts online players checks `ApiCall.hides` (or `seesVanished`) on the server thread; an event about a player goes through `EventBus`, which marks it `hidden` from `Vanished`, and the hub and history serve hidden events to `players:moderate` clients only. Counts inside events leave vanished players out for everyone.
- The extension bridge is a contract other plugins compile against without our classes: plain Java types in, maps out, and `v` on every request. Add ops and fields; never change what an existing one means, or bump `Bridge.CONTRACT` and keep the old behavior for the old version. WorldAPI stores none of a plugin's data, only its `recorded` events.
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

## Admin panel

- `admin/` is a React app on the same stack as Allay's panel (`~/c/personal/allay/server/admin`): Vite, TypeScript, shadcn/ui and Tailwind, TanStack Router and Query, react-hook-form and zod, Recharts, react-i18next, CodeMirror for configs. Every visible string sits in `src/locales/en.json`.
- It calls the public API with the session cookie, never a token. Types come from `docs/openapi.json`: `bun run generate` writes `src/api/generated/` (git-ignored), and every build runs it first, so a changed endpoint fails the panel's typecheck. openapi-typescript needs TypeScript 5's API, so it lives in `admin/scripts/openapi/` with its own lockfile.
- Writes send `X-WorldAPI-Panel: 1`, or WorldAPI answers 403 `csrf`. The page takes its API path from the `worldapi-api` meta tag WorldAPI injects and its assets from the `<base>`, so it works under any prefix (`/worldapi/admin/` on Moonlands). The CSP allows nothing from elsewhere: no CDNs, no inline scripts, fonts bundled, and `z.config({ jitless: true })` keeps zod from probing `eval`.
- Pages hide what `/auth/me`'s permissions don't allow; WorldAPI checks again on every call.
- Gradle's `buildPanel` runs `bun install --frozen-lockfile` and `bun run build` into `build/admin-dist`, and `processResources` puts it in the jar. Bun must be on the PATH; `./gradlew build -PskipPanel` builds without the panel, and `/admin` then says so. CI installs Bun with `oven-sh/setup-bun`.
- Item icons come from `GET /icons/{key}` (`admin/Icons.kt`): WorldAPI downloads the game client for the server version from Mojang into `plugins/WorldAPI/cache` on first use. Never put Mojang textures in the repo or the jar.
- `cd admin && bun run dev` serves it with hot reload and passes `/api` and the WebSocket through to a local server on 8110.
- Checking a change: `cd admin && bun run typecheck`, then `./gradlew build`, a local Paper with the jar, and the panel in a headless browser at 1366 and 360 px wide: no console errors, no CSP reports, nothing scrolls sideways. A test account can go straight into `plugins/WorldAPI/worldapi.db` (`accounts`, the password as `pbkdf2$600000$<salt hex>$<hash hex>`) while the server is stopped; `/worldapi admin` refuses the console on purpose.

## Autopilot

`.github/workflows/autopilot.yml` runs Claude every Monday at 03:00 UTC: `/update-deps`, then `/update-minecraft`, a fix for whatever they break, a push to `master`, and the “Release” workflow with every store when what the project ships changed. The release's `deploy` job then starts Moonlands' `ship-plugin.yml` with the `FAMILY_ACTIONS_TOKEN` secret, and Claude puts the jar on the server there. The run itself needs the `CLAUDE_CODE_OAUTH_TOKEN` secret. “Autopilot” in `~/c/personal/minecraft/CLAUDE.md` explains the family's runs. A session with the owner still releases only when the owner asks.

## Versions

Where the versions live, for `/update-deps` and `/update-minecraft` (both in `.claude/skills/`, the same files in every project of the family):

- The game: `io.papermc.paper:paper-api` (twice: `compileOnly` and `testImplementation`), `gameVersions` (Modrinth) and `platformVersions` (Hangar) in `build.gradle.kts`, and `api-version` in `src/main/resources/paper-plugin.yml`.
- Java: `jvmToolchain` in `build.gradle.kts`; CI's `setup-java` in `.github/workflows/`.
- Gradle in `gradle/wrapper/gradle-wrapper.properties`; Kotlin and the Gradle plugins in the `plugins` block; libraries, the JDBC drivers and ECJ among them, in `dependencies`.
- The admin panel: `admin/package.json` and `admin/bun.lock` (`bun update` there); openapi-typescript and its TypeScript 5 in `admin/scripts/openapi/package.json`; Bun itself in `oven-sh/setup-bun` in `.github/workflows/`.
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
