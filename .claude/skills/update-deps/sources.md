# Where fresh versions come from

The same file sits in every project of MairwunNx's Minecraft family; change all copies together. Ask the source, never your memory: versions move every week. Take the newest **stable** version. Skip anything marked alpha, beta, rc, pre, M1, snapshot, nightly, or dev, unless the project already uses that channel (Spigot's API is published only as `-R0.1-SNAPSHOT`, and that is its release).

## Game and platforms

| What | Where to ask | Notes |
|---|---|---|
| Minecraft releases | `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` | `latest.release`; each version's own JSON has `javaVersion.majorVersion`, the Java it needs |
| Paper server and its builds | `https://fill.papermc.io/v3/projects/paper` (versions), `…/versions/<game>/builds/latest` (newest build, with `channel` and the jar's sha256) | A build's `channel` is `ALPHA`, `BETA`, or `STABLE`. A new game version starts on alpha and beta builds |
| Paper API (`io.papermc.paper:paper-api`) | `https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml` | Versions read `<game>.build.<n>-<channel>`, like `26.3.build.190-stable`. The file's `<latest>` lies; pick the highest `<n>` for your game version from `<versions>`, or take `<n>` from Fill |
| Spigot API (`org.spigotmc:spigot-api`) | `https://hub.spigotmc.org/nexus/content/repositories/snapshots/org/spigotmc/spigot-api/maven-metadata.xml` | One version per game version: `<game>-R0.1-SNAPSHOT` |
| Velocity | `https://fill.papermc.io/v3/projects/velocity` | Same API as Paper's |
| Fabric game versions, loader | `https://meta.fabricmc.net/v2/versions/game`, `https://meta.fabricmc.net/v2/versions/loader` | Both flag `stable` |
| Fabric API, other mods and plugins | `https://api.modrinth.com/v2/project/<slug>/version?game_versions=["<game>"]&loaders=["fabric"]` | Use `loaders=["paper"]` for Paper plugins. Newest first; `version_type` is `release`, `beta`, or `alpha` |
| Fabric Loom | `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml` | |
| NeoForge | `https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge` | Versions follow the game: `26.3.0.83` is for 26.3. `…/api/maven/latest/version/releases/net/neoforged/neoforge?filter=26.3` gives the newest for one game version |
| NeoForge ModDevGradle | `https://maven.neoforged.net/api/maven/latest/version/releases/net/neoforged/moddev-gradle` | |
| Hangar | `https://hangar.papermc.io/api/v1/projects/<owner>/<slug>/versions?limit=5` | Plugins that aren't on Modrinth |

## Build tools and libraries

| What | Where to ask | Notes |
|---|---|---|
| Gradle | `https://services.gradle.org/versions/current` | Update with `./gradlew wrapper --gradle-version <v>`, then run it once more so the wrapper jar and scripts update too |
| Gradle plugins | `https://plugins.gradle.org/m2/<id with / for .>/<id>.gradle.plugin/maven-metadata.xml` | Like `com/gradleup/shadow/com.gradleup.shadow.gradle.plugin` |
| Maven Central libraries (Kotlin, kotlinx, OkHttp…) | `https://repo1.maven.org/maven2/<group with / for .>/<artifact>/maven-metadata.xml` | `<release>` may be a beta: pick the newest stable from `<versions>`. Kotlin's Gradle plugin, stdlib, and serialization plugin share one version |
| Other Maven repos | the same `maven-metadata.xml` path under the repo's URL in `build.gradle.kts`, like `https://repo.extendedclip.com/releases/me/clip/placeholderapi/` | |
| Java | `https://api.adoptium.net/v3/info/available_releases` | Stay on the newest LTS the game needs unless the project says otherwise |
| npm packages | `npm view <package> version`, `npm outdated` | `npm install <package>@latest` for a major; keep `package-lock.json` in step |
| Bun packages and Bun | `bun outdated`, `bun update --latest`; Bun itself: `gh api repos/oven-sh/bun/releases/latest -q .tag_name` | Bun's Docker image is `oven/bun:<version>-alpine` |
| Docker Hub images | `https://hub.docker.com/v2/repositories/<namespace>/<name>/tags?page_size=100&ordering=last_updated` | `library/` for official images like `nginx` and `mongo`. Keep the tag's shape (`-alpine`, `-java25`) |
| GHCR images | `gh api /orgs/<org>/packages/container/<name>/versions`, or the project's GitHub releases | |
| GitHub Actions | `gh api repos/<owner>/<action>/releases/latest -q .tag_name` | Pin the major tag (`actions/checkout@v7`) as the workflows already do |
