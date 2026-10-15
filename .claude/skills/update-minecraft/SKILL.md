---
name: update-minecraft
description: Moves this plugin or mod to a new Minecraft version, like Paper 26.3 to 26.4. It updates the platform API, the version in the plugin descriptor and on Modrinth and Hangar, the Java version, the code the new API breaks, and the game facts the project copies, then checks the result on a real server. Use when a new Minecraft, Paper, Spigot, Fabric, or NeoForge version is out and the owner wants the project on it.
argument-hint: "<game version, e.g. 26.4>"
---

# Update to a new Minecraft version

Move this project to Minecraft `$ARGUMENTS`, or to the newest release if no version was given. The same file sits in every plugin and mod of the family; this project's own facts live in its CLAUDE.md.

## 1. Is the version ready?

Ask the sources in `.claude/skills/update-deps/sources.md`:

- The game: `latest.release` in Mojang's manifest, and the Java it needs (`javaVersion.majorVersion` in that version's JSON).
- The platform the project builds on:
  - **Paper:** Fill's newest build for the version and its `channel`.
  - **Spigot:** `<version>-R0.1-SNAPSHOT` in the Spigot repo.
  - **Fabric:** the game version marked `stable` in Fabric Meta, and Fabric API for it on Modrinth.
  - **NeoForge:** a `<version>.x` release.
- The dependencies the project needs on a server, like PlaceholderAPI, on Modrinth for the new version.

A platform with only alpha or beta builds isn't ready. Tell the owner what's missing and ask whether to go ahead on a beta.

## 2. Read what changed

Read the platform's announcement for the version (papermc.io/news, the Spigot forum, the Fabric blog, the NeoForge blog) and the game's changelog on minecraft.net. Note removed and renamed API, new deprecations, and game changes that touch what this project does.

## 3. Change every place the version lives

CLAUDE.md's “Versions” section lists them. Then search for the old version (`git grep -n "26\.3"`) to catch the rest:

- the API dependency: `paper-api` with the newest build of the new version, `spigot-api`, Fabric's `minecraft` and mappings, NeoForge
- the descriptor: `api-version` in `paper-plugin.yml` or `plugin.yml`, `depends` in `fabric.mod.json`, `neoforge.mods.toml`
- the stores: `gameVersions` for Modrinth and `platformVersions` for Hangar in `build.gradle.kts`
- the Java toolchain, when the game needs a newer Java
- tests that spell out version strings
- texts: README.md, README.RU.md, MODRINTH.md, and CLAUDE.md where they name the version

The project targets one game version. Replace the old one; the earlier release stays on the stores for servers that haven't moved yet.

## 4. Make it build and keep it true

Build, fix what the new API broke, and clear new deprecation warnings that point at removals. Then look at what the project copies from the game: items, blocks, mobs, enchantments, formulas, tables, version-specific behavior. Read the new game jar for them instead of guessing (“Studying the game” in `~/c/personal/minecraft/CLAUDE.md` says how).

Dependencies that move with the game, like Fabric API or a PlaceholderAPI built for the new version, move now too. The rest wait for `/update-deps`.

## 5. Check

Run the whole check in CLAUDE.md's “Checking a change” on the **new** version, including the run on a real server. A plugin that loads but logs an error doesn't pass. If something can only be checked in a game client, list it for the owner.

## 6. Changelog, commit, report

Add a “## X.Y.Z” section to CHANGELOG.md with the next minor version, leading with the game version (“Runs on Paper 26.4”) and naming anything that changed for server owners. Commit and push in the project's style: “Move to Paper 26.4”. Release only when the owner asks. Moonlands moves with `/update-minecraft` in `~/c/personal/minecraft`, once every plugin it runs has a release for the version.

Report: the version moved from and to, the API changes that needed code, the game changes that reached the project, the check's result, and what still needs a game client.
