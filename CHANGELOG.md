# Changelog

## 4.2.0

WorldAPI runs on Fabric and NeoForge now, on a dedicated server and in the world you play alone. Everything Paper serves, the mods serve the same way, so sites, minecraft-mcp, minecraft-exporter, and the dashboard work with them unchanged. On Paper nothing changes but the jar's name.

### Fabric and NeoForge

- 🧩 **Three jars.** `worldapi-paper`, `worldapi-fabric` (needs Fabric API), and `worldapi-neoforge`, for Minecraft 26.3 on Java 25. On Modrinth each is its own version, `4.2.0+paper`, `+fabric`, and `+neoforge`; Hangar keeps the Paper one. `worldapi.jar` on GitHub stays the Paper jar, so old links keep working.
- 🖥️ **A dedicated mod server** works as Paper does: the same REST API and WebSocket, config, tokens, permissions, admin panel, and `/worldapi`. `/server/plugins` lists the mods. Permissions in game come from fabric-permissions-api (LuckPerms, for one) or NeoForge's PermissionAPI, else operator level 2.
- 🌍 **Worlds by dimension.** On the mods the worlds are `overworld`, `the_nether`, `the_end`, and a mod's own as `namespace:path`. Every platform also takes the other's names, so `world_nether` finds the Nether on Fabric, and `the_nether` on Paper.
- 🔌 **Expansions in mods.** A Fabric or NeoForge mod shares its data through the same expansion library as a plugin: WorldAPI finds it through `META-INF/services`.
- 📜 **Java scripts stay on Paper.** On the mods `/scripts` answers 501 `not_supported`.

### Singleplayer

- 🎮 **Your world on HTTP.** Put the mod in your game, open a world, and WorldAPI listens on `127.0.0.1:8110` for as long as it's open, then frees the port when you leave. A second game takes the next port.
- 💾 **Each world its own data.** History, sessions, and issued tokens live in the save's `worldapi/` folder, and move, copy, and go with it. The config and its token in `config/worldapi/` serve every world.
- 🤖 **minecraft-mcp finds the game by itself.** While a world is open, WorldAPI writes its address and token to `~/.worldapi/local/`, readable by you only. `/worldapi connect` copies the token and the line that adds minecraft-mcp to Claude Code.
- 🛡️ **Only this computer.** A singleplayer world never listens beyond `127.0.0.1`, and refuses requests whose `Host` or a browser's `Origin` points elsewhere, so a web page can't reach it through DNS rebinding. LAN guests play as usual and don't reach the API.

### Everywhere

- 🧭 **Where it runs.** `GET /server` adds `platform` (`paper`, `fabric`, `neoforge`), `side` (`dedicated`, or `integrated` under a singleplayer world), `lan`, `cheats`, and `paused`.
- 👀 **What a player looks at.** `GET /players/{player}?include=target` adds the block and the entity in their line of sight within 64 blocks, and where their eyes are. Needs `players:private`.
- 🔒 **The same guard on Paper.** A Paper server that listens on a loopback address checks `Host` and `Origin` the same way.

## 4.1.1

A security release that takes something away from moderators: making operators now needs `server:console`.

### Security

- 🔒 **Moderators could reach every permission.** `players:moderate`, which the default `moderator` group has, could make any player an operator. An operator may run `/worldapi`, which makes tokens and panel codes with any permission, `code` and `files:write` among them. Making or unmaking operators now needs `server:console`, which only `admin` and `owner` have by default: `PATCH /server/ops`, `PATCH /players/{player}/permissions`, and writing `ops.json` through `/files`.
- 🔒 **WorldAPI's own permissions stay out of the API.** `POST /server/commands` refuses any command that names a `worldapi.*` node, such as `lp user Steve permission set worldapi.admin true`, with 403 `protected_permission`, whatever the token holds. Change those permissions in game or in your permissions plugin.

### What changes for moderators

- A `moderator` token or account can no longer make or unmake operators; it gets 403 `missing_scope`. Kicks, bans, the whitelist, and reading the operator list stay as they were. Give `server:console` on purpose to anyone who should make operators, knowing it reaches as far as `owner`.
- The panel shows the operator button on a player's page only to accounts with `server:console`.

## 4.1.0

