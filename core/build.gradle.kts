plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val ktorVersion = "3.6.0"
val exposedVersion = "1.6.0"

// Everything that doesn't touch a game platform: no Bukkit, no Minecraft, no loader. paper builds on it now, and the mods
// will through modcore, so another platform is only an adapter.
dependencies {
    // The expansion API ships inside every jar as it is, never relocated: other plugins and mods compile against it.
    api(project(":expansion"))
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")
    api("io.ktor:ktor-server-websockets:$ktorVersion")
    api("io.ktor:ktor-server-cors:$ktorVersion")
    api("io.ktor:ktor-server-status-pages:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("com.charleskorn.kaml:kaml:0.104.0")
    api("org.jetbrains.exposed:exposed-core:$exposedVersion")
    api("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    api("com.zaxxer:HikariCP:7.1.0")
    api("org.postgresql:postgresql:42.7.14")
    // MariaDB's driver also talks to MySQL; MySQL's own is GPL.
    api("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    // Compiles scripts on servers that run a JRE without javac.
    api("org.eclipse.jdt:ecj:3.46.100")
    // Every platform ships its own SQLite driver (Paper does), so it stays out of the jar.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    // Paper and the game log through Log4j, and both bring it; GET /server/logs hooks its root logger.
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.1")

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
