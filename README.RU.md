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

🌍 **WorldAPI** выводит в HTTP ваш сервер Minecraft или мир, в котором вы играете в одиночку. Положите один jar в Paper, Fabric или NeoForge, и сайты, боты и ИИ-агенты смогут читать мир, игроков и их статистику, следить за событиями в реальном времени, а с токеном — управлять игрой.

[English version](https://github.com/MairwunNx/minecraft-worldapi/blob/master/README.md)

[![Играйте на Moonlands: чистая ваниль, сложные мобы, ИИ, приваты. ru.minecraft.mairwunnx.com](https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/og-adv-image.ru.png)](https://minecraft.mairwunnx.com)

Живой список игроков, лента событий и зал славы на сайте Moonlands — моего хардкорного сервера с друзьями — приходят из WorldAPI. Спросите его сами: `curl https://minecraft.mairwunnx.com/worldapi/api/v1/server`.

## Что внутри

- 📡 **129 REST-операций**: сервер и его нагрузка, его команды, миры, блоки по одному или целой областью сразу, сундуки, сущности, игроки онлайн и офлайн с инвентарями, статистика, достижения, рецепты, рейтинги, реестры игры и история событий.
- ⚡ **Живая лента** по WebSocket, 40 тем: входы, смерти, достижения, консоль, позиции, погода, убийства боссов, рейды и не только.
- 📣 **Вебхуки в ваш чат.** Входы, смерти, достижения, рейды и перезапуски уходят в Discord, Telegram, Slack, Microsoft Teams, Google Chat или на любой URL — на русском, английском, испанском, португальском или немецком. Подключите вебхук в игре за пару кликов в чате или в админ-панели.
- 🤖 **ИИ-агент в один клик.** Создайте MCP-подключение в игре или в панели и скопируйте готовую настройку для Claude Code, Codex, Cursor или OpenCode — для всех проектов на компьютере или только для одного. Агент работает через [minecraft-mcp](https://github.com/MairwunNx/minecraft-mcp).
- 🖥️ **Админ-панель** в браузере: нагрузка в реальном времени, консоль, чат, игроки, миры, файлы, плагины, токены, вебхуки и журнал аудита. Для повседневных дел SSH больше не нужен.
- ↩️ **Отмена изменений через API.** Если агент или скрипт поставил блоки, залил область или поменял сундук через API, один вызов вернёт всё как было, пока сервер не остановится.
- 🏆 **Живая статистика.** Рейтинги меняются, пока люди играют, а не при следующем автосохранении, и рейтингом может стать любая ванильная статистика.
- 🗂️ **Конфиги и логи как файлы**: чтение постранично или поиском, правка по ключу и бэкап каждой прежней версии.
- 🔑 **Безопасно по умолчанию.** Публичный сайт читает без токена и не видит ни координат, ни инвентарей, ни чата. Для остального нужен токен с подходящими правами.
- 🔌 **Данные других плагинов.** Плагины отдают через WorldAPI свои ресурсы и живые события: через маленькую Java-библиотеку с Maven Central или вовсе без неё.
- 🎮 **И в одиночной игре.** Поставьте мод в игру, и ИИ-агент ответит на вопросы о мире, в котором вы играете прямо сейчас: где ближайшая деревня, что лежит в сундуках, на что вы смотрите.
- 📊 **Дашборды в Grafana** через [minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter).

## Админ-панель

<p align="center">
  <a href="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/overview.png"><img alt="Обзор: графики TPS, времени тика, памяти и CPU и нагрузка миров" src="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/overview.png" width="49%"></a>
  <a href="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/webhooks-dark.png"><img alt="Вебхуки в Discord, Telegram, Slack, Microsoft Teams, Google Chat и JSON в тёмной теме" src="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/webhooks-dark.png" width="49%"></a>
  <a href="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/mcp-setup.png"><img alt="Новое MCP-подключение с готовой настройкой Cursor для одного проекта" src="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/mcp-setup.png" width="49%"></a>
  <a href="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/console-dark.png"><img alt="Живая консоль в тёмной теме" src="https://raw.githubusercontent.com/MairwunNx/minecraft-worldapi/master/media/panel/console-dark.png" width="49%"></a>
</p>

## Как начать

1. Скачайте jar для своей платформы с [Modrinth](https://modrinth.com/plugin/worldapi), [Hangar](https://hangar.papermc.io/MairwunNx/WorldAPI) (Paper) или GitHub ([Paper](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-paper.jar), [Fabric](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-fabric.jar), [NeoForge](https://github.com/MairwunNx/minecraft-worldapi/releases/latest/download/worldapi-neoforge.jar)). Положите его в `plugins/` сервера на Paper 26.3 или в `mods/` сервера на Fabric или NeoForge и перезапустите. Играете один? Страница [Singleplayer](https://github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer) подключит ИИ-агента за две минуты.
2. Спросите что-нибудь: `curl http://localhost:8110/api/v1/server`. Чтобы войти в админ-панель, выполните в игре `/worldapi admin`.
3. Прежде чем открывать порт 8110 в интернет, пройдите [первые шаги](https://github.com/MairwunNx/minecraft-worldapi/wiki/First-steps) и поставьте [HTTPS](https://github.com/MairwunNx/minecraft-worldapi/wiki/Reverse-proxy).

## Документация

Всё по шагам — в [вики](https://github.com/MairwunNx/minecraft-worldapi/wiki), на английском.

**Владельцам серверов:** [Installation](https://github.com/MairwunNx/minecraft-worldapi/wiki/Installation) · [Singleplayer](https://github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer) · [First steps](https://github.com/MairwunNx/minecraft-worldapi/wiki/First-steps) · [Tokens and permissions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Tokens-and-permissions) · [Admin panel](https://github.com/MairwunNx/minecraft-worldapi/wiki/Admin-panel) · [Webhooks](https://github.com/MairwunNx/minecraft-worldapi/wiki/Webhooks) · [Configuration](https://github.com/MairwunNx/minecraft-worldapi/wiki/Configuration) · [Reverse proxy](https://github.com/MairwunNx/minecraft-worldapi/wiki/Reverse-proxy) · [Security](https://github.com/MairwunNx/minecraft-worldapi/wiki/Security) · [Monitoring](https://github.com/MairwunNx/minecraft-worldapi/wiki/Monitoring) · [MCP](https://github.com/MairwunNx/minecraft-worldapi/wiki/MCP) · [FAQ](https://github.com/MairwunNx/minecraft-worldapi/wiki/FAQ)

**Разработчикам:** [REST API](https://github.com/MairwunNx/minecraft-worldapi/wiki/REST-API) · [WebSocket](https://github.com/MairwunNx/minecraft-worldapi/wiki/WebSocket) · [Extensions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Extensions) · [Expansions](https://github.com/MairwunNx/minecraft-worldapi/wiki/Expansions) · [Java scripts](https://github.com/MairwunNx/minecraft-worldapi/wiki/Java-scripts) · [Building from source](https://github.com/MairwunNx/minecraft-worldapi/wiki/Building-from-source) · [Architecture](https://github.com/MairwunNx/minecraft-worldapi/wiki/Architecture) · [Development](https://github.com/MairwunNx/minecraft-worldapi/wiki/Development) · [Releasing](https://github.com/MairwunNx/minecraft-worldapi/wiki/Releasing)

## Связанные проекты

- **[Moonlands](https://minecraft.mairwunnx.com)** — хардкорный сервер, где всё это работает.
- **[Minecraft MCP](https://github.com/MairwunNx/minecraft-mcp)** — даёт ИИ-клиенту читать сервер и управлять им через WorldAPI.
- **[Allay](https://github.com/allay-ai/minecraft)** — ИИ-помощник в игре: спросите через `/ask` прямо в чате.
- **[Allay Server](https://github.com/allay-ai/server)** — бэкенд Allay.
- **[Mo’Joins](https://github.com/MairwunNx/mojoins)** — сообщения о входе и выходе на языке каждого игрока.
- **[Mo’Afks](https://github.com/MairwunNx/moafks)** — безопасный AFK-режим, который не мешает пропустить ночь.
- **[Mo’Bosses](https://github.com/MairwunNx/mobosses)** — монстры, которые появляются боссами с классами, умениями и добычей.
- **[minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter)** — метрики Prometheus из WorldAPI.
- **[minecraft-grafana](https://github.com/MairwunNx/minecraft-grafana)** — дашборд Grafana для WorldAPI.

> 🫡 Сделал Павел Ерохин (Pavel Erokhin), он же mairwunnx.
