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

## Releasing and Moonlands

1. Bump the default version in `build.gradle.kts` and commit “Release X.Y.Z”.
2. Push the tag `vX.Y.Z`; CI builds the jar and attaches it to a GitHub release.
3. To put it on Moonlands, follow “Shipping a plugin or backend update” in `~/c/personal/minecraft/CLAUDE.md` (MairwunNx/minecraft): the jar goes to that repo's private `plugins` release and `minecraft/extras/jars.txt`. The server restarts only with the owner's go-ahead.

## Commits

Short one-line imperative English messages, one logical change per commit: “Send active effects and freezing with player.health”.
