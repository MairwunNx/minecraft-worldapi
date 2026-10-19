# Changelog

## 3.2.1

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
