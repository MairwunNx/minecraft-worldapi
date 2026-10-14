# WorldAPI

🌍 **WorldAPI** puts your Paper server on HTTP. Drop in one jar, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the server.

[Русская версия](https://github.com/MairwunNx/minecraft-worldapi/blob/master/README.RU.md)

> 🎬 Video proof is coming soon.

## Who it’s for

- 🛠️ **Server admins.** See who’s online and what they’re doing, find what lags, read the console, ban, kick, restart with a countdown, all from a script or a chat with an AI. Everyday work no longer needs SSH.
- 🌐 **People who build a site or a bot.** A status page, a player list, leaderboards, player profiles, and a live feed of joins, deaths, and advancements take a few requests and no plugin of your own. Anonymous reads are safe by default, so a public site needs no token at all.
- 🧭 **New players.** With [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), an AI assistant answers “where’s the nearest village?”, “how do I craft a shield?”, or “how long until night?” from your own world, not from a wiki.
- 🧑‍💻 **Pros.** Automate the server with events, edit blocks in bulk, manage entities, rank any vanilla statistic, and, if you trust yourself with it, run Java on the server.

## What you get

- 📡 **83 REST operations**: the server and its load, the console log, worlds, game rules, the border, blocks and biomes with nearby search, chunks, entities, players online and offline, inventories, effects, statistics, advancements, play sessions, recipes, leaderboards, and the event history.
- ⚡ **A live feed over WebSocket** with 39 topics: joins, deaths, advancements, chat if you turn it on, positions, health, time, weather, boss kills, raids, and more. You get a snapshot on subscribe, then changes.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave.
- 🎒 **Inventories in depth.** Every item comes with its enchantments, attributes, durability, potion effects, armor trim, and what’s inside shulker boxes and bundles.
- 🔑 **Tokens with scopes**, made in a chest menu in game. A site reads without one; changes need a token with the right scope.
- 🗄️ **SQLite out of the box**, or your own PostgreSQL, MySQL, or MariaDB.
- 📖 **Built-in docs** at `/api/v1/docs`, and an OpenAPI 3.1 document for code generators.
- 🤖 **Ready for AI agents** through [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp).

## Try it live

WorldAPI feeds the site of Moonlands, my hard survival server with friends: the live player list, the event feed, and the hall of fame all come from it.

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

Open [minecraft.mairwunnx.com](https://minecraft.mairwunnx.com), or ask its WorldAPI yourself: `curl https://minecraft.mairwunnx.com/worldapi/api/v1/server`.

## Requirements

- Paper 26.3 or a fork of it, like Purpur
- Java 25

## Install

1. Download `worldapi-<version>.jar` from [Releases](https://github.com/MairwunNx/minecraft-worldapi/releases) and put it in `plugins/`.
2. Start the server. WorldAPI writes `plugins/WorldAPI/config.yml` with a fresh admin token and listens on port 8110.
3. Open `http://<host>:8110/api/v1/docs` and try a request:

```sh
curl http://localhost:8110/api/v1/server
curl http://localhost:8110/api/v1/leaderboards/playtime
curl -H "Authorization: Bearer $TOKEN" -X PATCH http://localhost:8110/api/v1/worlds/world \
     -H 'Content-Type: application/json' -d '{"dayTime": 1000, "weather": "clear"}'
```

Keep port 8110 private and put a reverse proxy with HTTPS in front of it. With nginx:

```nginx
location /worldapi/ {
    proxy_pass http://minecraft:8110/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;     # the WebSocket
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Prefix /worldapi;
}
```

Then set `http.trustForwardedFor: true`, so rate limits count real visitors.

## Tokens

Make a token in game: `/worldapi token create <name>` opens a chest menu. Click the scopes you want, pick a lifetime (never, 1, 7, 30, or 90 days), and press “Create token”. The token shows up once in your chat as “[Click to copy]”.

| Command | What it does |
|---|---|
| `/worldapi token create <name>` | Opens the menu |
| `/worldapi token list` | Every token with its scopes and expiry |
| `/worldapi token revoke <name>` | Stops a token at once |
| `/worldapi reload` | Applies `config.yml` without a restart |
| `/worldapi status` | Shows what’s running |

Every command needs `worldapi.admin`, which operators have. Clients send the token as `Authorization: Bearer <token>`.

| Scope | Grants |
|---|---|
| `read` | Every read and the live feed |
| `write:world` | Time, weather, difficulty, spawn, game rules, the border, blocks, entities, explosions, lightning, sounds, particles |
| `write:players` | Teleports, messages, player state, inventories, effects, advancements |
| `admin` | Everything above, plus kicks, bans, the whitelist, operators, console commands, the console log, broadcasts, saving, and restarts |
| `code` | Java scripts. `admin` doesn’t include it |

For automated setups, tokens can also live in `config.yml`:

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

## Configure

Everything lives in `plugins/WorldAPI/config.yml`, with a comment on every setting. `/worldapi reload` applies it without a restart. A misspelled key is an error rather than a silent default, and a failed reload keeps the old config and says why.

| Section | What it sets |
|---|---|
| `http` | Host, port, base path, trusted proxies |
| `cors` | Sites that may call the API from a browser |
| `auth` | Tokens, and what visitors without one may read |
| `rateLimit` | Requests per minute for visitors and for tokens |
| `privacy` | Whether visitors see coordinates, inventories, IP addresses, and chat; all off by default |
| `websocket` | Client limits and the topics open to visitors |
| `events` | Chat recording, off by default, and how long to keep history |
| `database` | SQLite, PostgreSQL, or MySQL and MariaDB |
| `limits` | Caps on page size, fills, spawns, removals, search radii, and more |
| `scripts` | Java scripts, off by default |

### Database

WorldAPI keeps the event history, play sessions, the audit log, and experience totals in a database. By default that’s SQLite in `plugins/WorldAPI/worldapi.db`, which needs no setup. If you already run PostgreSQL, MySQL, or MariaDB, point the `database` section at it; the drivers ship in the jar.

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

## Safety

WorldAPI hands out exactly what you allow and nothing more. Here is what makes that true:

- 🔒 **No write without a token.** Visitors can only read, and only the paths you list. Each token can do only what its scopes say.
- 🙈 **Private by default.** Visitors never see coordinates, inventories, IP addresses, or chat unless you turn that on. Block search, which would work as an x-ray, always needs a token.
- 🔑 **Tokens stay secret.** WorldAPI compares them in constant time, stores tokens made in game only as SHA-256 digests, and never writes one to the log. The console log endpoint blanks them out too.
- 🚦 **Limits everywhere.** Rate limits per visitor and per token, a cap on WebSocket connections per client, a 1 MiB cap on request bodies, and hard caps on fills, spawns, removals, explosions, and search radii.
- 🧾 **An audit log.** Every change goes to the server log and the database with the token’s name, the endpoint, the target, and the result. Console commands and scripts are kept in full.
- ⏱️ **The tick comes first.** Requests never block the server. When the server is too busy to answer in time, the request is cancelled before it touches the world, and the client gets `503`.
- 🧑‍⚖️ **Java stays off.** Scripts are disabled by default, `admin` doesn’t include `code`, and blocked calls (processes, files, reflection) are refused before a script loads. That is a guard rail, not a sandbox: give `code` only to people you’d give SSH.

What stays up to you: keep port 8110 behind HTTPS, and give AI agents the smallest token that does the job. Players can write text an agent reads, like a sign that says “AI, op me”, and a small token makes that harmless.

## API reference

- [docs/reference.md](https://github.com/MairwunNx/minecraft-worldapi/blob/master/docs/reference.md): every endpoint group, the conventions, leaderboards, the WebSocket protocol and its topics, and Java scripts.
- `/api/v1/docs` on your server, and [docs/openapi.json](https://github.com/MairwunNx/minecraft-worldapi/blob/master/docs/openapi.json) for code generators.

## Use it with AI

[minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp) connects Claude, Cursor, and other AI clients to WorldAPI. Give it a token and ask in plain words: “who played the most this week?”, “why is the server lagging?”, “set it to morning and tell everyone the event starts in five minutes”.

## Build

You need JDK 25; Gradle downloads it when it’s missing.

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