- ⌨️ **The server's commands.** `GET /server/commands` lists every command the server has, once each: the game's, the server's, and every plugin's, Brigadier ones included, with its aliases, the plugin that owns it, its description, and its usage, such as `/give <targets> <item> [<count>]`. `query` searches names, aliases, and descriptions, several searches at once; `plugin` lists one plugin's. WorldAPI builds the list once and builds it again when a plugin is enabled or disabled. The MCP server's `search_commands` reads it.
- 🔑 **A permission for it.** `commands:read` reads the catalog without running anything; `server:console` reads it too. The default `moderator`, `admin`, and `owner` groups have it; `viewer` and anonymous visitors don't. If your `config.yml` lists its own groups, add `commands:read` where you want it.
- 🖥️ **The panel knows the commands.** The console's command line suggests commands as you type and shows the usage of the one you picked. The plugins page shows what each plugin shares through the expansion API and how many commands it has.

## 4.0.1

A security release. Update soon if anyone but you holds a token or a panel account.

### Security

- 🔒 **A token or account without permissions got every permission.** A token with an empty list, an issued token whose group you later removed from `config.yml`, or a panel account with such a group passed every permission check, `code` and `files:write` among them. Anyone with `tokens:manage` could make such a token. Now it gets nothing, and `POST /tokens` asks for at least one group or permission.
- 🔒 **A token named `anonymous` counted as an anonymous visitor**, with a visitor's view and rate limit. A token is now always a token, whatever its name.
- 🔒 **The docs page pins its script.** `/docs` loaded the newest Scalar from a CDN on the panel's own origin, so a bad release there could have acted with a signed-in admin's session. It now loads one version, checked against its hash, under a Content-Security-Policy.
- 🔒 **WebSocket clients have a message limit.** One connection could send subscriptions without end, each building a snapshot on the server thread, or try one token after another. A connection now sends up to 30 messages at once and 120 a minute; past that it gets `rate_limited`.
- 🔒 **Anonymous clients lose coordinates at any depth.** Location keys inside an object or a list of an event, as a plugin's event may carry them, are stripped too, not only those at the top.
- 🔒 **A grep can't hang a thread.** `GET /files/read` with `regex=true` stops after 5 seconds with 400 `grep_timeout`, so a pattern that backtracks forever can't keep a thread busy.
- 🔒 **Smaller things.** Sign-in bodies are read up to 4 KiB, not in full first. A failed sign-in keeps the name in the audit log only when it could be an account name, since anyone on the internet types it and AI agents read that log. `X-Forwarded-Prefix` counts only as a plain path for the OpenAPI document, which keeps a few copies at most. Anonymous clients no longer see a plugin's error message, only that it failed. The list of item keys without an icon stays bounded. `config.yml` refuses a token that is still a `${VARIABLE}` placeholder. The panel's sign-in sends you back only to a page on its own site.

## 4.0.0

WorldAPI 4.0 keeps one way for other plugins to share their data: the expansion API. Nothing changes for clients, sites, or the MCP server; only plugins that still use the older bridge need an update.

- 💥 **Breaking: the bridge is gone.** The `java.util.function.Function` WorldAPI put in Bukkit's `ServicesManager` for other plugins, with requests as maps, no longer exists. A plugin that still registers through it finds nothing and shares nothing, without an error. Plugins share through the [expansion API](https://github.com/MairwunNx/minecraft-worldapi/wiki/Expansions), `com.mairwunnx:worldapi-expansion` on Maven Central since 3.4.0, under the same namespaces, paths, and topics. Mo’Bosses moved in 2.9.0, so update it to 2.9.0 or newer before WorldAPI, or its data drops out of `/extensions/mobosses` until you do.

## 3.4.0

- 🧩 **An expansion API for other plugins.** `com.mairwunnx:worldapi-expansion` on Maven Central is a small Java library without dependencies: a plugin lists its resources and event types, WorldAPI serves them at `/extensions/<id>/…` and as `ext.<id>.*` topics, and the plugin publishes its events with one call. Register it from code, or name it in `META-INF/services` and WorldAPI finds it by itself. A resource learns who asks and whether they may see coordinates, so a public resource can leave them out for visitors. The bridge through Bukkit's services keeps working as before. The wiki's [Expansions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Expansions) shows a plugin from start to end.
- 🏗️ **Ready for more platforms.** The code now sits in modules: `core` without Bukkit, `paper` for the plugin, and frames for Fabric and NeoForge mods, which don't do anything yet and aren't published. The plugin, its endpoints, its config, and its jar's name stay the same.

## 3.3.0

