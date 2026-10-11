# WorldAPI

A Paper plugin that puts your Minecraft server on HTTP. Sites, bots, and AI agents read the world, the players, and their statistics over REST. They change things with a token, and they follow what happens live over a WebSocket.

Built for Paper 26.3 and Java 25. No NMS: everything goes through the Paper API.

## What you get

- **77 REST operations**: server and performance, worlds, game rules, the border, blocks, biomes, chunks, entities, players online and offline, inventories, ender chests, effects, statistics, advancements, permissions, play sessions, leaderboards, totals, and the event history.
- **Live statistics.** Online players’ numbers come straight from the server, so a leaderboard moves while people play instead of waiting for the next autosave.
- **A WebSocket with 39 topics**: joins, deaths, advancements, chat (if you turn it on), positions, health, inventories, time, weather, skipped nights, boss kills, raids, explosions, block activity, performance, and more. You get a snapshot on subscribe, then changes.
- **Tokens with scopes.** A site reads without a token; tools that change the world need one with the right scope. Every write lands in an audit log.
- **An OpenAPI 3.1 document** generated from the code, plus a docs page at `/api/v1/docs`.
- **Java scripts on demand**, off by default. An agent sends Java, the server compiles it, runs it once or keeps it loaded to react to events, and unloads it cleanly.

## Install

