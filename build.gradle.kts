plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.mairwunnx"
// CI passes the tag as releaseVersion; local builds use the version below.
version = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val ktorVersion = "3.6.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.169-beta")
    // Paper ships the SQLite driver, so it stays out of the jar.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")
    // Compiles scripts on servers that run a JRE without javac.
    implementation("org.eclipse.jdt:ecj:3.46.100")

    testImplementation("io.papermc.paper:paper-api:26.3.build.169-beta")
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
    dependencies { exclude(dependency("org.slf4j:.*")) }
    listOf("kotlin", "kotlinx", "io.ktor", "com.charleskorn.kaml", "it.krzeminski.snakeyaml", "okio", "net.thauvin", "com.typesafe.config")
        .forEach { relocate(it, "com.mairwunnx.worldapi.libs.$it") }
    // ECJ stays where it is: it finds its message bundles by package name, and nothing else ships it.
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
    // ECJ's jar is signed; its signature doesn't cover a shaded jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
