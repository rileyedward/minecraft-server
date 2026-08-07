plugins {
    java
}

group = "com.rileyedward"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// The Paper version lives in ../paper.env so the server jar and the API we
// compile against can never drift apart. Compiling against a different build
// than you run is a classic source of NoSuchMethodError at runtime.
val paperEnv = rootProject.file("../paper.env")
require(paperEnv.exists()) {
    "Missing ${paperEnv.path} — it holds the Paper version shared with start.sh"
}
val paperApiVersion: String = paperEnv.readLines()
    .firstOrNull { it.trimStart().startsWith("PAPER_API_VERSION=") }
    ?.substringAfter("=")
    ?.trim()
    ?: error("PAPER_API_VERSION not set in ${paperEnv.path}")

dependencies {
    // `compileOnly`, NOT `implementation`. The server already provides the Paper
    // API at runtime — bundling it would ship a second, conflicting copy of every
    // Bukkit class.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

tasks.withType<JavaCompile>().configureEach {
    // paper-api is compiled to Java 25 bytecode; this cannot be lowered.
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("CustomPlugins")
    // No version suffix, so each deploy overwrites the previous jar instead of
    // leaving copies behind for the server to load alongside each other.
    archiveVersion.set("")
}

// Substitutes ${version} in plugin.yml so the version is declared in one place.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// ./gradlew deploy → builds and copies the jar into the server's plugins folder.
// Copies only this jar; anything else in plugins/ is left untouched.
tasks.register<Copy>("deploy") {
    group = "paper"
    description = "Build the plugin and copy it into the server's plugins/ directory"
    from(tasks.jar)
    into(layout.projectDirectory.dir("../plugins"))
    doLast {
        println("Deployed -> plugins/CustomPlugins.jar  (restart the server to load it)")
    }
}
