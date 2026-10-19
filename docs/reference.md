# WorldAPI reference

Everything a client can call, for people who build sites, bots, and tools on top of WorldAPI. Installing and configuring the plugin lives in the [README](https://github.com/MairwunNx/minecraft-worldapi#readme). The same endpoints, with every field, are at `/api/v1/docs` on your server.

## REST API

The full reference lives at `/api/v1/docs`, and the same document is in [`openapi.json`](openapi.json). WorldAPI builds the document from its own endpoint list and data classes, and a test fails when the committed copy falls behind, so the two can’t drift apart.

| Group | Endpoints |
|---|---|
| Server | `GET /server`, `GET /server/performance`, `GET /server/system`, `GET /server/plugins`, `GET /server/configs`, `GET /server/logs`, `POST /server/broadcast`, `POST /server/commands`, `POST /server/save`, `POST`/`DELETE /server/restart` and `/server/stop` |
| Access | `GET`/`PATCH` `/server/whitelist`, `/server/ops`, `/server/bans` |
| Worlds | `GET /worlds`, `GET`/`PATCH /worlds/{world}`, `GET`/`PATCH` `/gamerules`, `/border`, `GET /stats`, `GET /locate` |
| Blocks | `GET`/`PUT /worlds/{world}/blocks/{x}/{y}/{z}`, `POST /blocks/fill`, `GET /biomes/{x}/{y}/{z}`, `GET /highest/{x}/{z}`, `GET /chunks`, `GET`/`PATCH /chunks/{chunkX}/{chunkZ}` |
| Containers | `GET /worlds/{world}/containers`; at a block, `PATCH`/`DELETE /container/slots/{slot}`, `DELETE /container`, `POST /container/give` |
| Entities | `GET`/`POST`/`DELETE /worlds/{world}/entities`, `GET /entities/summary`, `GET`/`PATCH`/`DELETE /entities/{uuid}` |
| World effects | `POST /worlds/{world}/explosions`, `/lightning`, `/sounds`, `/particles` |
| Players | `GET /players`, `GET /players/online`, `GET`/`PATCH /players/{player}`, `POST /teleport`, `/message`, `/kick` |
| Inventories | `GET /players/{player}/inventory` and `/enderchest`; for each, `PATCH`/`DELETE /slots/{slot}`, `DELETE` the whole thing, `POST /give` |
| Player data | `/effects`, `/statistics`, `/advancements` (grant and revoke by key), `/permissions`, `/sessions` |
| Leaderboards | `GET /leaderboards`, `GET /leaderboards/{statistic}`, `GET /aggregates` |
| Recipes | `GET /recipes` |
| Events | `GET /events` |
| Files | `GET /files`, `GET /files/read`, `PUT /files/write`, `POST /files/edit`, `POST /files/set` |
| Access control | `GET`/`POST /tokens`, `DELETE /tokens/{name}`, `GET /groups`, `GET /accounts`, `POST /accounts/invite`, `POST /accounts/{name}/reset`, `PATCH`/`DELETE /accounts/{name}`, `GET /audit` |
| Panel sign-in | `GET /auth/status`, `GET /auth/me`, `POST /auth/login`, `/auth/setup`, `/auth/password`, `/auth/logout` (not in the OpenAPI document) |
| Icons | `GET /icons/{key}`, an item’s PNG icon from the game’s textures (not in the OpenAPI document) |
| Scripts | `POST /scripts/run`, `GET`/`POST /scripts`, `GET`/`DELETE /scripts/{id}`, `GET /scripts/{id}/logs` |

Conventions:

- JSON in camelCase. Times are ISO-8601; where ticks matter, they come along (`dayTime`, `fullTime`, `durationTicks`).
- Players go by UUID or name; worlds by folder name (`world_nether`) or key (`minecraft:the_nether`).
- Lists page with `limit` and `cursor`: pass the `nextCursor` of one page to get the next. Lists with a `total` answer `limit=0` with the total alone, for a quick count.
- Each operation names the permission it needs; the README lists them and the groups. `GET /auth/me` says what a token or account has.
- GET reads, POST creates or runs an action, PATCH changes properties, PUT sets a block, DELETE removes.
- Write bodies are strict: an unknown field is a `400`, not a silent no-op. Only the fields you send change.
- Errors look the same everywhere, with the matching HTTP status:

  ```json
  { "error": { "code": "player_offline", "message": "Steve isn't online" } }
  ```

- `GET /server` carries the current load next to the version and settings, so a status page needs one request: `tps`, `tps5m`, and `tps15m` (capped at 20), `mspt`, `memoryUsedMb` and `memoryMaxMb` for the Java heap, and `loadedChunks` and `entities` across every world. `GET /server/performance` breaks the same numbers down by world and adds tick percentiles, CPU, and `jvm`: heap and non-heap memory, each memory pool, garbage collection per collector, threads, loaded classes, and direct buffers. `GET /server/system` (`system:read`) describes the host: load averages, memory and swap, the disk under the server folder, cgroup limits in a container, open files, resident memory, and the Java runtime; Linux fills in more than other systems. `GET /server` also lists the datapacks, enabled or not.
- `GET /players/{player}?include=inventory,enderchest,effects,deaths,sessions,statistics,advancements` adds those parts to the profile in one answer. An offline player’s inventory and ender chest come from their playerdata file. `deaths` holds the last place the game saw them die and the newest five deaths from the history, with causes and killers.
- Changing a player’s state, inventory, or effects needs them online; for offline players the answer is `409 player_offline`. Their statistics and advancements still read from the saved files.
- Items look the same everywhere: inventories, ender chests, containers in `GET /blocks`, entity equipment, and the `player.inventory` topic. Next to the type, name, and `enchantments`, an item carries whatever applies to it: `storedEnchantments` on enchanted books; `attributes`, the modifiers the game applies, a type’s defaults included; `potion` with the effects of a potion, tipped arrow, or suspicious stew; `trim`; `dyedColor`; `repairCost` for the anvil; a written book’s title and author, without its pages; a firework’s flight and explosions; and the `contents` of a shulker box or bundle, one level deep. Fields that don’t apply are left out, and `components` still holds the whole item in command syntax. Attribute amounts are what the item adds: the tooltip shows attack damage 1 higher and attack speed 4 higher, since it counts the player’s base values.
- Anonymous clients see no items that belong to a player unless `privacy.anonymous.inventories` is on: no container contents, no `player.inventory`, and no equipment on a player in `GET /entities/{uuid}`. Mobs and armor stands show their gear to everyone, as the game does.
- `GET /players/{player}/advancements` lists every advancement on the server, started or not, with its title and parent, so `done=false` reads as what’s left to do.
- `GET /worlds/{world}/entities/summary` counts entities per type, dropped items, villagers, named mobs, and pets, and names the ten busiest chunks with their block entities and hoppers, for the whole world or around a point. `GET /worlds/{world}/chunks?sort=entities` and the entity types in `GET /server/performance` add to it: together they find what lags. Entity lists page 25 at a time by default and filter by `type`, `name`, `chunk`, or a point and radius.
- `GET /worlds/{world}/locate?kind=block|biome|structure&target=…` finds the nearest of anything. Block search (`target=minecraft:diamond_ore,#minecraft:logs`) reads copies of chunks that are already loaded, off the server thread, and never loads more; it needs a token whatever `auth.anonymous` says, because it would show ores through walls. Biomes and structures ask the world generator, so they reach unexplored land, and give the nearest of each target; structures take prefixes, so `village` finds every kind of village. Anonymous clients locate only when they may see coordinates.
- `POST …/slots/{slot}/take` with `{"amount": n}` takes items from a player’s inventory or ender chest, or a container, and leaves the rest of the stack as it was.
- `GET /icons/minecraft:diamond_sword` returns an item’s icon as a PNG, for item grids: blocks as the game’s isometric cube at 64×64, other items flat at their texture’s size. The game’s textures can’t ship in the jar, so WorldAPI downloads the client for the server’s version from Mojang once, into `plugins/WorldAPI/cache`; until it has it, the answer is `503 icons_loading` with `Retry-After`. `panel.icons: false` turns it off.
- `GET /worlds/{world}/containers?x=&y=&z=&radius=&item=minecraft:diamond` finds chests, barrels, shulker boxes, hoppers, and the like in loaded chunks, nearest first, with how many of the items each holds, or their five most plentiful items without `item`. A double chest counts once. `GET /blocks/{x}/{y}/{z}` shows one in full; the container endpoints under a block change its slots.
- `DELETE /worlds/{world}/entities?type=minecraft:item` clears entities in bulk, around a point if you give one. It never touches players, skips named mobs and tamed pets unless `includeNamed=true`, and `dryRun=true` only counts.
- `GET /server/logs` returns recent console lines (`logs:read`), filtered by `level` and `grep`. WorldAPI keeps them from its own start, up to `limits.consoleLines`, and blanks out its tokens. The `server.console` topic streams the same lines live; older logs are files under `logs/`.
- Vanished players stay hidden from every client without `players:moderate`, anonymous ones included. WorldAPI calls a player vanished while a vanish plugin (SuperVanish, PremiumVanish, Essentials, and others) sets the `vanished` metadata on them. To such a client they look offline: they're missing from `GET /players/online` and the online counts in `GET /server` and `GET /server/performance`; `GET /players` and `GET /players/{player}` show them with `online: false`, no live state, and no position; leaderboards mark them offline; entity lists leave them out, and `GET /worlds/{world}/entities/{uuid}` answers `404`. Endpoints that need an online player answer `409 player_offline`, so a `players:write` token can't reach them either. `GET /events` and `GET /players/{player}/sessions` leave out what was recorded while the player was vanished, and the session of a player who vanished after joining. Clients with `players:moderate` see everyone.
- `POST /server/restart` counts down in chat, saves every world and player, and stops the server; `DELETE` calls it off. Something must start the server again: Docker’s restart policy, or a start script that loops. `POST /server/stop` does the same with “stops” in the warnings; whether the server stays down depends on what runs it.

### Files

`/files` reaches the server folder: `server.properties`, `bukkit.yml`, `spigot.yml`, Paper’s `config/`, every plugin’s folder, a world’s `paper-world.yml`, and `logs/`. `GET /server/configs` lists the configs that exist here. Paths are relative, with forward slashes.

- **Reading** needs `files:read`, or `logs:read` for `logs/`. `GET /files?path=plugins/TAB` lists a folder. `GET /files/read?path=…` returns numbered lines as an editor shows them, `<number>\t<text>`, 200 at a time from `offset`, or the last `tail` lines, or with `grep` (text, or a regular expression with `regex=true`) the matching lines with `context` lines around each, `--` between groups. `nextOffset` reads on; `totalLines` and `matches` count the whole file. Lines past 2000 characters are cut, an answer stops at `limits.maxReadChars`, and gzipped logs read as text.
- **Writing** needs `files:write`, which only the `owner` group has unless you add it elsewhere. `POST /files/edit` replaces exact text, each `oldText` found once unless `replaceAll`, all edits or none. `POST /files/set` sets keys by dotted path in YAML (comments kept), `.properties` (only the lines it sets change), or JSON; `null` removes a key. `PUT /files/write` replaces a file whole or creates one. Send the `sha256` from `/files/read` as `expectedSha256`, and a write fails with `409 file_changed` when someone changed the file since.
- `ops.json`, `whitelist.json`, `banned-players.json`, and `banned-ips.json` go through the server: WorldAPI reads the new content as the list it should become, adds and removes entries to match, and the server saves the file in its own format. Each file in `/files` and `/files/read` says why it stays read-only in `readOnlyReason`.
- Every write keeps the previous version in `plugins/WorldAPI/backups/`, `limits.fileBackups` per file, and goes to the audit log with the lines added and removed. The server and plugins read their configs at start or on their own reload command, so a change takes a restart or that reload.
- Never shared: WorldAPI’s own folder (its tokens, database, and backups), hidden files, keys, databases, jars, and anything a link leads to outside the server folder. Never written: `logs/`, `usercache.json`, and `version_history.json`, which the server writes by itself.

### Admin panel and accounts

The panel at `/admin` calls this same API with a session cookie instead of a token. `POST /auth/setup` takes a code from `/worldapi admin` in game, or an invite from `POST /accounts/invite`, with a password, and signs in; `POST /auth/login` signs in later. The cookie is HttpOnly and SameSite=Strict, and every write it carries must also send `X-WorldAPI-Panel: 1`, so another site can’t act through it. `GET /auth/me` answers for tokens too, with the permissions they come to. Tokens, accounts, and the audit log are under “Access control” (`tokens:manage`); nobody hands out a permission they lack.

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
| `netherite` | `mined:ancient_debris` | debris |
| `experience` | Experience points gained in total: the game’s `XpTotal` when WorldAPI first saw the player, plus every pickup since | xp |
| `tamed` | Animals tamed: the pets each player owned in the world when WorldAPI first counted, plus every taming since | animals |

Vanilla doesn’t keep lifetime experience, so WorldAPI counts it itself. Each player starts once from the `XpTotal` in their playerdata: everyone with a playerdata file when 1.1.1 first starts, anyone else when they join. From then on, every orb a player picks up, after other plugins had their say, adds to the total in `worldapi.db`, and `/xp` commands don’t count. `XpTotal` holds only what the player gathered since their last death, so the starting point is a lower bound of their real lifetime experience. If 1.1.0 already counted pickups, the player starts from whichever is larger, the count or `XpTotal`, since `XpTotal` already includes what 1.1.0 counted.

Vanilla doesn’t count tamed animals either. On the first start of 2.4.0, WorldAPI counts the pets already in the world by their owners: wolves, cats, parrots, horses, donkeys, mules, llamas, camels, and the rest that keep an owner, from the loaded mobs and from the entity files of every world, once. Every taming after that adds one. A pet that died before that first count left no trace, so the board is a lower bound for the oldest players. The log says how many pets it found, and how many chunks it couldn’t read, such as LZ4-compressed ones.

Any vanilla statistic works too: `custom:jump`, `killed:zombie`, `crafted:*`. `GET /aggregates` adds everyone up: hours, blocks and the block mined most, mobs and the mob killed most, deaths, advancements, the world’s day, and the date of the first join.

## WebSocket

Events about a vanished player, and their state in snapshots, reach clients with `players:moderate` only. Counts inside events (`online` in `player.join`, `player.leave`, and `server.status`; sleepers in `player.sleep` and `world.night_skipped`; players per world in `server.performance`) leave vanished players out for everyone.

Connect to `/api/v1/ws`. Send the token in the `Authorization` header, or as the first message if your client can’t set headers. Never put it in the URL.

```json
{ "op": "auth", "token": "…" }
{ "op": "subscribe", "topics": ["player.join", "player.death", "world.time"], "filter": { "player": "Steve" } }
{ "op": "unsubscribe", "topics": ["world.time"] }
{ "op": "ping" }
```

The server answers with `hello` (heartbeat interval, permissions, the topics you may use), `authenticated`, `subscribed`, then a `snapshot` of the current state for topics that have one, then `event` messages as things happen. `heartbeat` arrives every 20 seconds.

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
| `player.command` | Commands players run; `logs:read` only |
| `player.move` | Positions, at most once a second per player, only when they moved. Hidden from anonymous clients by default |
| `player.health`, `player.damage` | Health with its maximum, food, absorption, active effects, and whether the player is frozen, when any of them change; damage with its cause, at most four a second |
| `player.inventory` | The full inventory when it changes |
| `player.gamemode`, `player.level_up`, `player.sleep`, `player.world_change`, `player.portal` | What the names say |
| `player.kill`, `player.fish`, `player.enchant`, `player.trade`, `player.craft` | Mob kills, catches, enchanting, villager trades, and crafting (added up over a few seconds) |
| `world.time` | The clock once a second, with ticks until night, whether beds work, and `jumped: true` after a skipped night or a `/time` command |
| `world.weather`, `world.lightning`, `world.night_skipped` | Weather changes, strikes, and nights everyone slept through |
| `world.load`, `world.unload` | Worlds coming and going |
| `entity.boss_killed` | The dragon, the wither, elder guardians, and wardens, with who was there |
| `entity.death`, `entity.explode` | Mob deaths per type over a few seconds; explosions with their size |
| `raid.start`, `raid.finish` | Raids, with the winners |
| `block.break`, `block.place` | Blocks per player and type, added up over a few seconds |
| `server.performance` | TPS, tick times, memory, and CPU every five seconds |
| `server.status`, `server.plugin` | Start, stop, saves; plugins enabled and disabled |
| `server.console` | Console lines as they’re printed, colors stripped and tokens blanked; `logs:read` only |
| `script.log` | Lines Java scripts log; `code` only |

Joins, leaves, kicks, deaths, advancements, chat, skipped nights, boss kills, raids, and server status also go to the history behind `GET /events`.

Each client has its own queue. State topics (time, weather, positions, health, inventories, performance) keep only the newest value per player or world, so a slow client catches up instead of lagging behind. Other events queue up to `websocket.queueSize`; past that, the oldest are dropped and the client gets `{ "op": "dropped", "count": n }`. A slow client never holds up the server or anyone else.

## Extensions

Other plugins share their data through WorldAPI without depending on it: no library to add, nothing of WorldAPI’s on their classpath. A plugin stays the source of truth. WorldAPI reads its resources when a client asks and keeps none of them; it records only the events the plugin marks `recorded`, in the event history like its own.

### The bridge

WorldAPI registers a `java.util.function.Function<Map<String, Any?>, Any?>` in Bukkit’s `ServicesManager`. Find the one whose plugin is WorldAPI, and send it requests as maps of plain Java values. Every request carries `v`, the contract version (1 now); WorldAPI refuses a version newer than it knows, so check the answer’s `contract` if you rely on something new. Answers are maps: `ok`, `contract`, `version`. A bad request throws `IllegalArgumentException`.

Declare WorldAPI as an optional dependency that loads before your plugin (`load: BEFORE`, `required: false` in `paper-plugin.yml`), and register in `onEnable`. If WorldAPI starts later, listen for `ServiceRegisterEvent` and register then.

```kotlin
@Suppress("UNCHECKED_CAST")
val bridge = Bukkit.getServicesManager().getRegistrations(java.util.function.Function::class.java)
    .firstOrNull { it.plugin.name == "WorldAPI" }?.provider as? java.util.function.Function<Map<String, Any?>, Any?>

bridge?.apply(mapOf(
    "v" to 1, "op" to "register", "plugin" to this, "namespace" to "mobosses", "description" to "Bosses and their hunters",
    "resources" to listOf(
        mapOf("path" to "hunters", "description" to "The best hunters", "public" to true,
            "get" to java.util.function.Function<Map<String, String>, Any?> { topHunters() }),
        mapOf("path" to "players/{player}", "description" to "A player's card", "public" to true,
            "get" to java.util.function.Function<Map<String, String>, Any?> { card(it.getValue("player")) }),
        mapOf("path" to "bosses", "description" to "Bosses alive, with coordinates", "mainThread" to true,
            "get" to java.util.function.Function<Map<String, String>, Any?> { liveBosses() }),
    ),
    "events" to listOf(
        mapOf("name" to "boss.killed", "description" to "Players killed a boss", "public" to true, "recorded" to true),
    ),
))

bridge?.apply(mapOf("v" to 1, "op" to "publish", "namespace" to "mobosses", "event" to "boss.killed",
    "data" to mapOf("type" to "zombie", "level" to 40), "player" to player.uniqueId, "playerName" to player.name, "world" to world.name))
```

| Op | Fields |
|---|---|
| `register` | `plugin` (your plugin), `namespace` (1 to 32 of `a-z 0-9 _ -`), `description`, `resources`, `events`. Registering again replaces the namespace; another plugin can’t take it |
| `publish` | `namespace`, `event`, `data` (a map), and optionally `player` (a UUID), `playerName`, `world`. Safe from any thread; it reaches clients from the server thread |
| `unregister` | `namespace`. WorldAPI also drops a plugin’s namespaces when it stops |

**Resources.** `path` is segments of `a-z 0-9 _ -`, or `{name}` for a parameter. `get` takes the path parameters and the query parameters, as strings, and returns plain Java values: maps, lists, arrays, strings, numbers, booleans, and null. UUIDs, enums, and times turn into strings; anything else into its `toString()`. It may return a `CompletableFuture` of those too, which WorldAPI waits up to 10 seconds for. `get` runs on a worker thread, so it must be thread-safe, unless the resource sets `mainThread: true`. Throw `IllegalArgumentException` for 400, `NoSuchElementException` for 404.

**Events.** Each becomes the topic `ext.<namespace>.<name>`, on the WebSocket and, when `recorded`, in `GET /events`. Their data loses `location`, `position`, and the other coordinate keys for anonymous clients unless `privacy.anonymous.coordinates` is on, as with WorldAPI’s own topics.

**Privacy.** Everything a plugin shares needs a token, unless the plugin marks it `public`. Then anonymous clients may read it too, unless the server’s `extensions.private` lists its namespace, its resource (`mobosses/bosses`), or its topic (`ext.mobosses.boss.spawned`). Anonymous clients reach these through `/extensions` and `/extensions/**` in `auth.anonymous.endpoints`, which the default config has.

### Reading it

- `GET /extensions` lists the namespaces, with their resources and topics, that the client may see.
- `GET /extensions/{namespace}/{path...}` reads one resource, like `/extensions/mobosses/players/Steve`, straight from the plugin.
- Subscribe to `ext.mobosses.boss.killed` like any topic; `GET /events?type=ext.mobosses.boss.killed` returns the recorded ones.

## Java scripts

With `scripts.enabled: true`, a token with the `code` permission can send Java to the server. WorldAPI compiles it with the Eclipse compiler bundled in the jar, so a JRE is enough, and loads it in its own class loader.

Run something once and get the result back:

```sh
curl -H "Authorization: Bearer $CODE_TOKEN" -H 'Content-Type: application/json' \
     -X POST http://localhost:8110/api/v1/scripts/run \
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

## What the Paper API can’t do

WorldAPI uses no NMS, so a few things stay out of reach:

- **Offline players’ inventories, ender chests, health, and effects.** Paper only exposes them for online players. WorldAPI answers `409 player_offline`. Statistics, advancements, and the `XpTotal` of offline players are read from the vanilla files.
- **Raw NBT of block entities.** WorldAPI shows what Paper models: container contents, sign text, spawner type, skull owner, jukebox record. Items come with their full component string, such as `minecraft:diamond_sword[enchantments={sharpness:5}]`, next to the fields above.
- **Particles with extra data** (dust colors, block particles) aren’t supported yet; plain particles are.
- **History from before WorldAPI.** It records events from the moment it’s installed; older joins and deaths stay in the server logs.
