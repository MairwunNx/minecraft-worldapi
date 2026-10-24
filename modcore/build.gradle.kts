plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.neoforged.moddev")
}

// The game tests both mods run (`src/gametest`): each mod compiles them with its loader's `GameTest` annotation; here
// they compile once with `src/gametestPlain` (the annotation for a loader without its own), so they're checked against
// the game alone.
val gametest: SourceSet = sourceSets.create("gametest") {
    compileClasspath += sourceSets.main.get().output
}
kotlin.sourceSets.named("gametest") {
    kotlin.srcDir("src/gametestPlain/kotlin")
}

// The game without a loader, as both mods see it: NeoForm's Minecraft, whose names are the game's own since 26.1.
// Whatever reads or changes the world the same way on Fabric and NeoForge lives here; `fabric` and `neoforge` hand in
// what their loader does differently (see `Loader`).
neoForge {
    neoFormVersion = "26.3-1"
    addModdingDependenciesTo(gametest)
    // ParityTest loads the routes, whose classes name the game's.
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    // The game brings SLF4J and pins its version; Ktor and Exposed log through the game's.
    api(project(":core")) { exclude(group = "org.slf4j") }
    // MiniMessage in messages, as on Paper, which ships Adventure itself; the mods carry it relocated. The game brings Gson.
    api("net.kyori:adventure-text-minimessage:5.2.0")
    api("net.kyori:adventure-text-serializer-gson:5.2.0") { exclude(group = "com.google.code.gson") }
    // Each mod carries the SQLite driver inside its jar; the game has none.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    // Mixins: Fabric Loader and NeoForge both bring Mixin and MixinExtras at runtime.
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")

    "gametestImplementation"(project(":core")) { exclude(group = "org.slf4j") }

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    // ParityTest holds the mods' endpoints to the document Paper's build writes.
    systemProperty("worldapi.openapi", rootProject.file("docs/openapi.json").absolutePath)
}
