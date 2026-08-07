plugins {
    java
}

group = "com.rileyedward"
version = "1.0.0"

repositories {
    mavenCentral()
    // Where the Paper API lives. Not on Maven Central.
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // `compileOnly`, NOT `implementation`. The server already provides the Paper
    // API at runtime — bundling it into your jar would ship a second, conflicting
    // copy of every Bukkit class. This is the single most common beginner mistake.
    //
    // The build number here (103) matches the server jar in the parent directory.
    // When you update the server, update this too so you compile against the same API.
    compileOnly("io.papermc.paper:paper-api:26.2.build.103-stable")
}

tasks.withType<JavaCompile>().configureEach {
    // Paper 26.1+ requires Java 25 — the paper-api jar is compiled to Java 25
    // bytecode (class file major version 69), so this cannot be lowered.
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("SamplePlugins")
    // Drop the version from the filename so each deploy overwrites the previous
    // jar. Otherwise plugins/ accumulates copies and the server loads all of them.
    archiveVersion.set("")
}

// Substitutes ${version} in plugin.yml with the project version above, so the
// version is declared in exactly one place.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// ./gradlew deploy  →  builds and copies the jar into the server's plugins folder.
tasks.register<Copy>("deploy") {
    group = "paper"
    description = "Build the plugin and copy it into the server's plugins/ directory"
    from(tasks.jar)
    into(layout.projectDirectory.dir("../plugins"))
    doLast {
        println("Deployed -> plugins/SamplePlugins.jar  (restart the server to load it)")
    }
}
