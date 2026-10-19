# WorldAPI

🌍 **WorldAPI** puts your Paper server on HTTP. Drop in one jar, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the server.

## Who it’s for

- 🛠️ **Server admins.** See who’s online and what they’re doing, find what lags, follow the console live, change configs, ban, kick, restart with a countdown, from the admin panel, a script, or a chat with an AI.
- 🌐 **People who build a site or a bot.** A status page, a player list, leaderboards, player profiles, and a live feed of joins, deaths, and advancements take a few requests and no plugin of your own. Anonymous reads are safe by default, so a public site needs no token.
- 🧭 **New players.** With [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an AI assistant answers “where’s the nearest village?” or “how do I craft a shield?” from your own world.
- 🧑‍💻 **Pros.** Automate the server with events, edit blocks in bulk, manage entities, rank any vanilla statistic, and, if you allow it, run Java on the server.

## What you get

- 📡 **107 REST operations**: the server and its load, JVM and host metrics, worlds, game rules, blocks, chests and other containers, one search for blocks, biomes, and structures, entities with a summary of where they crowd, players online and offline with their inventories and deaths, statistics, advancements, play sessions, recipes, leaderboards, and the event history.
- 🗂️ **Configs and logs as files**: read them a page at a time or by search, change them with exact edits or by key, with a backup of each previous version.
- 🖥️ **An admin panel** at `/admin`: live load, the console with a command line, chat, players, files, plugins, tokens, accounts, and the audit log.
- ⚡ **A live feed over WebSocket** with 40 topics: joins, deaths, advancements, the console, positions, health, time, weather, boss kills, raids, and more.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave.
- 🎒 **Inventories in depth.** Every item comes with its enchantments, attributes, durability, potion effects, armor trim, and what’s inside shulker boxes and bundles.
- 🔌 **Other plugins’ data, too:** plugins share their own resources and live events through WorldAPI, with no library to add.
- 🔑 **Permissions and groups** for tokens and panel accounts, made in game, in the panel, or in the config.
- 🗄️ **SQLite out of the box**, or your own PostgreSQL, MySQL, or MariaDB.
- 📖 **Built-in docs** at `/api/v1/docs`, and an OpenAPI 3.1 document.
- 📊 **A section in [Plan](https://modrinth.com/plugin/plan)**, if you run it: the API's state on the server page, each player's tokens on theirs.

## Install

WorldAPI needs Paper 26.3 or a fork of it on Java 25.

**With Docker (recommended).** In [itzg/minecraft-server](https://docker-minecraft-server.readthedocs.io), name it and the server fetches it:

```yaml
environment:
  TYPE: PAPER
  VERSION: "26.3"
  MODRINTH_PROJECTS: |
    worldapi
```

**By hand.** Put the jar in `plugins/` and restart the server.

On its first start WorldAPI writes `plugins/WorldAPI/config.yml` with a fresh admin token and listens on port 8110. Try `curl http://localhost:8110/api/v1/server`, open `/api/v1/docs`, or run `/worldapi admin` in game for the admin panel at `/admin`.

Keep port 8110 private and put a reverse proxy with HTTPS in front of it: plain HTTP sends tokens and passwords in the open. The [README](https://github.com/MairwunNx/minecraft-worldapi#http-exposure) has Caddy, nginx, and Docker Compose examples.

## Commands

| Command | What it does |
|---|---|
| `/worldapi admin [group]` | A code for the admin panel at `/admin`: your first account, or a new password |
| `/worldapi token create <name>` | Opens a menu to pick groups and a lifetime; the token shows once in your chat |
| `/worldapi token list` | Every token with its permissions and expiry |
| `/worldapi token revoke <name>` | Stops a token at once |
| `/worldapi reload` | Applies `config.yml` without a restart |
| `/worldapi status` | Shows what’s running |

Every command needs `worldapi.admin`, which operators have.

## Plan

With [Plan](https://modrinth.com/plugin/plan) installed, WorldAPI adds its own section to Plan's pages. The server page shows the version, the number of endpoints, the WebSocket clients connected now, the active tokens, and the events in history. A player's page shows the tokens they made in game, with each one's permissions and expiry; a column in the players table counts them. Token values never reach Plan.

## Safety

- 🔒 **No write without a token**, and each token and account can do only what its permissions say. Nobody hands out more than they have.
- 🙈 **Private by default.** Visitors never see coordinates, inventories, IP addresses, or chat unless you turn that on.
- 👻 **Vanished stays vanished**: a player hidden by SuperVanish, PremiumVanish, or Essentials looks offline to everyone without `players:moderate`.
- 🔑 **Tokens stay secret**: compared in constant time, stored as digests, never logged.
- 📁 **Files within bounds**: only the server folder, never WorldAPI’s own folder, keys, or jars; writing needs `files:write`, which only the `owner` group has, and keeps the previous version.
- 🚦 **Limits everywhere**: rate limits, body size, WebSocket connections, and hard caps on fills, spawns, removals, and search radii.
- 🧾 **An audit log** of every change, with the token’s name.
- ⏱️ **The tick comes first.** Requests never block the server.
- 🧑‍⚖️ **Java stays off** unless you turn it on, and only the `owner` group may run it.

## Links

- Source code, issues, and the full API reference: [GitHub](https://github.com/MairwunNx/minecraft-worldapi)
- AI agents: [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an MCP server for Claude, Cursor, and others
- From the same author: [Mo’Joins](https://modrinth.com/plugin/mojoins), join messages in each player’s language, and [Mo’Afks](https://modrinth.com/plugin/moafks), a safe AFK mode