- 📍 **The spawn point.** `GET /spawn` shows where players join for the first time and respawn without a bed: the world, the point, which way they face, the respawn radius, and spawn protection. `PATCH /spawn` moves it, to another world too, and changes the radius. A world's page in the panel shows it and, with `world:write`, moves it.
- 🐛 **Spawn pitch.** Moving a world's spawn with `PATCH /worlds/{world}` no longer resets where players look up or down.
- 📖 **A manual on the wiki.** The docs moved to the [GitHub wiki](https://github.com/MairwunNx/minecraft-worldapi/wiki), step by step. The HTTP exposure warning in the console links to its “Reverse proxy” page.

## 3.2.0

- 🧊 **Blocks look like blocks.** Item icons draw block items as the game’s inventory does: an isometric cube with the top lit and the sides shaded, slabs half as tall, grass and leaves in their plains colors, and chests, ender chests, and barrels with their lids and latches. Other items stay flat, as in the game. Block icons are 64×64.
- 🔑 **A forgotten password has a second way back.** `POST /accounts/{name}/reset` gives an admin with `tokens:manage` a code that sets a new password for someone who can’t get into the game; the panel shows it next to each account. `/worldapi admin` in game still works too.
- 💬 **The panel writes to chat** as the server, from the Chat page.
- In the panel, worlds in the overview link to their pages, stack counts are white with a shadow, as in the game, and icons that weren’t ready yet appear without a reload.

## 3.1.0

- 🧰 **A menu in game.** `/worldapi` alone opens a chest menu: WorldAPI’s state, a code for the admin panel, tokens to see, create (type the name in chat), and revoke, panel accounts, groups, and a config reload. The subcommands stay.
- 🔗 **A link to the panel.** With the new `panel.publicUrl` in `config.yml`, `/worldapi admin` sends a clickable link that opens the panel with the code already filled in.
- 🖥️ **The panel grows.**
  - A Worlds section edits time, weather, difficulty, game rules, and the border, and shows entities, the busiest chunks, a locate box, and containers near a point.
  - Inventories, ender chests, and chests show real item icons, and their slots take part of a stack, put an item in, or clear.
  - Enchantments read as in the game’s tooltip, one per line with Roman numerals and curses in red.
  - A player’s page lists every advancement by tab, and every death a page at a time.
  - The file editor highlights YAML, JSON, .properties, TOML, and logs.
  - A link with `?code=` opens the sign-in with the code filled in.
- 🖼️ **Item icons** for the panel at `GET /icons/{key}`. Mojang’s textures can’t ship in the jar, so WorldAPI downloads the game’s client for the server’s version from Mojang once, into `plugins/WorldAPI/cache`, and reads icons from it. `panel.icons: false` turns this off.
- ✂️ **Take part of a stack.** `POST …/slots/{slot}/take` with an `amount` takes items from a player’s inventory, ender chest, or a container, and leaves the rest with its enchantments and name.
- 📝 **ops.json, whitelist.json, and the ban lists are writable** through `/files`. WorldAPI applies the new content through the server, which saves the file itself, so nothing it keeps in memory gets lost. Every file says why when it stays read-only (`readOnlyReason`).
- `POST /server/commands` runs commands as the console, so `say` reads “[Server] …” in game instead of “[FeedbackForwardingSender] …”. The output is what the command printed to the console.

## 3.0.0

WorldAPI 3.0 gives server owners an admin panel, configs and logs as files, and finer permissions. Read “Upgrading from 2.x” below before you update: the API changed in places.

- 🖥️ **An admin panel** at `/admin`, served by WorldAPI itself: live load, JVM and host numbers, the console streaming live with a command line, chat, players with their inventories and deaths, files with an editor, plugins, tokens, accounts, and the audit log. Run `/worldapi admin` in game for a code, then sign in with a password. Each account sees what its groups allow.
- 🔑 **Permissions and groups replace scopes.** Sixteen permissions, from `server:read` to `files:write`, and four groups in `config.yml` (`viewer`, `moderator`, `admin`, `owner`) that you can change or extend. Tokens and accounts list either. Nobody hands out a permission they lack. The in-game token menu offers groups.
- 🗂️ **Configs and logs as files.** `GET /files` lists a folder, and `GET /files/read` reads numbered lines a page at a time, the last lines, or by search with context, gzipped logs included. `POST /files/edit` replaces exact text, `POST /files/set` sets keys in YAML (comments kept), `.properties`, or JSON, and `PUT /files/write` replaces a file. Writing needs `files:write`, which only `owner` has. Every write keeps the previous version and checks the file didn’t change meanwhile. `GET /server/configs` lists the server’s and each plugin’s configs.
- 🧍 **A player in one request.** `GET /players/{player}?include=inventory,enderchest,effects,deaths,sessions,statistics,advancements`. Inventories and ender chests of offline players now read from their playerdata. `deaths` gives the last death place and the newest five deaths with causes and killers.
- 📦 **Containers.** `GET /worlds/{world}/containers` finds chests, barrels, shulker boxes, and the like near a point, with how many of an item each holds. New endpoints put items into a container’s slots, empty them, or add to a container.
- 🧭 **One search for everything.** `GET /worlds/{world}/locate?kind=block|biome|structure` finds the nearest blocks, biomes, or structures, several targets at once.
- 🐄 **Entities without a flood.** `GET /worlds/{world}/entities/summary` counts entities by type and names the busiest chunks with their hoppers. Entity lists page 25 at a time and filter by name and chunk. An entity shows a villager’s trades, a dropped item’s stack, its lead, and why it spawned.
- 📈 **Deeper numbers.** `GET /server/performance` adds the JVM: memory pools, garbage collection, threads, classes, buffers. `GET /server/system` describes the host: load, memory, swap, disk, container limits, open files. `GET /server` lists the datapacks, and `GET /server/plugins` each plugin’s config files.
- 🖨️ **The console live,** on the `server.console` topic, with tokens blanked as in `GET /server/logs`.
- ⏹️ **`POST /server/stop`**, with a countdown like restart.
- 🚨 **A warning when WorldAPI is reachable without HTTPS.** When a request comes from the internet straight to the port, or through a proxy that serves plain HTTP, the console shows a framed warning (at most every 6 hours) and the admin panel a red banner before anyone types a password. Requests from the machine itself and local networks don’t count.
- `limit=0` on a paged list answers with the total alone.
- `GET /players/{player}/inventory` and `/enderchest` read offline players from their playerdata too.
- The OpenAPI document names each operation’s permission in `x-scope`, the alternatives in `x-scopes-any`, and in `x-also-needs` the one that coordinates and items need on top, so clients can tell what a token may call.
- `invulnerable` in entity and player data shows the flag that `PATCH` sets again. Since Paper 26.3 build 205 the old check answers whether damage gets through, which is also true for a player in creative mode.
- Exposed 1.6.0 inside the jar. Built against Paper API 26.3 build 205 with Gradle 9.8.1.

### Upgrading from 2.x

- **Tokens keep working.** `read` now means the `viewer` group, `write:world` and `write:players` mean `world:write` and `players:write`, and `admin` is the `admin` group: everything but `files:write` and `code`. The log names tokens that still use the old names. Add `groups` to `auth` in your `config.yml` only if you want to change them; the defaults apply without it.
- **The `admin` scope no longer covers `code`, and neither does the `admin` group.** Give `code` or the `owner` group to whoever runs Java scripts.
- **Removed:** `GET /worlds/{world}/structures/nearest` and `GET /worlds/{world}/search`. Use `GET /worlds/{world}/locate`; its results come in `found`, each with `type`, `x`, `y`, `z`, and `distance`.
- **Entity lists** return 25 entities per page unless you pass `limit`.
- **The `hello` message on the WebSocket** lists permissions such as `server:read` instead of scopes.
- **Vanished players** show to clients with `players:moderate` rather than `admin`; the `admin` group has it.
- The database gets two tables, `accounts` and `admin_sessions`, on the first start. Nothing else changes in it.

## 2.4.0

- 🐺 **A leaderboard of tamed animals,** `GET /leaderboards/tamed`. The game keeps no such statistic, so WorldAPI counts the pets already in the world once, by their owners, from the loaded mobs and the entity files of every world, and adds every taming after that. A pet that died before that first count left no trace. The database gets two small tables on the first start.

No field was renamed or removed.

## 2.3.0

- 🔌 **Other plugins share their data through WorldAPI.** A plugin registers a namespace through a bridge in Bukkit’s services, with nothing of WorldAPI’s on its classpath: resources WorldAPI reads from it on request, at `GET /extensions/{namespace}/{path}`, and events it publishes, as `ext.<namespace>.<event>` topics on the WebSocket and, when it asks, in the event history. The plugin keeps its data; WorldAPI stores none of it. Anonymous clients see only what the plugin marks public, and the new `extensions.private` setting closes any of it. `GET /extensions` lists what a client may read. The reference describes the contract for plugin authors.
- The default `auth.anonymous.endpoints` adds `/extensions` and `/extensions/**`; add them to your own `config.yml` to let visitors read public plugin data.

No field was renamed or removed.

## 2.2.0

- 📊 **A section in Plan.** With [Plan](https://modrinth.com/plugin/plan) installed, its server page shows WorldAPI's version, endpoints, WebSocket clients connected now, active tokens, and events in history. Each player's page lists the tokens they made in game, with scopes and expiry, and the players table counts them. Token values never reach Plan. Plan is optional; WorldAPI runs the same without it.
- 👻 **Vanished players stay hidden.** While a vanish plugin (SuperVanish, PremiumVanish, Essentials, and others) hides a player, every client without the `admin` scope sees them as offline: not in `GET /players/online` or the online counts, `online: false` without a position in player lists and profiles, missing from entity lists, and `409 player_offline` where a player must be online. Live events and snapshots about them reach `admin` clients only, and so do the events and play sessions recorded while they were hidden. Counts inside events leave them out for everyone. The database gets a `hidden` column in `events` and `sessions` on the first start; existing rows stay visible.

## 2.1.0

- **Items tell what they are.** Wherever WorldAPI shows an item, it now adds the enchantments stored in an enchanted book, the item’s attributes (a sword’s attack damage and speed, armor points and toughness, custom modifiers), the effects of potions, tipped arrows, and suspicious stew, armor trims, dye colors, the anvil repair cost, a written book’s title and author, firework flight and explosions, and what’s inside shulker boxes and bundles. Fields an item doesn’t have are left out, so payloads stay small.
- A player’s worn and held items in `GET /worlds/{world}/entities/{uuid}` now follow `privacy.anonymous.inventories`, like the rest of their inventory.

No field was renamed or removed.

## 2.0.0

WorldAPI goes public: MIT-licensed, on Modrinth and Hangar, with releases made by hand from GitHub Actions.

- **Pick your database.** SQLite stays the default and needs no setup. PostgreSQL, MySQL, and MariaDB work too: set the new `database` section in `config.yml`. An existing `worldapi.db` keeps working with no data lost.
- **Find things nearby** with `GET /worlds/{world}/search`: the nearest blocks of some types in loaded chunks, or the nearest biome. Block search always needs a token, since it would work as an x-ray.
- **Recipes** with `GET /recipes`: what makes an item, and what uses it.
- **The console log** with `GET /server/logs`, for admin tokens, with tokens blanked out.
- **Restart with a countdown** with `POST /server/restart`: it warns players in chat, saves, and stops the server; `DELETE` calls it off. Something must start the server again, such as Docker’s restart policy.
- **Clear entities in bulk** with `DELETE /worlds/{world}/entities`: by type and area, sparing players, named mobs, and pets, with a dry run.
- **Find lag faster**: `GET /worlds/{world}/chunks?sort=entities` puts the busiest chunks first, and `GET /server/performance` names each world’s most common entity types.
- A world’s time now says how many ticks are left until night and whether beds work.
- Advancements name their parent, so the list of unfinished ones reads as what to do next.
- New `limits`: `maxBlockSearchRadius`, `maxBiomeSearchRadius`, `maxEntityRemoval`, `consoleLines`. Older configs load as they are.

No field was renamed or removed, so 1.x clients keep working.

## 1.1.1

- Each player’s experience total starts from the `XpTotal` the game already keeps, instead of from zero.

## 1.1.0

- `GET /server` carries TPS, tick time, heap, loaded chunks, and entities.
- New leaderboards: netherite mined, and experience gained, which WorldAPI counts itself.

## 1.0.2

- `player.health` carries active effects and freezing.

## 1.0.1

- Tokens made in game with a scope menu, listed and revoked with `/worldapi token`.
- Port 8110 by default, clear of BlueMap’s 8100.
- Request bodies are capped, WebSockets are limited per client, and only known proxies may set `X-Forwarded-For`.
- Online players’ playtime reads under its vanilla id, so they stay on the boards.

## 1.0.0

- The first release: REST and WebSocket access to the server, worlds, blocks, entities, players, inventories, statistics, leaderboards, and events; tokens with scopes; an OpenAPI document; Java scripts, off by default.
