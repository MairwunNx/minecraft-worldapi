import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("com.modrinth.minotaur") version "2.10.0"
    id("io.papermc.hangar-publish-plugin") version "0.1.4"
}

group = "com.mairwunnx"
// The release workflow passes the version; every other build is a dev build.
version = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "dev"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.playeranalytics.net/releases/")
}

val ktorVersion = "3.6.0"
val exposedVersion = "1.6.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    // Paper ships the SQLite driver, so it stays out of the jar.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    // Paper logs through Log4j; GET /server/logs hooks its root logger.
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.1")
    // Plan is optional: its section shows on Plan's pages when Plan runs next to WorldAPI.
    compileOnly("com.djrapitops:plan-api:5.9-R1.3")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.14")
    // MariaDB's driver also talks to MySQL; MySQL's own is GPL.
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    // Compiles scripts on servers that run a JRE without javac.
    implementation("org.eclipse.jdt:ecj:3.46.100")

    testImplementation("io.papermc.paper:paper-api:26.3.build.205-stable")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
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
    // Servers for StoreTest to run on besides SQLite, as postgresql://user:password@host:port/name or mysql://...
    inputs.property("testDatabases", providers.environmentVariable("WORLDAPI_TEST_DATABASES").orElse(""))
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("paper-plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    // Paper already provides SLF4J and routes it into the server log.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
        // Checker Framework annotations come with the PostgreSQL driver and do nothing at runtime.
        exclude(dependency("org.checkerframework:.*"))
    }
    listOf(
        "kotlin", "kotlinx", "io.ktor", "com.charleskorn.kaml", "it.krzeminski.snakeyaml", "okio", "net.thauvin", "com.typesafe.config",
        "org.jetbrains.exposed", "com.zaxxer.hikari", "org.postgresql", "org.mariadb.jdbc",
    )
        .forEach { relocate(it, "com.mairwunnx.worldapi.libs.$it") }
    // ECJ stays where it is: it finds its message bundles by package name, and nothing else ships it.
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
    // ECJ's jar is signed; its signature doesn't cover a shaded jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

// The release workflow writes the notes to a file; a local upload falls back to the GitHub release.
val releaseNotes = (findProperty("changelogFile") as String?)?.let { file(it).readText() }
    ?: "https://github.com/MairwunNx/minecraft-worldapi/releases/tag/v${project.version}"

// `./gradlew modrinth` with MODRINTH_TOKEN set; the release workflow runs it when asked to.
modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = "worldapi"
    versionNumber = project.version.toString()
    versionName = "WorldAPI ${project.version}"
    versionType = "release"
    uploadFile.set(tasks.shadowJar)
    gameVersions.addAll("26.3")
    loaders.addAll("paper", "purpur")
    changelog = releaseNotes
    syncBodyFrom = file("MODRINTH.md").readText()
}

tasks.modrinth {
    dependsOn(tasks.modrinthSyncBody)
}

// `./gradlew publishPluginPublicationToHangar` with HANGAR_TOKEN set; the release workflow runs it when asked to.
hangarPublish {
    publications.register("plugin") {
        version = project.version.toString()
        id = "WorldAPI"
        channel = "Release"
        apiKey = providers.environmentVariable("HANGAR_TOKEN")
        changelog = releaseNotes
        platforms {
            register(Platforms.PAPER) {
                // The jar is over Hangar's 15 MB limit, so Hangar links to the GitHub release instead.
                url = "https://github.com/MairwunNx/minecraft-worldapi/releases/download/v${project.version}/worldapi-${project.version}.jar"
                platformVersions = listOf("26.3")
            }
        }
    }
}
