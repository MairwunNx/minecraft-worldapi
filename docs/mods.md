# WorldAPI on Fabric and NeoForge

The design of WorldAPI 4.2.0 on the mods, as built on 2026-10-25. It says how WorldAPI runs as a mod: on a dedicated Fabric or NeoForge server it behaves as the Paper plugin does; in singleplayer it serves the world you play, so an AI agent through minecraft-mcp can read it while you play.

## Modules

Paper becomes an adapter like the mods. What moves where:

| Module | Gets |
|---|---|
| `core` | `WorldApi`, abstract, with what a platform answers as hooks (statistics, player names, online players, config files, lists), the HTTP server, the endpoint DSL, `ApiCall`, auth, the WebSocket hub, the OpenAPI generator, the panel's serving and sign-in, the event bus and `Visibility`, and every route that never touches the world: tokens, events, files, extensions, statistics boards, management, scripts' 501 |
| `paper` | `PaperApi`, the routes that read or change the world (server, worlds, blocks, containers, entities, players, inventories, recipes, search, access), the listeners, the scripts' compiler, Plan, the token menu |
| `modcore` | `ModApi` and the same world routes written against the game's own classes, the listeners (`Hooks`, mixins where loaders have no event), `Runtime`, the lifecycle per server, `/worldapi` with Brigadier |
| `fabric`, `neoforge` | The entry, events to `Hooks`, `Loader`: the mod list, permissions, the folder for config |

A world route keeps one declaration per platform, and `ParityTest` builds the OpenAPI document from Paper's list and from modcore's and fails on any difference: the same paths, permissions, parameters, and schemas. Handlers differ only in how they reach the game. Paper's handlers move without changes to their bodies, and a capture of every GET's answer on a local Paper, before and after, must match (as when the models moved to `core`).

## Lifecycle

The game starts a server for each world you open and stops it when you leave; one game may do that many times. WorldAPI builds everything for a server at `SERVER_STARTING` and closes it at `SERVER_STOPPED`: the HTTP server, the WebSocket hub, the store, the expansions' binding. Nothing outlives a server, so leaving a world frees the port and its database.

- **Port.** A dedicated server binds `http.host:http.port`, as on Paper. A singleplayer world binds `127.0.0.1` and tries `http.port`, then the next nine ports, so a second game or a second world in another launcher instance still starts. The chosen port goes into the log and the discovery file.
- **Discovery.** While a world is open, WorldAPI writes `~/.worldapi/local/<port>.json` (mode 600): the URL, the token, the world's name, the game's process id, and when it started. It removes the file when the server stops, and a client skips files whose process is gone. minecraft-mcp reads this folder when `WORLDAPI_URL` is unset.
- **Between worlds** the port is closed: MCP answers “No Minecraft world with WorldAPI is open. Start the game and open a world, or set WORLDAPI_URL.”

## Data per world

| What | Where | Why |
|---|---|---|
| `config.yml` with the generated token | `config/worldapi/` of the game | One token and port for every world, so MCP's setup never changes |
| History, sessions, audit log, experience, issued tokens, panel accounts | `<save>/worldapi/worldapi.db` | Each world its own story; it moves with the save |
| Backups of `/files` writes, the icon cache | `config/worldapi/backups/`, `config/worldapi/cache/` | Not the world's: shared and safe to delete |

A copied world carries its history and issued tokens, which is what copying means. A renamed world keeps its data, since the folder moves. A deleted world takes its data with it. A dedicated mod server keeps the data in `world/worldapi/` as well; the database settings apply to it, while a singleplayer world always uses SQLite in its save.

## Worlds and files

26.3 keeps one save with every dimension inside: `dimensions/<namespace>/<path>/region`, and players in `players/data`, `players/stats`, `players/advancements` (read from `LevelResource` and `DimensionType.getStorageFolder` in the 26.3 jar). Paper keeps its folders per world (`world`, `world_nether`, `world_the_end`).

- On the mods a world's `name` is its dimension: `overworld`, `the_nether`, `the_end`, and a mod's own as `namespace:path`. `key` stays the full id on every platform, as it is now.
- Every platform finds a world by `name`, by `key`, and by the other platform's names: `world`, `world_nether`, `world_the_end` on the mods, `overworld` and its kin on Paper. A client that only knows one naming keeps working.
- `/files` on a dedicated mod server follows the same rules as on Paper, with `config/` in place of `plugins/` and `mods/` jars refused. In singleplayer it reaches the save folder and `config/`, never `options.txt`, the launcher's files, or other saves.

## Singleplayer and LAN

