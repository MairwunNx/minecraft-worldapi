# WorldAPI: notes for AI agents

A Paper 26.3 plugin that serves the server's state over REST and a WebSocket: worlds, players, statistics, events, and admin actions, plus Java scripts for those with the `code` permission, configs and logs as files, and an admin panel at `/admin`. README.md shows it off; the GitHub wiki explains it for humans, so read it first. Two things depend on it: the MCP server in MairwunNx/minecraft-mcp, and the Moonlands site in MairwunNx/minecraft. Other plugins share their data through it with the expansion API, `com.mairwunnx:worldapi-expansion` on Maven Central.

## Talking to the owner

- Reply in Russian, address the owner as «вы». Quotes «ёлочки», nested „лапки“, an em dash with spaces « — ».
- Code, comments, commit messages, and README.md are in English: curly quotes, an em dash without spaces or an en dash with spaces, never a hyphen as a dash.

## How it fits together

| Module | What it holds |
|---|---|
| `expansion/` | The expansion API, plain Java without dependencies (see “The expansion API”) |
| `core/` | What doesn't touch Bukkit: the config, the store, the models, permissions, `/files`' rules, the WebSocket protocol, the panel's accounts and icons. No game imports; it builds on `expansion` and carries the libraries |
| `paper/` | The plugin: everything that calls Bukkit or Paper, the routes, the listeners, the HTTP server and the hub (they still reach `WorldApi`, which holds the Bukkit server), and the tests that need Bukkit's types. Its shaded jar is the only one released |
| `modcore/`, `fabric/`, `neoforge/` | Frames for the mods, built with every build and never released: modcore against NeoForm, as in Allay and Red Bull, with `Loader` and the expansions' discovery; each mod's entry does nothing yet |

