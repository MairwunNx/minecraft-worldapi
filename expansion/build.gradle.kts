plugins {
    `java-library`
    `maven-publish`
    signing
}

// The expansion API: plain Java with no dependencies, so a plugin or mod in any language can use it and WorldAPI's own
// Kotlin stays private. WorldAPI's jars carry these classes as they are; expansions compile against the published jar.
// It has its own version, apart from WorldAPI's.
version = (findProperty("expansionVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    // Java 21 bytecode: a library shouldn't ask for more than the oldest server it could meet.
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "worldapi-expansion"
            from(components["java"])
            pom {
                name = "WorldAPI Expansion API"
                description = "Share a plugin's or mod's data through WorldAPI, the REST and WebSocket API for Minecraft servers: resources under /extensions, event topics, and the history, from Paper, Fabric, or NeoForge."
                url = "https://github.com/MairwunNx/minecraft-worldapi/wiki/Expansions"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/license/mit"
                    }
                }
                developers {
                    developer {
                        id = "mairwunnx"
                        name = "MairwunNx"
                        url = "https://github.com/MairwunNx"
                    }
                }
                scm {
                    url = "https://github.com/MairwunNx/minecraft-worldapi"
                    connection = "scm:git:https://github.com/MairwunNx/minecraft-worldapi.git"
                    developerConnection = "scm:git:ssh://git@github.com/MairwunNx/minecraft-worldapi.git"
                }
            }
        }
    }
    repositories {
        // The release bundles this folder for Maven Central's Portal API (scripts/central.py).
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
}

signing {
    // The release hands in the armored key and its passphrase (the MAVEN_SIGNING_* secrets); local builds and
    // publishToMavenLocal go unsigned.
    val key = providers.environmentVariable("MAVEN_SIGNING_KEY").orNull
    val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications["maven"])
    }
}
