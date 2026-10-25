import java.time.Duration

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
}

val minecraft = "26.3"
val neoforgeVersion = "26.3.0.95"
val sqlite = "3.53.4.0"

base {
    archivesName = "worldapi-neoforge"
}

val modcore = project(":modcore")
val core = project(":core")
val expansion = project(":expansion")
evaluationDependsOn(modcore.path)
evaluationDependsOn(core.path)
evaluationDependsOn(expansion.path)
val modcoreMain: SourceSet = modcore.sourceSets.main.get()
val coreMain: SourceSet = core.sourceSets.main.get()
val expansionMain: SourceSet = expansion.sourceSets.main.get()

// Game tests ask WorldAPI on a real server with real players (`./gradlew :neoforge:runGameTestServer`). They are
// modcore's, shared with Fabric, and run in a dev-only mod of their own, so the release jar doesn't carry them.
val gametest: SourceSet = sourceSets.create("gametest") {
    compileClasspath += sourceSets.main.get().output + modcoreMain.output
    runtimeClasspath += sourceSets.main.get().output + modcoreMain.output
}
kotlin.sourceSets.named("gametest") {
    kotlin.srcDir(modcore.file("src/gametest/kotlin"))
    kotlin.srcDir(modcore.file("src/gametestPlain/kotlin"))
}

neoForge {
    version = neoforgeVersion
    addModdingDependenciesTo(gametest)

    // The mod is this module, modcore, core, and the expansion API together, in dev runs as in the jar: they share
    // packages, which NeoForge's module layers keep to one jar each.
    mods {
        register("worldapi") {
            sourceSet(sourceSets.main.get())
            sourceSet(modcoreMain)
            sourceSet(coreMain)
            sourceSet(expansionMain)
        }
        register("worldapi_gametest") {
            sourceSet(gametest)
        }
    }

    runs {
        register("client") {
            client()
            loadedMods = listOf(mods["worldapi"])
        }
        register("server") {
            server()
            programArgument("--nogui")
            loadedMods = listOf(mods["worldapi"])
        }
        // Runs every test and stops: `./gradlew :neoforge:runGameTestServer`.
        register("gameTestServer") {
            type = "gameTestServer"
            sourceSet = gametest
            gameDirectory = file("run/gametest")
            systemProperty("neoforge.enabledGameTestNamespaces", "worldapi_gametest")
        }
        // Singleplayer worlds in a game window, checked and closed by the test mod: `./gradlew :neoforge:runClientGameTest`.
        register("clientGameTest") {
            client()
            sourceSet = gametest
            gameDirectory = file("run/clientgametest")
            systemProperty("worldapi.clientGameTest", "true")
        }
    }
}

// What goes into the jar besides the mod's own classes: modcore, core, the expansion API, and core's libraries,
// relocated as on Paper. The expansion API stays where it is: other mods compile against it.
val shade: Configuration = configurations.create("shade")

dependencies {
    // The game brings SLF4J and pins its version; Ktor and Exposed log through the game's.
    implementation(project(":core")) { exclude(group = "org.slf4j") }
    // Without its Minecraft: the game comes from NeoForge here.
    compileOnly(project(":modcore")) { isTransitive = false }
    "gametestCompileOnly"(project(":modcore")) { isTransitive = false }
    "gametestImplementation"(project(":core")) { exclude(group = "org.slf4j") }
    implementation("net.kyori:adventure-text-minimessage:5.2.0")
    implementation("net.kyori:adventure-text-serializer-gson:5.2.0") { exclude(group = "com.google.code.gson") }
    shade(project(":core")) {
        exclude(group = "org.slf4j")
        // Java scripts run on Paper only, so the compiler stays out.
        exclude(group = "org.eclipse.jdt")
    }
    shade(project(":modcore")) { isTransitive = false }
    shade("net.kyori:adventure-text-minimessage:5.2.0")
    shade("net.kyori:adventure-text-serializer-gson:5.2.0") { exclude(group = "com.google.code.gson") }
    // The game has no SQLite driver. It loads native code by its own package name, so it goes in whole, as a jar in the
    // jar: NeoForge picks the newest copy when another mod carries one too.
    implementation("org.xerial:sqlite-jdbc:$sqlite")
    jarJar("org.xerial:sqlite-jdbc:$sqlite") { isTransitive = false }
    // The test runs take their class path from the gametest source set, which has the mod's code but not its libraries.
    "gametestRuntimeOnly"("org.xerial:sqlite-jdbc:$sqlite")
    "gametestRuntimeOnly"("net.kyori:adventure-text-minimessage:5.2.0")
    "gametestRuntimeOnly"("net.kyori:adventure-text-serializer-gson:5.2.0") { exclude(group = "com.google.code.gson") }
}