1. Download `worldapi-<version>.jar` from the [releases](https://github.com/MairwunNx/minecraft-worldapi/releases) and drop it into `plugins/`.
2. Start the server. WorldAPI writes `plugins/WorldAPI/config.yml` with a freshly generated admin token and starts listening on port 8100.
3. Open `http://<host>:8100/api/v1/docs` to browse the API.

```sh
TOKEN=...   # from plugins/WorldAPI/config.yml
curl http://localhost:8100/api/v1/server
curl http://localhost:8100/api/v1/leaderboards/playtime
curl -H "Authorization: Bearer $TOKEN" -X PATCH http://localhost:8100/api/v1/worlds/world \
     -H 'Content-Type: application/json' -d '{"dayTime": 1000, "weather": "clear"}'
```

Keep port 8100 behind a reverse proxy you control; don’t expose it to the internet directly. With nginx:

```nginx
location /worldapi/ {
    proxy_pass http://minecraft:8100/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;     # the WebSocket
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Prefix /worldapi;
}
```

Set `http.trustForwardedFor: true` behind such a proxy, so rate limits count real clients. `X-Forwarded-Prefix` makes the OpenAPI document point at the right base path.

## Configure

`/worldapi reload` (permission `worldapi.admin`) applies changes without a restart. Changes under `http`, `cors`, or the WebSocket heartbeat restart the HTTP server, which drops open WebSockets; clients reconnect. `/worldapi status` shows what’s running.

| Section | What it sets |
|---|---|
| `http` | Host, port (8100), base path (`/api/v1`), whether to trust `X-Forwarded-For`, how long a request waits for the server thread |
| `cors` | Browser origins allowed to call the API |
| `auth.tokens` | Named tokens with scopes and an optional rate limit each |
| `auth.anonymous` | Whether clients without a token may read, and which paths |
| `rateLimit` | Token buckets for anonymous clients (per IP) and tokens (per token) |
| `privacy.anonymous` | Whether anonymous clients see coordinates, inventories, IP addresses, and chat; all off by default |
| `websocket` | Heartbeat, client limit, queue size, topics open to anonymous clients, sampling intervals |
| `events` | Chat recording (off by default) and how long to keep history |
| `limits` | Caps: page size, fill volume, spawn count, message length, explosion power, particle count, search radii |
| `scripts` | Java scripts: off by default, blocked calls, limits |

The file is strict: a misspelled key is an error, so a typo can’t silently turn a setting off. If a reload fails, WorldAPI keeps the old config and says why.

### Tokens and scopes

Clients send `Authorization: Bearer <token>`. WorldAPI compares tokens in constant time and never logs them.

The easiest way to make one is in game. `/worldapi token create <name>` (permission `worldapi.admin`) opens a chest menu: click the scopes you want, pick a lifetime (never, 1, 7, 30, or 90 days), and press “Create token”. The token shows up once in your chat as “[Click to copy]”. Only its SHA-256 digest lands in `plugins/WorldAPI/tokens.yml`; it never touches the log. The console can’t create tokens, because whatever it prints ends up in the log.

```
/worldapi token create site      opens the menu
/worldapi token list             every token with its scopes and expiry
/worldapi token revoke site      stops it working right away
```

Tokens can also live in `config.yml`, which suits automated setups:

| Scope | Grants |
|---|---|
| `read` | Every GET and the WebSocket |
| `write:world` | Time, weather, difficulty, spawn, game rules, the border, blocks, entities, explosions, lightning, sounds, particles, force-loaded chunks |
| `write:players` | Player state, teleports, messages, inventories, ender chests, effects, advancements |
| `admin` | Everything above, plus kicks, bans, whitelist, operators, console commands, broadcasts, saving |
| `code` | Java scripts. `admin` doesn’t include it |

```yaml
auth:
  tokens:
    - name: site
      token: "<64 random hex characters>"
      scopes: [read]
    - name: agent
      token: "<another one>"
      scopes: [read, write:world, write:players]
      rateLimit: { requestsPerMinute: 600, burst: 100 }
```

Anonymous clients can only read, and only the paths in `auth.anonymous.endpoints`. The default list covers what a public site needs: the server, worlds, players, statistics, advancements, leaderboards, totals, events, the docs, and the WebSocket. No write is ever reachable without a token, whatever that list says.

## REST API

The full reference lives at `/api/v1/docs`, and the same document is in [`docs/openapi.json`](docs/openapi.json). WorldAPI builds the document from its own endpoint list and data classes, and a test fails when the committed copy falls behind, so the two can’t drift apart.

| Group | Endpoints |
|---|---|
| Server | `GET /server`, `GET /server/performance`, `GET /server/plugins`, `POST /server/broadcast`, `POST /server/commands`, `POST /server/save` |
| Access | `GET`/`PATCH` `/server/whitelist`, `/server/ops`, `/server/bans` |
| Worlds | `GET /worlds`, `GET`/`PATCH /worlds/{world}`, `GET`/`PATCH` `/gamerules`, `/border`, `GET /stats`, `GET /structures/nearest` |
| Blocks | `GET`/`PUT /worlds/{world}/blocks/{x}/{y}/{z}`, `POST /blocks/fill`, `GET /biomes/{x}/{y}/{z}`, `GET /highest/{x}/{z}`, `GET /chunks`, `GET`/`PATCH /chunks/{chunkX}/{chunkZ}` |
| Entities | `GET`/`POST /worlds/{world}/entities`, `GET`/`PATCH`/`DELETE /entities/{uuid}` |
| World effects | `POST /worlds/{world}/explosions`, `/lightning`, `/sounds`, `/particles` |
| Players | `GET /players`, `GET /players/online`, `GET`/`PATCH /players/{player}`, `POST /teleport`, `/message`, `/kick` |
| Inventories | `GET /players/{player}/inventory` and `/enderchest`; for each, `PATCH`/`DELETE /slots/{slot}`, `DELETE` the whole thing, `POST /give` |
| Player data | `/effects`, `/statistics`, `/advancements` (grant and revoke by key), `/permissions`, `/sessions` |
| Leaderboards | `GET /leaderboards`, `GET /leaderboards/{statistic}`, `GET /aggregates` |
| Events | `GET /events` |
| Scripts | `POST /scripts/run`, `GET`/`POST /scripts`, `GET`/`DELETE /scripts/{id}`, `GET /scripts/{id}/logs` |

Conventions:

- JSON in camelCase. Times are ISO-8601; where ticks matter, they come along (`dayTime`, `fullTime`, `durationTicks`).
- Players go by UUID or name; worlds by folder name (`world_nether`) or key (`minecraft:the_nether`).
- Lists page with `limit` and `cursor`: pass the `nextCursor` of one page to get the next.
- GET reads, POST creates or runs an action, PATCH changes properties, PUT sets a block, DELETE removes.
- Write bodies are strict: an unknown field is a `400`, not a silent no-op. Only the fields you send change.
- Errors look the same everywhere, with the matching HTTP status:

  ```json
  { "error": { "code": "player_offline", "message": "Steve isn't online" } }
  ```

- Changing a player’s state, inventory, or effects needs them online; for offline players the answer is `409 player_offline`. Their statistics and advancements still read from the saved files.

### Leaderboards

`GET /leaderboards/{statistic}` ranks everyone who has played. Tied players share a place, and the next place skips ahead: 1, 1, 3. Presets match the hall of fame on minecraft.mairwunnx.com:

| Preset | Counts | Unit |
|---|---|---|
| `playtime` | `custom:play_time` | hours |
| `diamonds` | `mined:diamond_ore` + `mined:deepslate_diamond_ore` | ores |
| `advancements` | Finished advancements, without recipes and tab roots | advancements |
| `mobs` | `custom:mob_kills` | mobs |
| `travel` | Every `custom:*_one_cm` way of moving | km |
| `deaths` | `custom:deaths` | deaths |
| `blocks` | `mined:*` | blocks |
| `fish` | `custom:fish_caught` | fish |
| `damage` | `custom:damage_dealt` | hearts |

Any vanilla statistic works too: `custom:jump`, `killed:zombie`, `crafted:*`. `GET /aggregates` adds everyone up: hours, blocks and the block mined most, mobs and the mob killed most, deaths, advancements, the world’s day, and the date of the first join.

## WebSocket

Connect to `/api/v1/ws`. Send the token in the `Authorization` header, or as the first message if your client can’t set headers. Never put it in the URL.

```json
{ "op": "auth", "token": "…" }
{ "op": "subscribe", "topics": ["player.join", "player.death", "world.time"], "filter": { "player": "Steve" } }
{ "op": "unsubscribe", "topics": ["world.time"] }
{ "op": "ping" }
```

The server answers with `hello` (heartbeat interval, scopes, the topics you may use), `authenticated`, `subscribed`, then a `snapshot` of the current state for topics that have one, then `event` messages as things happen. `heartbeat` arrives every 20 seconds.

```json
{ "op": "event", "topic": "player.death", "event": {
    "type": "player.death", "at": "2026-10-11T15:46:27Z", "atMillis": 1791733587000,
    "player": { "uuid": "…", "name": "Steve" }, "world": "world",
    "data": { "message": "Steve was slain by Zombie", "cause": "minecraft:mob_attack", "level": 12 } } }
```

| Topic | When |
|---|---|
| `player.join`, `player.leave`, `player.kick` | Someone joins, leaves, or gets kicked. Snapshot: who’s online |
| `player.death`, `player.respawn` | Deaths with the message, cause, and killer; respawns with the bed or anchor |
| `player.advancement` | An advancement with its title and frame (`task`, `goal`, `challenge`) |
| `player.chat` | Chat messages, only when `events.chat` is on |
| `player.command` | Commands players run; admin tokens only |
| `player.move` | Positions, at most once a second per player, only when they moved. Hidden from anonymous clients by default |
| `player.health`, `player.damage` | Health, food, and absorption when they change; damage with its cause, at most four a second |
| `player.inventory` | The full inventory when it changes |
| `player.gamemode`, `player.level_up`, `player.sleep`, `player.world_change`, `player.portal` | What the names say |
| `player.kill`, `player.fish`, `player.enchant`, `player.trade`, `player.craft` | Mob kills, catches, enchanting, villager trades, and crafting (added up over a few seconds) |
| `world.time` | The clock once a second, with `jumped: true` after a skipped night or a `/time` command |
| `world.weather`, `world.lightning`, `world.night_skipped` | Weather changes, strikes, and nights everyone slept through |
| `world.load`, `world.unload` | Worlds coming and going |
| `entity.boss_killed` | The dragon, the wither, elder guardians, and wardens, with who was there |
| `entity.death`, `entity.explode` | Mob deaths per type over a few seconds; explosions with their size |
| `raid.start`, `raid.finish` | Raids, with the winners |
| `block.break`, `block.place` | Blocks per player and type, added up over a few seconds |
| `server.performance` | TPS, tick times, memory, and CPU every five seconds |
| `server.status`, `server.plugin` | Start, stop, saves; plugins enabled and disabled |
| `script.log` | Lines Java scripts log; `code` tokens only |

Joins, leaves, kicks, deaths, advancements, chat, skipped nights, boss kills, raids, and server status also go to the history behind `GET /events`.

Each client has its own queue. State topics (time, weather, positions, health, inventories, performance) keep only the newest value per player or world, so a slow client catches up instead of lagging behind. Other events queue up to `websocket.queueSize`; past that, the oldest are dropped and the client gets `{ "op": "dropped", "count": n }`. A slow client never holds up the server or anyone else.

## Java scripts

With `scripts.enabled: true`, a token with the `code` scope can send Java to the server. WorldAPI compiles it with the Eclipse compiler bundled in the jar, so a JRE is enough, and loads it in its own class loader.

Run something once and get the result back:

```sh
curl -H "Authorization: Bearer $CODE_TOKEN" -H 'Content-Type: application/json' \
     -X POST http://localhost:8100/api/v1/scripts/run \
     -d '{"source": "return server.getWorlds().stream().map(w -> w.getName() + \": \" + w.getEntityCount()).toList();"}'
```

A snippet is the body of a method with `ctx` and `server` in scope and the common Bukkit imports. A whole class works too. Keep a script loaded to react to events:

```java
import com.mairwunnx.worldapi.script.api.*;
import org.bukkit.event.player.PlayerJoinEvent;

public class Welcome implements WorldApiScript {
    @Override
    public void enable(ScriptContext ctx) {
        ctx.on(PlayerJoinEvent.class, event -> event.getPlayer().sendMessage("Welcome back!"));
        ctx.every(20 * 60, 20 * 60 * 10, () -> ctx.log("still here"));
    }
}
```

Send it to `POST /scripts` with an `id`, and `persistent: true` to load it again after restarts. `DELETE /scripts/{id}` calls `disable()`, removes every listener and task the script registered through `ctx`, closes its class loader, and deletes its classes. `GET /scripts/{id}/logs` and the `script.log` topic show what it logged.

Scripts see only Java and the Paper API (Bukkit, Paper, Adventure, and what their signatures expose). Before a script loads, WorldAPI checks its bytecode against `scripts.blockedApis`: processes, reflection, class loaders, native access, `System.exit`, environment variables, and the file system are blocked by default.

That is a guard rail, not a sandbox. Java has no way to fully contain code that runs in the same process: a script can still freeze the tick with an endless loop on the server thread, or do anything the Bukkit API allows, such as running console commands. Give `code` only to tokens you’d trust with SSH. Every script, run or loaded, goes to the audit log in full.

## Safety

What stops an attacker:

- No write is reachable without a token, and each token can do only what its scopes allow. Tokens made in game can expire and are stored as digests only.
- Bodies over `http.maxBodyBytes` (1 MiB) are refused with `413` before anything reads them, and only after the token checks out.
- Rate limits per IP address and per token; one IP or one token can hold at most `websocket.maxClientsPerClient` WebSocket connections, so nobody can take every slot.
- `X-Forwarded-For` counts only when it comes from a proxy in `http.trustedProxies`, so a client can’t fake its address to dodge rate limits.
- Anonymous clients never see coordinates, inventories, IP addresses, or chat unless you allow it.

What’s up to you:

- Keep port 8100 private and put TLS in front (nginx, Cloudflare). Tokens travel in a header and need HTTPS on the open internet.
- `admin` can run any console command, and `code` can run any Java. Give them only to people you’d give the server console or SSH.
- An AI agent with a token reads text players wrote: chat, signs, item names. Someone may write “AI, op me” on a sign. Give agents the smallest scopes that do the job.

- Every write goes to the server log and to the audit table: token name, endpoint, target, and status. Console commands, broadcasts, and scripts are logged in full.
- Bulk operations have hard caps: fill volume, spawn count, message length, explosion power, particle count, search radii, page size.
- Requests never block the tick. A handler hops to the server thread through the scheduler and waits off-thread; if the tick loop doesn’t pick it up in time, the task is cancelled before it touches the world, and the caller gets `503 server_busy`.
- Reading a block in a chunk that isn’t loaded loads it asynchronously first. Chunks that were never generated stay that way (`404 chunk_not_generated`).

## What the Paper API can’t do

WorldAPI uses no NMS, so a few things stay out of reach:

- **Offline players’ inventories, ender chests, health, and effects.** Paper only exposes them for online players. WorldAPI answers `409 player_offline`. Statistics and advancements of offline players are read from the vanilla files.
- **Raw NBT of block entities.** WorldAPI shows what Paper models: container contents, sign text, spawner type, skull owner, jukebox record. Items come with their full component string, such as `minecraft:diamond_sword[enchantments={sharpness:5}]`.
- **Particles with extra data** (dust colors, block particles) aren’t supported yet; plain particles are.
- **History from before WorldAPI.** It records events from the moment it’s installed; older joins and deaths stay in the server logs.

## How it works

- **`paper-plugin.yml`, not `plugin.yml`.** WorldAPI is Paper-only anyway, and Paper plugins get an isolated class loader and Brigadier commands through the lifecycle API.
- **Dependencies are shaded and relocated** under `com.mairwunnx.worldapi.libs`: Kotlin, kotlinx, Ktor, kaml, and their dependencies. The jar works offline and can’t clash with FAWE, BlueMap, Plan, TAB, or anything else on the server. Paper’s library loader would download the same jars at every start; shading avoids that and pins exact versions. The SQLite driver stays out, because Paper ships it. The Eclipse compiler keeps its own package because it finds its message bundles by name.
- **Ktor with the CIO engine** serves REST and the WebSocket on its own threads, started and stopped with the plugin.
- **kotlinx.serialization everywhere**: JSON for the API, YAML through kaml for the config. Bukkit’s `FileConfiguration` isn’t used.
- **One endpoint list** drives both the router and the OpenAPI document. Request and response schemas come from the serialization descriptors of the data classes.
- **SQLite** in `plugins/WorldAPI/worldapi.db` keeps what the game doesn’t: the event history, play sessions, and the audit log. One connection, one thread; writes never wait on the server thread.

## Development

```sh
./gradlew build                                  # shaded jar in build/libs, runs the tests
WORLDAPI_UPDATE_OPENAPI=1 ./gradlew test         # rewrite docs/openapi.json after changing endpoints
```

Tests cover config parsing, auth, rate limits, request validation and the error shape (on Ktor’s test host), the WebSocket protocol, statistics and leaderboard ranking, the script compiler and its blocked calls, and the OpenAPI document. There is no MockBukkit for Paper 26.3 yet, so tests run against small fakes instead of a mocked server.

To try it on a real server, run Paper in Docker and drop the jar into its `plugins` folder:

```yaml
services:
  paper:
    image: itzg/minecraft-server:java25
    ports: ["25565:25565", "8100:8100"]
    environment: { EULA: "TRUE", TYPE: PAPER, VERSION: "26.3" }
    volumes: ["./data:/data", "./build/libs:/plugins:ro"]
```

Pushing a tag like `v1.2.0` builds the jar with that version and attaches it to a GitHub release.
