# Changelog

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
