plugins {
    kotlin("jvm")
    id("net.neoforged.moddev")
}

// The game without a loader, as both mods will see it: NeoForm's Minecraft, whose names are the game's own since 26.1.
// Whatever reads or changes the world the same way on Fabric and NeoForge lives here; `fabric` and `neoforge` hand in
// what their loader does differently (see `Loader`). Only a frame so far: the mods don't serve anything yet.
neoForge {
    neoFormVersion = "26.3-1"
}

dependencies {
    // The game brings SLF4J and pins its version; Ktor and Exposed log through the game's.
    implementation(project(":core")) { exclude(group = "org.slf4j") }
}

kotlin {
    jvmToolchain(25)
}
