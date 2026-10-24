rootProject.name = "worldapi"

// core holds everything that doesn't touch a game platform; paper is the plugin for Paper and its forks. expansion is the API
// other plugins and mods share their data through.
include("expansion", "core", "paper")