The packages stay the same in every module, so a class moves between `core` and `paper` without a change to its imports. A class goes to `core` once nothing in it, and nothing it uses, needs Bukkit; the compiler says when it does. The paths below are packages under `com.mairwunnx.worldapi`, in `core` or `paper`.

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
| `extensions/` | Other plugins' data: `Expansions` (the expansion API's host) and `ExpansionPlugins` (finds `META-INF/services` in every plugin and drops a stopped plugin's expansions), `Extensions` (their namespaces and resources), `JsonValues` (their plain values as JSON); `routes/ExtensionRoutes.kt` serves them, and their topics are `Topic.extension` |
| `tokens/`, `command/` | Issued tokens (stored as SHA-256), the in-game token menu, `/worldapi` |
| `script/`, `paper/src/main/java/.../script/api` | Java scripts: compiled by the bundled ECJ, loaded by a filtered class loader, checked against blocked calls |
| `admin/` (Kotlin side in `admin/*.kt`) | The admin panel: React, Vite, shadcn/ui, TanStack Router and Query, built with Bun into the jar under `admin/`; `Panel.kt` serves it at `/admin` and holds the sign-in endpoints, `Accounts.kt` the accounts and sessions |

## Rules that keep it working

- Bukkit calls happen on the server thread through `core/MainThread.kt`; IO, the database, and network never do.
- Anonymous clients never get coordinates, inventories, addresses, or chat. A new topic or field declares its `Privacy` in `Topic.kt`, and `Visibility` must strip it for anonymous clients. Moonlands opens topics to anonymous visitors in its own config, so check what a new field would show there.
- A token or account whose permissions resolve to none gets nothing; only the WebSocket waiting for its first message passes `guard` without permissions. Anonymous clients are told by their `ip:` key, never by name.
- Permissions live in `Scope.kt`; groups in config.yml `auth.groups`, with defaults in `Scope.defaultGroups` that the shipped config.yml must match (a test checks). Only `owner` has `files:write` and `code` by default; keep it so. Anyone grants only what they have (`canGrant`).
- `/files` never reaches WorldAPI's own folder, hidden files, keys, databases, jars, or a link out of the server folder; logs and files the server keeps in memory stay read-only. A new rule goes into `ServerFiles` with a test in `FilesTest`.
- Never log or print a token. The token menu shows a new one once, to the player who made it.
- Vanished players look offline to every client without `players:moderate`. A new endpoint or field that names or counts online players checks `ApiCall.hides` (or `seesVanished`) on the server thread; an event about a player goes through `EventBus`, which marks it `hidden` from `Vanished`, and the hub and history serve hidden events to `players:moderate` clients only. Counts inside events leave vanished players out for everyone.
- Other plugins share their data only through the expansion API (below). WorldAPI stores none of a plugin's data, only its `recorded` events. The older bridge, a `Function` in Bukkit's ServicesManager that took maps, was removed in 4.0.0 after Mo’Bosses 2.9.0 moved to the API; don't bring it back. Mo’Bosses' `/extensions/mobosses/leaderboards/kills` and `level` feed the Moonlands site, so keep `/extensions` serving what an expansion answers as it is.
- Statistics use vanilla ids, as in the stats files. Bukkit names some differently (PLAY_ONE_MINUTE is `minecraft:play_time`); map them in `vanillaKey`, or online players drop out of boards.
- A changed field the Moonlands site reads (`site/data.js` in MairwunNx/minecraft) can break the site. Add fields; don't rename or remove them.

## The expansion API

`expansion/` is `com.mairwunnx:worldapi-expansion`, the library other plugins and mods build against, on Maven Central since 1.0.0 (WorldAPI 3.4.0), and since 4.0.0 the only way in: resources and topics with types; the wiki's “Expansions” is its manual for authors, with an example for Paper.

- **Plain Java, no dependencies, Java 21 bytecode**, so any plugin or mod can use it and WorldAPI's Kotlin stays private. Every WorldAPI jar carries its classes unrelocated through `core`'s `api(project(":expansion"))`; never relocate `com.mairwunnx.worldapi.expansion`, and authors use `compileOnly`. On Paper, a plugin sees them with `depend` or `softdepend` on WorldAPI, or as a Paper plugin with `join-classpath: true`; the smoke plugin checks the first, and both work.
- **What it holds.** `WorldApiExpansion` (an id, which is the namespace, a description, `resources()`, `events()`), `Resource` (a path with `{parameters}`, `publicly()`, `onServerThread()`, a handler that answers with plain values or a `CompletionStage`), `Request` (path and query parameters, `anonymous()`, `can(permission)`, `seesCoordinates()`), `EventType` and `Event`, and `WorldApiExpansions` (`register`, `unregister`, `publish`, and `bind` for the host). A handler's `IllegalArgumentException` answers 400, `NoSuchElementException` 404, anything else 502.
- **Unlike Allay's.** Allay's expansions add tools the model calls; WorldAPI's share data clients read, so an expansion is a namespace of resources and topics, and it publishes events itself through `WorldApiExpansions.publish`. The MCP server reaches every expansion through its `list_extensions` and `read_extension` tools without a change.
- **Its own version.** `ExpansionApi.VERSION` is the library's version and what `requiresApi()` returns; `expansion/CHANGELOG.md` its notes. Within a major the API only grows: a new interface method comes with a default, and nothing is removed or changes meaning. WorldAPI refuses an expansion that needs a newer minor or another major, with a line in the log.
- **Releasing** is its own workflow, `gh workflow run expansion.yml -R MairwunNx/minecraft-worldapi -f version=X.Y.Z`, after `ExpansionApi.VERSION` and `expansion/CHANGELOG.md` say that version. It signs with the `MAVEN_SIGNING_*` secrets, uploads through `scripts/central.py` with `publishingType=AUTOMATIC` (no draft to click) as `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the Central token for `com.mairwunnx`), waits for `PUBLISHED` and repo1.maven.org, and tags `expansion-vX.Y.Z`. A version goes to Central once and stays.
- **Security.** A resource is private unless the expansion calls `publicly()`, and then still hidden when the server's owner lists the namespace in `extensions.private`. Anonymous clients never get coordinates: a public resource checks `Request.seesCoordinates()`, and events about a player go through `EventBus` like WorldAPI's own, so vanished players stay hidden and `Visibility` strips location keys.
- **On the mods** `modcore`'s `Expansions.discover` finds services through the game's class loader; nothing binds a host yet.

## Changing endpoints

1. Change the route and its data classes.
2. `WORLDAPI_UPDATE_OPENAPI=1 ./gradlew test` rewrites `docs/openapi.json`; a stale document fails the tests.
3. In minecraft-mcp, `npm run generate` and fix what the typecheck flags.
4. Describe it on the matching API page of the wiki.

## Building and testing

- `./gradlew build` runs every module's tests and builds the shaded jar in `paper/build/libs`, plus the mods' empty jars; Java 25. Tests that need Bukkit's types live in `paper/src/test`, the rest in `core/src/test` and `expansion/src/test`.
- There is no MockBukkit for Paper 26.3; tests use the small fakes in `paper/src/test`. Anything that needs a real server goes to a local Paper in Docker (the compose snippet on the wiki's “Installation” page).
- Dependencies are shaded and relocated under `com.mairwunnx.worldapi.libs`. The SQLite driver comes from Paper; the PostgreSQL and MariaDB drivers are relocated too, so the store passes Hikari their class names from class references. `WORLDAPI_TEST_DATABASES=postgresql://u:p@host:port/db,mysql://...` runs `StoreTest` against real servers.

## Checking a change

1. `./gradlew build`: the tests and the jar.
2. A real Paper with the jar, started and stopped by the script, which fails on any error about the plugin:
   ```sh
   ./gradlew :paper:smokeJar
   python3 -I .claude/scripts/paper_smoke.py --game 26.3 --plugin paper/build/libs/worldapi-dev.jar --plugin paper/build/libs/worldapi-smoke.jar --modrinth plan --expect "Listening on .* endpoints" --expect "Registered extension: WorldAPI" --expect "shares smoke_found: 1 resources" --expect "shares smoke_code: 1 resources, 1 topics" --expect "Published ping: true" --ignore 'Plan\] Failed to enable geolocation'
   ```
   `worldapi-smoke.jar` (`paper/src/smoke`, never released) is an expansion plugin: one expansion WorldAPI finds through `META-INF/services`, one it registers in code, and an event it publishes on the first tick.
   Pass `--game` the version the project targets, and `--build` to pin a Paper build. The script downloads Paper and Modrinth plugins into `~/.cache/paper-smoke` and keeps the server folder when something fails.
   Plan reports a failing extension method only as a warning and a file in `plugins/Plan/logs/`, which the script doesn't catch: after a change to `plan/`, add `--keep` and check that folder is empty. Player values appear only once a player joins.
3. Endpoints and the WebSocket need a running server to call: the Docker compose snippet on the wiki's “Installation” page, then `curl` with the admin token from its `config.yml`. After a release reaches Moonlands, `curl -s https://minecraft.mairwunnx.com/worldapi/api/v1/server` shows its `worldApiVersion`, and the site and `scripts/live-check.mjs` in minecraft-mcp read it.

## Admin panel

- `admin/` is a React app on the same stack as Allay's panel (`~/c/personal/allay/server/admin`): Vite, TypeScript, shadcn/ui and Tailwind, TanStack Router and Query, react-hook-form and zod, Recharts, react-i18next, CodeMirror for configs. Every visible string sits in `src/locales/en.json`.
- It calls the public API with the session cookie, never a token. Types come from `docs/openapi.json`: `bun run generate` writes `src/api/generated/` (git-ignored), and every build runs it first, so a changed endpoint fails the panel's typecheck. openapi-typescript needs TypeScript 5's API, so it lives in `admin/scripts/openapi/` with its own lockfile.
- Writes send `X-WorldAPI-Panel: 1`, or WorldAPI answers 403 `csrf`. The page takes its API path from the `worldapi-api` meta tag WorldAPI injects and its assets from the `<base>`, so it works under any prefix (`/worldapi/admin/` on Moonlands). The CSP allows nothing from elsewhere: no CDNs, no inline scripts, fonts bundled, and `z.config({ jitless: true })` keeps zod from probing `eval`.
- Pages hide what `/auth/me`'s permissions don't allow; WorldAPI checks again on every call.
- Gradle's `buildPanel` runs `bun install --frozen-lockfile` and `bun run build` into `paper/build/admin-dist`, and `paper`'s `processResources` puts it in the jar. Bun must be on the PATH; `./gradlew build -PskipPanel` builds without the panel, and `/admin` then says so. CI installs Bun with `oven-sh/setup-bun`.
- Item icons come from `GET /icons/{key}` (`admin/Icons.kt`): WorldAPI downloads the game client for the server version from Mojang into `plugins/WorldAPI/cache` on first use. Never put Mojang textures in the repo or the jar.
- `cd admin && bun run dev` serves it with hot reload and passes `/api` and the WebSocket through to a local server on 8110.
- Checking a change: `cd admin && bun run typecheck`, then `./gradlew build`, a local Paper with the jar, and the panel in a headless browser at 1366 and 360 px wide: no console errors, no CSP reports, nothing scrolls sideways. A test account can go straight into `plugins/WorldAPI/worldapi.db` (`accounts`, the password as `pbkdf2$600000$<salt hex>$<hash hex>`) while the server is stopped; `/worldapi admin` refuses the console on purpose.

## Autopilot

`.github/workflows/autopilot.yml` runs Claude every Monday at 03:00 UTC: `/update-deps`, then `/update-minecraft`, a fix for whatever they break, a push to `master`, and the “Release” workflow with every store when what the project ships changed. The release's `deploy` job then starts Moonlands' `ship-plugin.yml` with the `FAMILY_ACTIONS_TOKEN` secret, and Claude puts the jar on the server there. The run itself needs the `CLAUDE_CODE_OAUTH_TOKEN` secret. “Autopilot” in `~/c/personal/minecraft/CLAUDE.md` explains the family's runs. A session with the owner still releases only when the owner asks.

## Versions

Where the versions live, for `/update-deps` and `/update-minecraft` (both in `.claude/skills/`, the same files in every project of the family):

- The game: `io.papermc.paper:paper-api` (three times: `compileOnly`, `testImplementation`, and `smokeCompileOnly`), `gameVersions` (Modrinth) and `platformVersions` (Hangar) in `paper/build.gradle.kts`, and `api-version` in `paper/src/main/resources/paper-plugin.yml` and `paper/src/smoke/resources/plugin.yml`. The mods: `neoFormVersion` in `modcore/build.gradle.kts`, `minecraft`, `loader`, `fabricApi`, and `fabricKotlin` in `fabric/build.gradle.kts`, `minecraft` and `neoforgeVersion` in `neoforge/build.gradle.kts`.
- Java: `jvmToolchain` in every Kotlin module's build file, the toolchain in `expansion/build.gradle.kts` (its bytecode stays at `release = 21`); CI's `setup-java` in `.github/workflows/`.
- Gradle in `gradle/wrapper/gradle-wrapper.properties`; Kotlin and the Gradle plugins (ModDevGradle and Loom among them) in the root `build.gradle.kts`; libraries, the JDBC drivers and ECJ among them, in `core/build.gradle.kts`, Paper-only ones in `paper/build.gradle.kts`, JUnit in each module.
- The admin panel: `admin/package.json` and `admin/bun.lock` (`bun update` there); openapi-typescript and its TypeScript 5 in `admin/scripts/openapi/package.json`; Bun itself in `oven-sh/setup-bun` in `.github/workflows/`.
- Plan: `compileOnly("com.djrapitops:plan-api:…")` from `repo.playeranalytics.net`, never shaded. Use only what the Plan servers run offers: graphs and statistics (`DATA_EXTENSION_GRAPH_API`, `DATA_EXTENSION_STATISTICS_API`) need a Plan newer than 5.8.

## Docs

- README.md and README.RU.md are the shop window: badges, a pitch, “What you get”, “Get started” in three steps, links into the wiki, and “Related projects” (the same list in every project of the family). No install alternatives, config, API, build, stack, AI, or License sections. Change both together.
- MODRINTH.md is the store page, synced to Modrinth by the release workflow (the Hangar page is edited by hand on the site): the pitch, “What you get”, “Get started”, a link to the wiki, and “Related projects”; no Moonlands banner.
- The GitHub wiki is the manual, English only: installation, first steps, tokens and permissions, the panel, configuration, the reverse proxy, security, monitoring, MCP, FAQ, the REST API by area, the WebSocket, extensions (“Extensions” for what clients read, “Expansions” for plugin authors), Java scripts, and developer pages. Clone `https://github.com/MairwunNx/minecraft-worldapi.wiki.git`, edit, and push to `master`. A new endpoint, topic, config key, or permission goes on its wiki page in the same session; endpoint tables follow `docs/openapi.json`. The wiki names the game version and Java in “Installation” and “Development”, so `/update-minecraft` changes them there too.
- Internals stay out of the READMEs; this file and the wiki's developer pages keep them.

## Releasing and Moonlands

1. Add a “## X.Y.Z” section to CHANGELOG.md, written for server owners. After a change that reaches what the project ships, add it to CHANGELOG.md in the same push: under the topmost “## X.Y.Z” section while that version isn't released (`gh release view vX.Y.Z` fails), or under a new section with the next version: patch for fixes, minor for features, major for breaking changes. A release then needs only the workflow, and the workflow runs only when the owner asks.
2. Run the “Release” workflow: `gh workflow run release.yml -R MairwunNx/minecraft-worldapi -f version=X.Y.Z -f modrinth=true -f hangar=true`. Its `changelog` field overrides the CHANGELOG.md section. It builds with `-PreleaseVersion`, tags `vX.Y.Z`, and attaches `paper/build/libs/worldapi-X.Y.Z.jar` to a GitHub release. Nothing in the repo holds the version; local and CI builds are `dev`. The expansion API releases apart (see “The expansion API”); a WorldAPI release needs it only when the API changed, and then the library goes first.
3. Modrinth: project `worldapi` (id `Q5IDSOEP`), submitted for review on 2026-10-13; `./gradlew modrinth` uploads the jar and syncs MODRINTH.md. Hangar: project `MairwunNx/WorldAPI`; `./gradlew publishPluginPublicationToHangar` adds a version to the Release channel for Paper 26.3 that links to the GitHub release jar, since the jar is over Hangar's 15 MB limit; the page doesn't sync, so edit it on the site. Both use secrets (`MODRINTH_TOKEN`, `HANGAR_TOKEN`) that also sit in `~/.secrets/cloud.env`; never put them in the repo. The Hangar key (made 2026-10-13 with `create_version`) uploads versions; project settings, the page, and the icon are edited on the site.
4. To put it on Moonlands, follow “Shipping a plugin or backend update” in `~/c/personal/minecraft/CLAUDE.md` (MairwunNx/minecraft). That file also says when a restart may happen without asking.

Why the build looks the way it does: `paper-plugin.yml` because WorldAPI is Paper-only and gets an isolated class loader and Brigadier commands; dependencies shaded so the jar works offline and can't clash with FAWE, BlueMap, Plan, or TAB; ECJ unrelocated because it finds its message bundles by package name; the SQLite driver left to Paper; JDBC drivers named through class references so the relocation holds.

## Platforms

How the family ships a project to more than one platform. A project that builds only for Paper keeps its jar's present name until it gains a second platform, so links and Moonlands' `jars.txt` keep working.

- **Modules.** One Gradle module per platform, `paper/`, `fabric/`, `neoforge/`, next to a `core/` module with what they share: the rules as plain Kotlin without game imports, data, and assets. A project with both mods adds `modcore/`, the game without a loader, compiled against NeoForm and packed into both mods' jars, so `fabric/` and `neoforge/` stay thin adapters (Allay and Red Bull do; their game tests live in `modcore/src/gametest` and run on both loaders). Every module builds from one version, and one release ships them all.
- **Jars.** `<name>-paper-X.Y.Z.jar`, `<name>-fabric-X.Y.Z.jar`, and `<name>-neoforge-X.Y.Z.jar`, each attached again without the version, so `releases/latest/download/<name>-<platform>.jar` always fetches the newest.
- **Modrinth.** One project for every platform. Each platform is its own version, numbered `X.Y.Z+paper`, `X.Y.Z+fabric`, or `X.Y.Z+neoforge`, with its own loaders and dependencies; a mod's version says it runs on the client and the server.
- **Hangar.** Paper only, numbered `X.Y.Z`.
- **Moonlands** runs the Paper jar only.

WorldAPI has `core`, `paper`, `modcore`, `fabric`, and `neoforge` since 3.4.0 (the owner decided on 2026-10-24), but the mods are frames: they build with every build and are never published. `worldapi.mods=false` in `gradle.properties` keeps it so: the Paper jar stays `worldapi-X.Y.Z.jar` and Modrinth's version `X.Y.Z`. Once the mods serve the API, set it to `true` in the same push: the Paper jar becomes `worldapi-paper-X.Y.Z.jar` (the release attaches `worldapi.jar` too), Modrinth's versions `X.Y.Z+paper`, `+fabric`, and `+neoforge` (the mods through `scripts/modrinth_mods.py`, server only), and Moonlands' `jars.txt` line needs the new name. Never publish an empty mod.

## Commits

Short one-line imperative English messages, one logical change per commit: “Send active effects and freezing with player.health”.
