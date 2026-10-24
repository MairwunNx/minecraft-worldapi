import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

plugins {
    kotlin("jvm")
    id("net.neoforged.moddev")
}

val minecraft = "26.3"
val neoforgeVersion = "26.3.0.95"

val expansion = project(":expansion")
val core = project(":core")
val modcore = project(":modcore")
evaluationDependsOn(expansion.path)
evaluationDependsOn(core.path)
evaluationDependsOn(modcore.path)
val expansionMain: SourceSet = expansion.sourceSets.main.get()
val coreMain: SourceSet = core.sourceSets.main.get()
val modcoreMain: SourceSet = modcore.sourceSets.main.get()

neoForge {
    version = neoforgeVersion

    // The mod is this module, modcore, core, and the expansion API together, in dev runs as in the jar.
    mods {
        register("worldapi") {
            sourceSet(sourceSets.main.get())
            sourceSet(modcoreMain)
            sourceSet(coreMain)
            sourceSet(expansionMain)
        }
    }

    runs {
        register("server") {
            server()
            programArgument("--nogui")
            loadedMods = listOf(mods["worldapi"])
        }
    }
}

dependencies {
    // core's and modcore's classes go into the jar itself (see tasks.jar), so they aren't libraries at runtime.
    compileOnly(project(":core")) { exclude(group = "org.slf4j") }
    // Without its Minecraft: the game comes from NeoForge here.
    compileOnly(project(":modcore")) { isTransitive = false }
    // Kotlin ships inside the jar, so players need no Kotlin mod; NeoForge picks the newest copy if another mod has one.
    jarJar("org.jetbrains.kotlin:kotlin-stdlib:${getKotlinPluginVersion()}") { isTransitive = false }
}

kotlin {
    jvmToolchain(25)
}

tasks.processResources {
    // FML wants a version number; a dev build is 0.0.0-dev.
    val version = project.version.toString().let { if (it == "dev") "0.0.0-dev" else it }
    val properties = mapOf("version" to version, "neoforge" to neoforgeVersion, "minecraft" to minecraft)
    inputs.properties(properties)
    filesMatching("META-INF/neoforge.mods.toml") { expand(properties) }
    // The mod list shows the store's icon, as Fabric's does.
    from(rootProject.file("media/icon.png")) { rename { "worldapi.png" } }
}

// Once the mod serves the API, its libraries (Ktor, Exposed, the drivers) get shaded and relocated under
// com.mairwunnx.worldapi.libs, as in the plugin.
tasks.jar {
    archiveBaseName = "worldapi-neoforge"
    from(modcoreMain.output, coreMain.output, expansionMain.output)
}
