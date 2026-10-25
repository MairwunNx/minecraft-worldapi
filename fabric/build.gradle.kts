import java.time.Duration

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("net.fabricmc.fabric-loom")
    id("com.gradleup.shadow")
}

val minecraft = "26.3"
val loader = "0.20.1"
val fabricApi = "0.165.0+26.3"

// Once the mods are released, each platform's jar carries its name, as “Platforms” in CLAUDE.md says.
base {
    archivesName = "worldapi-fabric"
}

// Game tests ask WorldAPI on a real server with real players (`./gradlew :fabric:runGameTest`);
// `./gradlew :fabric:runClientGameTest` opens singleplayer worlds in a game window. They live in `src/gametest` as a mod
// of their own, so the release jar doesn't carry them.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "worldapi_gametest"
        enableGameTests = true
        enableClientGameTests = true
        eula = true
    }
}

// The game tests are modcore's, shared with NeoForge; Fabric API's `GameTest` stands in for the annotation they name.
kotlin.sourceSets.named("gametest") {
    kotlin.srcDir(project(":modcore").file("src/gametest/kotlin"))
}
// So is the test mod's expansion, named in its META-INF/services.
sourceSets.named("gametest") {
    resources.srcDir(project(":modcore").file("src/gametest/resources"))
}

// What goes into the jar besides the mod's own classes: modcore, core, the expansion API, and core's libraries,
// relocated as on Paper. The expansion API stays where it is: other mods compile against it.
val shade: Configuration = configurations.create("shade")
val nested: Configuration = configurations.create("nested")
val sqlite = "3.53.4.0"

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    implementation("net.fabricmc:fabric-loader:$loader")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    // The game brings SLF4J and pins its version; Ktor and Exposed log through the game's.
    implementation(project(":core")) { exclude(group = "org.slf4j") }
    // Without its Minecraft: the game comes from Loom here.
    implementation(project(":modcore")) { isTransitive = false }
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
    // jar (see shadowJar): Fabric Loader picks the newest copy when another mod carries one too.
    implementation("org.xerial:sqlite-jdbc:$sqlite")
    nested("org.xerial:sqlite-jdbc:$sqlite") { isTransitive = false }
    // Optional: a permissions mod's answer for /worldapi, asked only when it's loaded.
    compileOnly("me.lucko:fabric-permissions-api:0.7.0")
}

kotlin {
    jvmToolchain(25)
}

// The admin panel, built once by paper's `buildPanel` and served by every platform's jar under /admin. `-PskipPanel`
// leaves it out, as on Paper.
evaluationDependsOn(":paper")
val skipPanel = providers.gradleProperty("skipPanel").isPresent
val panel = project(":paper").tasks.named("buildPanel")

// Fabric Loader loads only mods from a jar in the jar, so the driver gets the descriptor Loom's `include` would give it.
val sqliteMod = tasks.register<Zip>("sqliteMod") {
    val descriptor = layout.buildDirectory.file("sqlite-mod/fabric.mod.json")
    val text = "{\"schemaVersion\":1,\"id\":\"org_xerial_sqlite-jdbc\",\"version\":\"$sqlite\",\"name\":\"sqlite-jdbc\",\"custom\":{\"fabric-loom:generated\":true}}"
    inputs.property("descriptor", text)
    doFirst { descriptor.get().asFile.apply { parentFile.mkdirs(); writeText(text) } }
    from(nested.elements.map { jars -> jars.map { zipTree(it) } })
    from(descriptor)
    archiveFileName = "sqlite-jdbc-$sqlite.jar"
    destinationDirectory = layout.buildDirectory.dir("sqlite-mod")
}

tasks {
    processResources {
        if (!skipPanel) from(panel) { into("admin") }
        val versions = mapOf("version" to project.version.toString(), "minecraft" to minecraft, "loader" to loader, "sqlite" to sqlite)
        inputs.properties(versions)
        filesMatching("fabric.mod.json") {
            expand(versions)
        }
        from(rootProject.file("media/icon.png")) { into("assets/worldapi") }
    }

    // Kotlin and the libraries go inside, relocated, so players need no Kotlin mod and nothing clashes with a mod that
    // ships its own copy.
    shadowJar {
        archiveClassifier = ""
        configurations = listOf(shade)
        from(sqliteMod) { into("META-INF/jars") }
        listOf(
            "kotlin", "kotlinx", "io.ktor", "com.charleskorn.kaml", "it.krzeminski.snakeyaml", "okio", "net.thauvin", "com.typesafe.config",
            "org.jetbrains.exposed", "com.zaxxer.hikari", "org.postgresql", "org.mariadb.jdbc", "net.kyori",
        ).forEach { relocate(it, "com.mairwunnx.worldapi.libs.$it") }
        mergeServiceFiles()
        // kotlinx-coroutines' debugger probes sit outside any package and would clash with another copy.
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

    // A game that hangs is killed, and the run fails.
    matching { it.name == "runGameTest" || it.name == "runClientGameTest" }.configureEach {
        timeout = Duration.ofMinutes(10)
    }
}
