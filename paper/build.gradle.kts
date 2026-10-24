import io.papermc.hangarpublishplugin.model.Platforms
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("io.papermc.hangar-publish-plugin")
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
    // OpenApiTest checks, and with WORLDAPI_UPDATE_OPENAPI=1 rewrites, the document at the repository's root.
    systemProperty("worldapi.openapi", rootProject.file("docs/openapi.json").absolutePath)
}

// The admin panel (admin/, React and Vite) builds with Bun into build/admin-dist, and the jar serves it under /admin.
// `-PskipPanel` leaves it out for quick builds: the jar then answers /admin with a note instead.
abstract class BuildPanel @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val panel: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun build() {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val names = if (windows) listOf("bun.exe", "bun.cmd") else listOf("bun")
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + (System.getProperty("user.home") + "/.bun/bin")
        val bun = dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.canExecute() }?.absolutePath
            ?: throw GradleException("The admin panel needs Bun (https://bun.sh) to build. Install it, or build without the panel: ./gradlew build -PskipPanel")
        val dir = panel.get().asFile
        exec.exec { workingDir = dir; commandLine(bun, "install", "--frozen-lockfile") }
        // `bun run build` makes the API types from docs/openapi.json, typechecks, and bundles.
        exec.exec { workingDir = dir; commandLine(bun, "run", "build"); environment("WORLDAPI_PANEL_OUT", output.get().asFile.absolutePath) }
    }
}

val skipPanel = providers.gradleProperty("skipPanel").isPresent

val buildPanel = tasks.register<BuildPanel>("buildPanel") {
    description = "Builds the admin panel with Bun."
    enabled = !skipPanel
    panel = rootProject.layout.projectDirectory.dir("admin")
    sources.from(
        rootProject.fileTree("admin") { exclude("**/node_modules/**", "src/api/generated/**", "*.tsbuildinfo", "dist/**") },
        rootProject.file("docs/openapi.json"),
    )
    output = layout.buildDirectory.dir("admin-dist")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("paper-plugin.yml") {
        expand("version" to version)
    }
    if (!skipPanel) {
        from(buildPanel) { into("admin") }
    }
}

tasks.shadowJar {
    // Paper is WorldAPI's only platform so far, so its jar keeps the name it always had: worldapi-X.Y.Z.jar.
    archiveBaseName = "worldapi"
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
val releaseNotes = (findProperty("changelogFile") as String?)?.let { rootProject.file(it).readText() }
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
    syncBodyFrom = rootProject.file("MODRINTH.md").readText()
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
