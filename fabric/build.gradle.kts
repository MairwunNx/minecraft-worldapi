plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
}

val minecraft = "26.3"
val loader = "0.20.1"
val fabricApi = "0.165.0+26.3"
val fabricKotlin = "1.14.2+kotlin.2.4.21"

base {
    archivesName = "worldapi-fabric"
}

val expansion = project(":expansion")
val core = project(":core")
val modcore = project(":modcore")
evaluationDependsOn(modcore.path)

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    implementation("net.fabricmc:fabric-loader:$loader")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlin")
    // The game brings SLF4J and pins its version; Ktor and Exposed log through the game's.
    implementation(project(":core")) { exclude(group = "org.slf4j") }
    // Without its Minecraft: the game comes from Loom here.
    implementation(project(":modcore")) { isTransitive = false }
}

kotlin {
    jvmToolchain(25)
}

tasks.processResources {
    val version = project.version.toString()
    val versions = mapOf("version" to version, "minecraft" to minecraft, "loader" to loader, "kotlin" to fabricKotlin)
    inputs.properties(versions)
    filesMatching("fabric.mod.json") {
        expand(versions)
    }
    from(rootProject.file("media/icon.png")) { into("assets/worldapi") }
}

// The expansion API, core, and modcore aren't mods: their classes go into the mod's jar, the API's unrelocated. Once the mod serves the API, its libraries (Ktor,
// Exposed, the drivers) get shaded and relocated under com.mairwunnx.worldapi.libs, as in the plugin.
tasks.jar {
    dependsOn(expansion.tasks.named("classes"), core.tasks.named("classes"), modcore.tasks.named("classes"))
    from(expansion.layout.buildDirectory.dir("classes/java/main"))
    from(core.layout.buildDirectory.dir("classes/kotlin/main"), core.layout.buildDirectory.dir("resources/main"))
    from(modcore.layout.buildDirectory.dir("classes/kotlin/main"))
}
