# WorldAPI

🌍 **WorldAPI** puts your Paper server on HTTP. Drop in one jar, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the server.

## Who it’s for

- 🛠️ **Server admins.** See who’s online and what they’re doing, find what lags, read the console, ban, kick, restart with a countdown, all from a script or a chat with an AI.
- 🌐 **People who build a site or a bot.** A status page, a player list, leaderboards, player profiles, and a live feed of joins, deaths, and advancements take a few requests and no plugin of your own. Anonymous reads are safe by default, so a public site needs no token.
- 🧭 **New players.** With [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an AI assistant answers “where’s the nearest village?” or “how do I craft a shield?” from your own world.
- 🧑‍💻 **Pros.** Automate the server with events, edit blocks in bulk, manage entities, rank any vanilla statistic, and, if you allow it, run Java on the server.

## What you get

- 📡 **83 REST operations**: the server and its load, the console log, worlds, game rules, blocks and biomes with nearby search, entities, players online and offline, inventories, effects, statistics, advancements, play sessions, recipes, leaderboards, and the event history.
- ⚡ **A live feed over WebSocket** with 39 topics: joins, deaths, advancements, positions, health, time, weather, boss kills, raids, and more.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave.
- 🎒 **Inventories in depth.** Every item comes with its enchantments, attributes, durability, potion effects, armor trim, and what’s inside shulker boxes and bundles.
- 🔑 **Tokens with scopes**, made in a chest menu in game.
- 🗄️ **SQLite out of the box**, or your own PostgreSQL, MySQL, or MariaDB.
- 📖 **Built-in docs** at `/api/v1/docs`, and an OpenAPI 3.1 document.

## Install

1. Put the jar in your server’s `plugins/` folder and restart the server.
2. WorldAPI writes `plugins/WorldAPI/config.yml` with a fresh admin token and listens on port 8110.
3. Open `http://<host>:8110/api/v1/docs`, or make a token in game with `/worldapi token create <name>`.

Keep port 8110 private and put a reverse proxy with HTTPS in front of it; the [README](https://github.com/MairwunNx/minecraft-worldapi#install) has an nginx example.

## Commands

| Command | What it does |
|---|---|
| `/worldapi token create <name>` | Opens a menu to pick scopes and a lifetime; the token shows once in your chat |
| `/worldapi token list` | Every token with its scopes and expiry |
| `/worldapi token revoke <name>` | Stops a token at once |
| `/worldapi reload` | Applies `config.yml` without a restart |
| `/worldapi status` | Shows what’s running |

Every command needs `worldapi.admin`, which operators have.

## Safety

- 🔒 **No write without a token**, and each token can do only what its scopes say.
- 🙈 **Private by default.** Visitors never see coordinates, inventories, IP addresses, or chat unless you turn that on.
- 🔑 **Tokens stay secret**: compared in constant time, stored as digests, never logged.
- 🚦 **Limits everywhere**: rate limits, body size, WebSocket connections, and hard caps on fills, spawns, removals, and search radii.
- 🧾 **An audit log** of every change, with the token’s name.
- ⏱️ **The tick comes first.** Requests never block the server.
- 🧑‍⚖️ **Java stays off** unless you turn it on, and `admin` doesn’t include it.

## Links

- Source code, issues, and the full API reference: [GitHub](https://github.com/MairwunNx/minecraft-worldapi)
- AI agents: [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an MCP server for Claude, Cursor, and others
- From the same author: [Mo’Joins](https://modrinth.com/plugin/mojoins), join messages in each player’s language, and [Mo’Afks](https://modrinth.com/plugin/moafks), a safe AFK mode
