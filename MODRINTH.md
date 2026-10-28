# WorldAPI

🌍 **WorldAPI** puts your Minecraft server on HTTP, or the world you play alone. Drop one jar into Paper, Fabric, or NeoForge, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the game.

## What you get

- 📡 **129 REST operations** for the server and its load, its commands, worlds, blocks one by one or a whole area at once, chests, entities, players online and offline with their inventories, statistics, advancements, recipes, leaderboards, the game's registries, and the event history.
- ⚡ **A live feed** over WebSocket with 40 topics: joins, deaths, advancements, the console, positions, weather, boss kills, raids, and more.
- 📣 **Webhooks to your chat.** Joins, deaths, advancements, raids, and restarts go to Discord, Telegram, Slack, Microsoft Teams, Google Chat, or any URL, in English, Russian, Spanish, Portuguese, or German. Set one up in game with a few clicks in chat, or in the admin panel.
- 🤖 **An AI agent in one click.** Make an MCP connection in game or in the panel, and copy a ready setup for Claude Code, Codex, Cursor, or OpenCode, for every project on your machine or just one. The agent works through [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp).
- 🖥️ **An admin panel** in the browser: live load, the console, chat, players, worlds, files, plugins, tokens, webhooks, and the audit log. Everyday work no longer needs SSH.
- ↩️ **Undo for the API.** When an agent or a script sets blocks, fills an area, or changes a chest through the API, one call puts it back, until the server stops.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave, and any vanilla statistic can be one.
- 🗂️ **Configs and logs as files**, read by page or by search and changed by key, with a backup of every previous version.
- 🔑 **Safe by default.** A public site reads without a token and never sees coordinates, inventories, or chat; everything else takes a token with the right permissions.
- 🔌 **Other plugins' data, too.** Plugins share their own resources and live events through WorldAPI, with a small Java library from Maven Central or with none at all.
- 🎮 **Singleplayer too.** Put the mod in your game, and an AI agent answers questions about the world you're playing right now: where the nearest village is, what's in your chests, what you're looking at.
- 📊 **Grafana dashboards** through [minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter).

## Get started

1. Put the jar in `plugins/` of a Paper 26.3 server (or a fork of it), or in `mods/` of a Fabric (with Fabric API) or NeoForge server or game, on Java 25, and restart. With [itzg/minecraft-server](https://docker-minecraft-server.readthedocs.io), add `worldapi` to `MODRINTH_PROJECTS` instead. Playing alone? The wiki's [Singleplayer](https://github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer) page connects an AI agent in two minutes.
2. Ask it something: `curl http://localhost:8110/api/v1/server`. Run `/worldapi admin` in game for the admin panel.
3. Before you open port 8110 to the internet, put it [behind HTTPS](https://github.com/MairwunNx/minecraft-worldapi/wiki/Reverse-proxy): plain HTTP sends tokens and passwords in the open.

## Documentation

Installation, tokens, configuration, the whole API, and more are in the [wiki on GitHub](https://github.com/MairwunNx/minecraft-worldapi/wiki).

## Related projects

- **[Moonlands](https://minecraft.mairwunnx.com)** – the hard-survival server where all of these run.
- **[Minecraft MCP](https://github.com/MairwunNx/minecraft-mcp)** – lets an AI client read and run a server through WorldAPI.
- **[Allay](https://github.com/allay-ai/minecraft)** – an AI helper in the game: ask with `/ask`, right in the chat.
- **[Allay Server](https://github.com/allay-ai/server)** – the backend behind Allay.
- **[Mo’Joins](https://github.com/MairwunNx/mojoins)** – join and quit messages in each player's language.
- **[Mo’Afks](https://github.com/MairwunNx/moafks)** – a safe AFK mode that doesn't block the night.
- **[Mo’Bosses](https://github.com/MairwunNx/mobosses)** – monsters that spawn as bosses with classes, abilities, and loot.
- **[minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter)** – Prometheus metrics from WorldAPI.
- **[minecraft-grafana](https://github.com/MairwunNx/minecraft-grafana)** – the Grafana dashboard for WorldAPI.