kotlin {
    jvmToolchain(25)
}

// The admin panel, built once by paper's `buildPanel` and served by every platform's jar under /admin. `-PskipPanel`
// leaves it out, as on Paper.
evaluationDependsOn(":paper")
val skipPanel = providers.gradleProperty("skipPanel").isPresent
val panel = project(":paper").tasks.named("buildPanel")

tasks {
    processResources {
        if (!skipPanel) from(panel) { into("admin") }
        // FML wants a version number: a dev build is 0.0.0-dev, CI's 0.0.0-dev-<sha>.
        val version = project.version.toString().let { if (it.startsWith("dev")) "0.0.0-$it" else it }
        val properties = mapOf("version" to version, "neoforge" to neoforgeVersion, "minecraft" to minecraft)
        inputs.properties(properties)
        filesMatching("META-INF/neoforge.mods.toml") { expand(properties) }
        // The mod list shows the store's icon, as Fabric's does.
        from(rootProject.file("media/icon.png")) { rename { "worldapi.png" } }
    }

    // Kotlin and the libraries go inside, relocated, so players need no Kotlin mod and nothing clashes with a mod that
    // ships its own copy. The SQLite driver rides along as NeoForge's jar in jar.
    shadowJar {
        archiveClassifier = ""
        configurations = listOf(shade)
        from(named("jarJar"))
        listOf(
            "kotlin", "kotlinx", "io.ktor", "com.charleskorn.kaml", "it.krzeminski.snakeyaml", "okio", "net.thauvin", "com.typesafe.config",
            "org.jetbrains.exposed", "com.zaxxer.hikari", "org.postgresql", "org.mariadb.jdbc", "net.kyori",
        ).forEach { relocate(it, "com.mairwunnx.worldapi.libs.$it") }
        mergeServiceFiles()
        // kotlinx-coroutines' debugger probes sit outside any package and would clash with another copy on NeoForge's module path.
        exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class", "_COROUTINE/**", "DebugProbesKt.bin")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
        dependencies {
            exclude(dependency("org.jetbrains:annotations"))
            exclude(dependency("org.checkerframework:.*"))
        }
    }

    // The jar without Kotlin wouldn't load; the shaded one is the mod.
    jar {
        archiveClassifier = "slim"
    }

    assemble {
        dependsOn(shadowJar)
    }

    // `./gradlew build` runs the game tests, as on Fabric.
    check {
        dependsOn("runGameTestServer")
    }

    // A game that hangs is killed, and the run fails.
    named("runGameTestServer") {
        timeout = Duration.ofMinutes(10)
    }

    // The singleplayer check writes what it found; anything but `ok` fails the run.
    named("runClientGameTest") {
        timeout = Duration.ofMinutes(8)
        val result = file("run/clientgametest/worldapi-check.txt")
        doFirst { result.delete() }
        doLast {
            val text = if (result.exists()) result.readText().trim() else "the game closed before the check ran"
            if (text != "ok") throw GradleException("The singleplayer check failed:\n$text")
        }
    }
}
