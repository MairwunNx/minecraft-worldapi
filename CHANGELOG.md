# Changelog

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
