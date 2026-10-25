# WorldAPI

<p align="center">
  <a href="https://modrinth.com/plugin/worldapi"><img alt="Modrinth" src="https://img.shields.io/modrinth/dt/worldapi?logo=modrinth&label=Modrinth&color=00AF5C"></a>
  <a href="https://hangar.papermc.io/MairwunNx/WorldAPI"><img alt="Hangar" src="https://img.shields.io/hangar/dt/WorldAPI?label=Hangar&color=1E88E5"></a>
  <a href="https://www.curseforge.com/projects/1766131"><img alt="CurseForge for Paper" src="https://img.shields.io/curseforge/dt/1766131?logo=curseforge&label=CurseForge%20for%20Paper&color=F16436"></a>
  <a href="https://www.curseforge.com/projects/1766173"><img alt="CurseForge for Fabric &amp; NeoForge" src="https://img.shields.io/curseforge/dt/1766173?logo=curseforge&label=CurseForge%20for%20Fabric%20%26%20NeoForge&color=F16436"></a>
  <a href="https://github.com/MairwunNx/minecraft-worldapi/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/MairwunNx/minecraft-worldapi?label=release"></a>
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-F7CF0D">
  <img alt="Fabric 26.3" src="https://img.shields.io/badge/Fabric-26.3-DBD0B4">
  <img alt="NeoForge 26.3" src="https://img.shields.io/badge/NeoForge-26.3-D7742F">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-E76F00">
  <a href="https://github.com/MairwunNx/minecraft-worldapi/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/MairwunNx/minecraft-worldapi/build.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/MairwunNx/minecraft-worldapi"></a>
</p>

🌍 **WorldAPI** puts your Minecraft server on HTTP, or the world you play alone. Drop one jar into Paper, Fabric, or NeoForge, and sites, bots, and AI agents can read the world, the players, and their statistics, follow what happens live, and, with a token, run the game.

[Русская версия](https://github.com/MairwunNx/minecraft-worldapi/blob/master/README.RU.md)

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

WorldAPI feeds the site of Moonlands, my hard survival server with friends: the live player list, the event feed, and the hall of fame. Ask it yourself: `curl https://minecraft.mairwunnx.com/worldapi/api/v1/server`.

## What you get

- 📡 **117 REST operations** for the server and its load, its commands, worlds, blocks, chests, entities, players online and offline with their inventories, statistics, advancements, recipes, leaderboards, and the event history.
- ⚡ **A live feed** over WebSocket with 40 topics: joins, deaths, advancements, the console, positions, weather, boss kills, raids, and more.
- 🖥️ **An admin panel** in the browser: live load, the console, chat, players, worlds, files, plugins, tokens, and the audit log. Everyday work no longer needs SSH.
- 🏆 **Live statistics.** Leaderboards move while people play, not at the next autosave, and any vanilla statistic can be one.
- 🗂️ **Configs and logs as files**, read by page or by search and changed by key, with a backup of every previous version.
- 🔑 **Safe by default.** A public site reads without a token and never sees coordinates, inventories, or chat; everything else takes a token with the right permissions.
- 🔌 **Other plugins' data, too.** Plugins share their own resources and live events through WorldAPI, with a small Java library from Maven Central or with none at all.
- 🎮 **Singleplayer too.** Put the mod in your game, and an AI agent answers questions about the world you're playing right now: where the nearest village is, what's in your chests, what you're looking at.
- 🤖 **Ready for AI agents** through [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp), and for Grafana through [minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter).

## Get started

1. Download the jar for your platform from [Modrinth](https://modrinth.com/plugin/worldapi), [Hangar](https://hangar.papermc.io/MairwunNx/WorldAPI) (Paper), or GitHub ([Paper](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-paper.jar), [Fabric](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-fabric.jar), [NeoForge](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-neoforge.jar)). Put it in `plugins/` of a Paper 26.3 server or in `mods/` of a Fabric or NeoForge one, and restart. Playing alone? [Singleplayer](https://github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer) connects an AI agent in two minutes.
2. Ask it something: `curl http://localhost:8110/api/v1/server`. Run `/worldapi admin` in game for the admin panel.
3. Before you open port 8110 to the internet, follow [First steps](https://github.com/MairwunNx/minecraft-worldapi/wiki/First-steps) and put it [behind HTTPS](https://github.com/MairwunNx/minecraft-worldapi/wiki/Reverse-proxy).

## Documentation

The [wiki](https://github.com/MairwunNx/minecraft-worldapi/wiki) has everything, step by step.

**For server owners:** [Installation](https://github.com/MairwunNx/minecraft-worldapi/wiki/Installation) · [Singleplayer](https://github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer) · [First steps](https://github.com/MairwunNx/minecraft-worldapi/wiki/First-steps) · [Tokens and permissions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Tokens-and-permissions) · [Admin panel](https://github.com/MairwunNx/minecraft-worldapi/wiki/Admin-panel) · [Configuration](https://github.com/MairwunNx/minecraft-worldapi/wiki/Configuration) · [Reverse proxy](https://github.com/MairwunNx/minecraft-worldapi/wiki/Reverse-proxy) · [Security](https://github.com/MairwunNx/minecraft-worldapi/wiki/Security) · [Monitoring](https://github.com/MairwunNx/minecraft-worldapi/wiki/Monitoring) · [MCP](https://github.com/MairwunNx/minecraft-worldapi/wiki/MCP) · [FAQ](https://github.com/MairwunNx/minecraft-worldapi/wiki/FAQ)

**For developers:** [REST API](https://github.com/MairwunNx/minecraft-worldapi/wiki/REST-API) · [WebSocket](https://github.com/MairwunNx/minecraft-worldapi/wiki/WebSocket) · [Extensions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Extensions) · [Expansions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Expansions) · [Java scripts](https://github.com/MairwunNx/minecraft-worldapi/wiki/Java-scripts) · [Building from source](https://github.com/MairwunNx/minecraft-worldapi/wiki/Building-from-source) · [Architecture](https://github.com/MairwunNx/minecraft-worldapi/wiki/Architecture) · [Development](https://github.com/MairwunNx/minecraft-worldapi/wiki/Development) · [Releasing](https://github.com/MairwunNx/minecraft-worldapi/wiki/Releasing)

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

> 🫡 Made by Pavel Erokhin (Павел Ерохин), aka mairwunnx.