- **Who you are.** The world's owner may run `/worldapi`, cheats or not; the generated token is the `admin` group's, as on Paper.
- **Cheats.** The token's permissions decide, as on a server: an agent with `world:write` builds in a world without cheats too. `/server`'s `cheats` tells clients whether the world allows commands, so an agent can say so before it acts.
- **LAN guests** are players like on any server: they show in `/players`, their events stream, and they never reach the API, which listens on `127.0.0.1` only. `/server` says `lan: true` while the world is open to LAN.
- **No console.** There is no server console in a singleplayer game; `/server/logs` reads the game's log, console commands run as the server, and restarting or stopping answers 409 `not_on_integrated`: you leave a world in game.

## Security on localhost

- The first start generates the token into `config/worldapi/config.yml`, as on Paper. In singleplayer, the first world you open greets you in chat; `/worldapi connect` shows the address, a button that copies the token, and one that copies the MCP setup line.
- `/worldapi admin` gives a code for a panel account in this world, with a link that fills it in, as Paper's token menu does.
- DNS rebinding: when the server listens on a loopback address, WorldAPI answers only requests whose `Host` names the loopback (`localhost`, `127.0.0.1`, `[::1]`, with any port), and refuses a browser's `Origin` from elsewhere unless `cors.allowedOrigins` names it. This guards Paper too, when it listens on loopback.

## What clients see

`GET /server` gains fields, and nothing goes away:

- `platform`: `paper`, `fabric`, or `neoforge`.
- `side`: `dedicated` or `integrated`.
- `lan`: whether a singleplayer world is open to LAN; `false` on a dedicated server.
- `cheats`: whether commands are allowed, `true` on a dedicated server.
- `paused`: whether a singleplayer game is paused; the tick rate and TPS then read as frozen.

`/server/plugins` lists the loader's mods (without the game and the loader themselves), with versions, authors, and `folder` under `config/`. Bans, the whitelist, and operators work on every server the game runs, singleplayer included. Making operators needs `server:console` on every platform, and the console refuses commands naming `worldapi.*`, as 4.1.1 decided.

- **Permissions in game** (`/worldapi`, the token menu): fabric-permissions-api on Fabric when present, NeoForge's PermissionAPI, else operator level 2, and the world's owner always.
- **Commands** (`GET /server/commands`): from the server's Brigadier tree, the owner by the command's namespace or by the mod whose jar registered it (`Loader.owner`).
- **Events**: chat, joins, quits, deaths, advancements, blocks, and the rest through `Hooks`, fed by Fabric API's and NeoForge's events, and by mixins in `modcore` where neither has one, so both mods see the same moment.
- **Scripts**: Paper only for now: `ScriptContext` is Bukkit's types. On the mods `/scripts` answers 501 `not_supported`, and `/server`'s `platform` lets clients hide them.

## New on every platform

- `GET /players/{player}?include=target`: the block and the entity the player looks at (up to 64 blocks), with the eye's position and direction. Needs `players:private`.
- `/server`'s `platform`, `side`, `lan`, `cheats`, and `paused`.

Locating structures, the seed, building, giving, and teleporting already have endpoints and tools. minecraft-mcp reads `get_player` with `target` for “what am I looking at”, finds the open world through the discovery file, tells the agent about singleplayer, and leaves the script tools out on the mods.

## Monitoring

- minecraft-exporter adds `platform` and `side` labels to `minecraft_info`, and a `kind` label (`plugin` or `mod`) to `minecraft_plugin_enabled`. No metric changes its name, so the exporter's release is a minor one.
- It adds `minecraft_paused` too. The dashboard gets hidden `$multiplayer` and `$singleplayer` variables from `minecraft_info`'s `side`. The rows that mean nothing alone (players, plugins and the console, chat and events) repeat over `$multiplayer`, which is empty for `integrated`, so they disappear in singleplayer; a “Your world” row over `$singleplayer` shows the game's state, play time, level, deaths, diamonds, advancements, health and food, and the mods instead. Status reads “Paused” while the game is.
- Singleplayer: WorldAPI listens on `127.0.0.1` and checks `Host`, so a container can't reach it. The exporter runs beside the game (its binary), and `examples/singleplayer/compose.yaml` in minecraft-exporter runs Prometheus and Grafana with the dashboard, scraping `host.docker.internal:10059`.

## Expansions

`ExpansionMods` finds services through the game's class loader, and each server binds the host at start and unbinds at stop; a game test checks a test mod's expansion on both loaders. The expansion API stays as it is; `Request` gains nothing.

## Releases

`worldapi.mods=true`: `worldapi-paper-X.Y.Z.jar`, `worldapi-fabric-X.Y.Z.jar`, `worldapi-neoforge-X.Y.Z.jar`, each also without the version. Modrinth `X.Y.Z+paper`, `+fabric` (Fabric API required), `+neoforge`, the mods `client_or_server`. Hangar gets the Paper jar as `X.Y.Z`. Moonlands' `jars.txt` moves to `worldapi-paper-X.Y.Z.jar`.
