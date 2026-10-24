pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "worldapi"

// core holds everything that doesn't touch a game platform; paper is the plugin for Paper and its forks. modcore is what
// the mods will share, the game without a loader; fabric and neoforge are the mods, only frames so far. expansion is the
// API other plugins and mods share their data through.
include("expansion", "core", "paper", "modcore", "fabric", "neoforge")
