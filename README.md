# WorldAPI

<p align="center">
  <a href="https://modrinth.com/plugin/worldapi"><img alt="Modrinth" src="https://img.shields.io/modrinth/dt/worldapi?logo=modrinth&label=Modrinth&color=00AF5C"></a>
  <a href="https://hangar.papermc.io/MairwunNx/WorldAPI"><img alt="Hangar" src="https://img.shields.io/hangar/dt/WorldAPI?label=Hangar&color=1E88E5"></a>
  <a href="https://github.com/MairwunNx/minecraft-worldapi/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/MairwunNx/minecraft-worldapi?label=release"></a>
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-F7CF0D">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-E76F00">
  <a href="https://github.com/MairwunNx/minecraft-worldapi/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/MairwunNx/minecraft-worldapi/build.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/MairwunNx/minecraft-worldapi"></a>
</p>

🌍 **WorldAPI** puts your Paper server on HTTP. Drop in one jar, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the server.

[Русская версия](https://github.com/MairwunNx/minecraft-worldapi/blob/master/README.RU.md)

> 🎬 Video proof is coming soon.

## Who it’s for

- 🛠️ **Server admins.** See who’s online and what they’re doing, find what lags, follow the console live, change configs, ban, kick, restart with a countdown, from the admin panel, a script, or a chat with an AI. Everyday work no longer needs SSH.
- 🌐 **People who build a site or a bot.** A status page, a player list, leaderboards, player profiles, and a live feed of joins, deaths, and advancements take a few requests and no plugin of your own. Anonymous reads are safe by default, so a public site needs no token at all.
- 🧭 **New players.** With [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an AI assistant answers “where’s the nearest village?”, “how do I craft a shield?”, or “how long until night?” from your own world, not from a wiki.
- 🧑‍💻 **Pros.** Automate the server with events, edit blocks in bulk, manage entities, rank any vanilla statistic, and, if you trust yourself with it, run Java on the server.

## What you get

- 📡 **107 REST operations**: the server and its load, JVM and host metrics, worlds, game rules, the border, blocks, chests and other containers, one search for blocks, biomes, and structures, chunks, entities with a summary of where they crowd, players online and offline with their inventories and deaths, statistics, advancements, play sessions, recipes, leaderboards, and the event history.
- 🗂️ **Configs and logs as files.** Read `server.properties`, Paper’s and every plugin’s config, and old logs a page at a time or by search, and change them with exact edits or by key, with a backup of each previous version.
- 🖥️ **An admin panel** at `/admin`: live load, the console with a command line, chat, players, files, plugins, tokens, accounts, and the audit log.
- ⚡ **A live feed over WebSocket** with 40 topics: joins, deaths, advancements, chat if you turn it on, the console, positions, health, time, weather, boss kills, raids, and more. You get a snapshot on subscribe, then changes.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave.
- 🎒 **Inventories in depth.** Every item comes with its enchantments, attributes, durability, potion effects, armor trim, and what’s inside shulker boxes and bundles.
- 🔌 **Other plugins’ data, too.** Plugins share their own resources and live events through WorldAPI with no library to add, and stay the source of truth for them. See “Extensions” in the reference.
- 🔑 **Permissions and groups** for tokens and panel accounts, made in game, in the panel, or in the config. A site reads without a token; everything else needs the right permission.
- 🗄️ **SQLite out of the box**, or your own PostgreSQL, MySQL, or MariaDB.
- 📖 **Built-in docs** at `/api/v1/docs`, and an OpenAPI 3.1 document for code generators.
- 🤖 **Ready for AI agents** through [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp).
- 📊 **A section in [Plan](https://modrinth.com/plugin/plan)**, if you run it: the API's state on the server page, each player's tokens on theirs.

## Try it live

WorldAPI feeds the site of Moonlands, my hard survival server with friends: the live player list, the event feed, and the hall of fame all come from it.

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

Open [minecraft.mairwunnx.com](https://minecraft.mairwunnx.com), or ask its WorldAPI yourself: `curl https://minecraft.mairwunnx.com/worldapi/api/v1/server`.

## Install

WorldAPI needs Paper 26.3 or a fork of it, like Purpur, on Java 25. Plan is optional.

### With Docker (recommended)

If your server runs in [itzg/minecraft-server](https://docker-minecraft-server.readthedocs.io), it fetches WorldAPI itself. Name it in `MODRINTH_PROJECTS`:

```yaml
services:
  minecraft:
    image: itzg/minecraft-server:java25
    environment:
      EULA: "TRUE"
      TYPE: PAPER
      VERSION: "26.3"
      MODRINTH_PROJECTS: |
        worldapi
    ports: ["25565:25565"]
    volumes: ["./data:/data"]
```

`worldapi:3.0.0` pins a version. To take it from GitHub instead, use `PLUGINS: https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi.jar`. The port 8110 stays inside Docker on purpose: “HTTP exposure” below shows how to publish it safely.

### By hand

1. Download the jar from [Modrinth](https://modrinth.com/plugin/worldapi), [Hangar](https://hangar.papermc.io/MairwunNx/WorldAPI), or [GitHub](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi.jar).
2. Put it in `plugins/` and restart the server.

### First steps

On its first start WorldAPI writes `plugins/WorldAPI/config.yml` with a fresh admin token and listens on port 8110. From the server itself, ask it something:

```console
$ curl -s http://localhost:8110/api/v1/server
{"name":"Paper","brand":"Paper","minecraftVersion":"26.3","onlinePlayers":3,"maxPlayers":20,"tps":20.0,"mspt":6.21,…}
```

Change the world with the admin token from `config.yml`:

```sh
curl -X PATCH http://localhost:8110/api/v1/worlds/world \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"dayTime": 1000, "weather": "clear"}'
```

Then open `http://localhost:8110/api/v1/docs` for every endpoint, and run `/worldapi admin` in game for the admin panel.

## Examples

**Who’s online, for a status page.** No token needed:

```console
$ curl -s https://mc.example.com/worldapi/api/v1/players/online
{"count":2,"max":20,"players":[{"name":"Steve","world":"world","gameMode":"survival","health":20.0,"ping":38,…},…]}
```

**A leaderboard.** Presets like `playtime`, `diamonds`, and `mobs`, or any vanilla statistic:

```sh
curl -s 'https://mc.example.com/worldapi/api/v1/leaderboards/playtime?limit=3'
curl -s 'https://mc.example.com/worldapi/api/v1/leaderboards/killed:pig'
```

**A live feed in the browser.** Subscribe once and get a snapshot, then every change:

```js
const ws = new WebSocket("wss://mc.example.com/worldapi/api/v1/ws");
ws.onopen = () => ws.send(JSON.stringify({ op: "subscribe", topics: ["player.join", "player.death"] }));
ws.onmessage = (message) => {
  const data = JSON.parse(message.data);
  if (data.op === "event") console.log(data.topic, data.event.player?.name, data.event.data);
};
```

**What lags.** One request counts entities per type and names the busiest chunks:

```sh
curl -s -H "Authorization: Bearer $TOKEN" https://mc.example.com/worldapi/api/v1/worlds/world/entities/summary
```

**What the log said before the crash.** Search yesterday’s log, with two lines around each match:

```sh
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://mc.example.com/worldapi/api/v1/files/read?path=logs/2026-10-18-1.log.gz&grep=exception&context=2'
```

**A config change by key**, comments kept, with a backup of the old file:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  https://mc.example.com/worldapi/api/v1/files/set \
  -d '{"path": "server.properties", "values": {"view-distance": 12}}'
```

The [reference](https://github.com/MairwunNx/minecraft-worldapi/blob/master/docs/reference.md) has the rest, and `/api/v1/docs` on your server lists every field.

## Tokens and permissions

Make a token in game: `/worldapi token create <name>` opens a chest menu. Click the groups you want, pick a lifetime (never, 1, 7, 30, or 90 days), and press “Create token”. The token shows up once in your chat as “[Click to copy]”. The admin panel makes tokens too.

| Command | What it does |
|---|---|
| `/worldapi` | Opens a menu with all of the below: state, panel code, tokens, accounts, groups, reload |
| `/worldapi admin [group]` | A code for the admin panel: your first account, or a new password for yours |
| `/worldapi token create <name>` | Opens the token menu |
| `/worldapi token list` | Every token with its permissions and expiry |
| `/worldapi token revoke <name>` | Stops a token at once |
| `/worldapi reload` | Applies `config.yml` without a restart |
| `/worldapi status` | Shows what’s running |

Every command needs `worldapi.admin`, which operators have. Clients send the token as `Authorization: Bearer <token>`.

A token or an account lists permissions and groups. Four groups come ready, and you can change them or add your own in `auth.groups`:

| Group | Gets |
|---|---|
| `viewer` | Everything a site or a curious agent reads: the server, worlds, players with their places and inventories, events, chat |
| `moderator` | `viewer`, plus the console log, player changes, kicks, bans, the whitelist, and operators |
| `admin` | Everything but writing files and Java scripts |
| `owner` | Everything |

| Permission | Grants |
|---|---|
| `server:read` | The server, worlds, blocks, entities, plugins, and performance |
| `players:read` | Players, statistics, advancements, sessions, and leaderboards |
| `players:private` | Coordinates, inventories, ender chests, containers, and death places |
| `events:read` | The event history and the live feed |
| `chat:read` | Chat in the history and the live feed |
| `logs:read` | The console, players’ commands, and files under `logs/` |
| `system:read` | The host: memory, swap, disk, CPU, and container limits |
| `world:write` | Time, weather, rules, the border, blocks, containers, entities, explosions, lightning, sounds, particles |
| `players:write` | Teleports, messages, player state, inventories, effects, advancements |
| `players:moderate` | Kicks, bans, the whitelist, operators, vanished players, and IP addresses |
| `server:console` | Console commands |
| `server:control` | Saving, restarting, stopping, and world seeds |
| `files:read` | Server and plugin configs |
| `files:write` | Changing them. Only `owner` has it unless you add it to a group |
| `tokens:manage` | Tokens, panel accounts, and the audit log |
| `code` | Java scripts. Only `owner` has it |

Nobody hands out more than they have: an `admin` can’t make an `owner` token. Tokens from 2.x keep working as they were: `read` means `viewer`, `write:world` and `write:players` mean `world:write` and `players:write`, and `admin` is the group.

For automated setups, tokens can also live in `config.yml`:

```yaml
auth:
  tokens:
    - name: site
      token: "<64 random hex characters>"
      scopes: [viewer]
    - name: agent
      token: "<another one>"
      scopes: [viewer, "world:write", "players:write"]
      rateLimit: { requestsPerMinute: 600, burst: 100 }
```

## Admin panel

WorldAPI serves a panel at `/admin`, next to the API: `http://localhost:8110/admin/` through an SSH tunnel, or `https://<your-domain>/worldapi/admin/` behind a proxy (see “HTTP exposure”). It shows the load live, streams the console with a command line, follows chat, and lets you look through players, files, and plugins, and manage tokens and accounts. Each account sees only what its groups allow.

1. In game, run `/worldapi admin`. You get a code for 30 minutes, and with `panel.publicUrl` set in `config.yml`, a link that opens the panel with the code filled in. The first account joins the `owner` group, later ones `admin`, or the group you name.
2. Open the panel, choose “I have a code”, and enter it with a password of at least 10 characters. The account takes your player name.
3. Sign in with that name and password from then on. A forgotten password? Run `/worldapi admin` again for a code that sets a new one.

An owner or admin can also invite people from the panel: the invite is a code the person enters with a name and a password. Passwords are stored as salted PBKDF2 hashes, and sessions as SHA-256 digests of the cookie.

## Configure

Everything lives in `plugins/WorldAPI/config.yml`, with a comment on every setting. `/worldapi reload` applies it without a restart. A misspelled key is an error rather than a silent default, and a failed reload keeps the old config and says why.

| Section | What it sets |
|---|---|
| `http` | Host, port, base path, trusted proxies |
| `cors` | Sites that may call the API from a browser |
| `auth` | Tokens, groups, and what visitors without a token may read |
| `rateLimit` | Requests per minute for visitors and for tokens |
| `privacy` | Whether visitors see coordinates, inventories, IP addresses, and chat; all off by default |
| `websocket` | Client limits and the topics open to visitors |
| `events` | Chat recording, off by default, and how long to keep history |
| `database` | SQLite, PostgreSQL, or MySQL and MariaDB |
| `limits` | Caps on page size, fills, spawns, removals, search radii, file reads, backups, and more |
| `panel` | The panel’s public address for links in game, and item icons |
| `scripts` | Java scripts, off by default |

### Database

WorldAPI keeps the event history, play sessions, the audit log, experience totals, and panel accounts in a database. By default that’s SQLite in `plugins/WorldAPI/worldapi.db`, which needs no setup. If you already run PostgreSQL, MySQL, or MariaDB, point the `database` section at it; the drivers ship in the jar.

```yaml
database:
  type: postgresql
  host: db.example.com
  port: 5432
  name: worldapi
  user: worldapi
  password: "secret"
  properties: { sslmode: require }
```

```yaml
database:
  type: mysql          # MySQL and MariaDB alike
  host: db.example.com
  port: 3306
  name: worldapi
  user: worldapi
  password: "secret"
```

Create the database and the user first; WorldAPI creates its tables on start. Give each server its own database. A change here takes a restart, and switching databases starts the new one empty: nothing is copied over.

## Plan

With [Plan](https://modrinth.com/plugin/plan) installed, WorldAPI adds its own section to Plan's pages. The server page shows the version, the number of endpoints, the WebSocket clients connected now, the active tokens, and the events in history. A player's page shows the tokens they made in game, with each one's permissions and expiry; a column in the players table counts them. Token values never reach Plan.

## Security

WorldAPI hands out exactly what you allow and nothing more. Here is what makes that true:

- 🔒 **No write without a token.** Visitors can only read, and only the paths you list. Each token and account can do only what its permissions say, and nobody hands out more than they have.
- 🙈 **Private by default.** Visitors never see coordinates, inventories, IP addresses, or chat unless you turn that on. Block search, which would work as an x-ray, always needs a token.
- 👻 **Vanished stays vanished.** A player hidden by a vanish plugin (SuperVanish, PremiumVanish, Essentials) looks offline to everyone without `players:moderate`: no place in the online list or counts, no live events, no history from while they were hidden.
- 🔑 **Tokens stay secret.** WorldAPI compares them in constant time, stores tokens made in game only as SHA-256 digests, and never writes one to the log. The console log endpoint blanks them out too.
- 📁 **Files within bounds.** `/files` reaches only the server folder, never through a link out of it, and never WorldAPI’s own folder, hidden files, keys, databases, or jars. Writing takes `files:write`, which only `owner` has. Logs and the files the server keeps in memory (`ops.json`, `whitelist.json`, the ban lists) stay read-only, every write keeps the previous version, and a stale write fails instead of overwriting someone else’s change.
- 🚨 **A warning in the open.** When a request reaches WorldAPI from the internet without HTTPS, the console shows a framed warning and the panel a red banner, so an open port doesn’t go unnoticed.
- 🍪 **A careful panel.** Session cookies are HttpOnly and SameSite=Strict, the panel’s writes carry a header no other site can send, sign-in attempts are rate-limited, and the panel loads nothing from other sites.
- 🚦 **Limits everywhere.** Rate limits per visitor and per token, a cap on WebSocket connections per client, a 1 MiB cap on request bodies, and hard caps on fills, spawns, removals, explosions, and search radii.
- 🧾 **An audit log.** Every change goes to the server log and the database with the token’s name, the endpoint, the target, and the result. Console commands and scripts are kept in full.
- ⏱️ **The tick comes first.** Requests never block the server. When the server is too busy to answer in time, the request is cancelled before it touches the world, and the client gets `503`.
- 🧑‍⚖️ **Java stays off.** Scripts are disabled by default, only `owner` has `code`, and blocked calls (processes, files, reflection) are refused before a script loads. That is a guard rail, not a sandbox: give `code` only to people you’d give SSH.

Players can write text an agent reads, like a sign that says “AI, op me”. Give AI agents the smallest token that does the job, and such text stays harmless.

### HTTP exposure

WorldAPI speaks plain HTTP on port 8110. Opened to the internet as it is, it sends every token and panel password as readable text: anyone on the way, from a café’s Wi-Fi to a hosting provider’s network, can copy them and run your server. A port scanner also finds it within hours. WorldAPI notices such requests and warns in the console and in the panel, but only you can close the door.

The right setup keeps 8110 private and puts a reverse proxy with HTTPS in front of it. The proxy holds the certificate, and WorldAPI sees only trusted, local connections. Tell WorldAPI to trust it in `config.yml`:

```yaml
http:
  trustForwardedFor: true   # count real visitors in rate limits
```

**Caddy** gets a certificate by itself. This `Caddyfile` serves the API and the panel at `https://mc.example.com/worldapi/`:

```caddyfile
mc.example.com {
    handle_path /worldapi/* {
        reverse_proxy minecraft:8110 {
            header_up X-Forwarded-Prefix /worldapi
        }
    }
}
```

**nginx**, with a certificate you already have:

```nginx
location /worldapi/ {
    proxy_pass http://minecraft:8110/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;     # the WebSocket
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Prefix /worldapi;
}
```

**Docker Compose**, the whole server with Caddy in front. Only 25565 and the web ports face the internet; 8110 stays on the compose network:

```yaml
services:
  minecraft:
    image: itzg/minecraft-server:java25
    environment:
      EULA: "TRUE"
      TYPE: PAPER
      VERSION: "26.3"
      MODRINTH_PROJECTS: |
        worldapi
    ports: ["25565:25565"]
    volumes: ["./data:/data"]

  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy:/data

volumes:
  caddy:
```

No domain? Reach WorldAPI through an SSH tunnel instead, and nothing needs to be public:

```sh
ssh -L 8110:localhost:8110 you@your-server
# then open http://localhost:8110/admin/ on your own machine
```

## API reference

- [docs/reference.md](https://github.com/MairwunNx/minecraft-worldapi/blob/master/docs/reference.md): every endpoint group, the conventions, leaderboards, the WebSocket protocol and its topics, and Java scripts.
- `/api/v1/docs` on your server, and [docs/openapi.json](https://github.com/MairwunNx/minecraft-worldapi/blob/master/docs/openapi.json) for code generators.

## Use it with AI

[minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp) connects Claude, Cursor, and other AI clients to WorldAPI. Give it a token and ask in plain words: “who played the most this week?”, “why is the server lagging?”, “what did the log say before the crash?”, “set it to morning and tell everyone the event starts in five minutes”.

## Build

You need JDK 25, which Gradle downloads when it’s missing, and [Bun](https://bun.sh) for the admin panel. `./gradlew build -PskipPanel` builds without the panel.

```shell
git clone https://github.com/MairwunNx/minecraft-worldapi
cd minecraft-worldapi
./gradlew build
```

The jar lands in `build/libs/`. Releases come from the “Release” workflow on GitHub, which takes the version as input.

## Stack

- Kotlin and Gradle Kotlin DSL
- Paper API 26.3
- Ktor with the CIO engine for REST and the WebSocket
- kotlinx-serialization with kaml for the config
- Exposed and HikariCP for the database
- The Eclipse compiler for Java scripts
- The admin panel: React, Vite, shadcn/ui, and TanStack, built with Bun

## AI involvement

AI wrote most of the code and the documentation from my task descriptions, and I review what it makes. AI also writes the commit messages from the changes. WorldAPI was built for AI agents to use, so they helped test it too.

## Related projects

[minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp)—🤖 the MCP server that lets AI agents read and run a Minecraft server through WorldAPI.

[Allay Server](https://github.com/allay-ai/server)—🛠️⚡ the backend for Allay, an AI helper for Minecraft, built on OpenRouter.

[Allay](https://github.com/allay-ai/minecraft)—🪽 brings Allay into the game: ask the AI anything with `/ask`, right in the chat. 🎮

## From the same author

[Mo’Joins](https://github.com/MairwunNx/mojoins)—🎉 join and quit messages in each player’s own language, picked by IP.

[Mo’Afks](https://github.com/MairwunNx/moafks)—🛡️ a safe AFK mode: AFK players take no damage and don’t block the night.

## License

MIT, see [LICENSE](https://github.com/MairwunNx/minecraft-worldapi/blob/master/LICENSE).

> 🫡 Made by Pavel Erokhin (Павел Ерохин), aka mairwunnx.
