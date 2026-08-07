# Minecraft Server

A Paper Minecraft server built for custom plugin development.

## Overview

### What is Minecraft Server?

Minecraft Server is a local [Paper](https://papermc.io/) server set up as the foundation for
long-term plugin development. It runs on your machine, loads plugins written in Java, and players
connect with an ordinary, unmodified Minecraft client.

### Why Paper?

Vanilla Minecraft's server has no plugin system at all. Paper is a modified server that adds one,
sitting at the end of a lineage: **Bukkit** defined the original plugin API, **Spigot** forked it
for performance, and **Paper** forked Spigot again — faster, actively maintained, with a modern
API. Paper is a superset, so it loads Bukkit, Spigot, and Paper plugins alike.

All of it is server-side. Nobody joining needs Forge, Fabric, or a single mod installed, which is
the main reason to build here rather than on a mod loader.

### Key Features

- **Custom Plugins**: A Gradle project in `custom-plugins/` that compiles straight into the server's `plugins/` folder.
- **Community Plugins**: Drop-in jars from Modrinth or Hangar run alongside your own code.
- **Vanilla Clients**: Players install nothing — any stock Minecraft client can connect.
- **Simple Control**: `./start.sh` and `./stop.sh` handle the server, with a guard so two can never run over the same world.

## Getting Started

### Prerequisites

Ensure you have the following prerequisites installed on your system. You can verify each
installation by running the provided commands in your terminal.

1. **Java 25 or newer** is required to run the server and build plugins. Paper 26.1+ requires it,
   and the plugin API is compiled to Java 25 bytecode, so this is a hard floor. Check with:

    ```bash
    java --version
    ```

    If it's missing or older, install a current JDK with `brew install openjdk`, then run the
    `sudo ln -sfn ...` command Homebrew prints at the end.

2. **Minecraft Java Edition 26.2** is needed to connect. The client must match the server
   *exactly*. Minecraft uses date-based versioning now, so you're looking for `26.2` — not a
   `1.21.x`. Add it in the Minecraft Launcher under Installations → New Installation.

Gradle is **not** required. The committed wrapper (`./gradlew`) downloads what it needs.

### Installation

1. Accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) by opening `eula.txt` and setting:

    ```
    eula=true
    ```

2. Build the custom plugins into the server:

    ```bash
    cd custom-plugins
    ./gradlew deploy
    ```

3. Start the server from the project root:

    ```bash
    ./start.sh
    ```

    It's ready when you see `Done (6.0s)! For help, type "help"`.

4. Connect from Minecraft: **Multiplayer** → **Direct Connection** → `localhost` → **Join Server**.

5. Give yourself operator permissions by typing this into the server console, so `/gamemode`,
   `/give`, and the rest are available while testing:

    ```
    op YourMinecraftUsername
    ```

## Running the Server

Start it:

```bash
./start.sh
```

Stop it — either type `stop` into the server console, or from any other terminal:

```bash
./stop.sh
```

Use one of those rather than Ctrl+C. Both flush every loaded chunk and player to disk, close the
world cleanly, and release the world lock. Killing the process outright can roll back recent
changes.

Only one server can use the world at a time — Minecraft locks it so two processes never write the
same chunks. If one is already running, `./start.sh` tells you and offers to restart it instead of
failing with a stack trace.

## Plugins

Every plugin, yours or someone else's, is a `.jar` file in `plugins/`. The server scans that folder
at startup and loads what it finds, so **a new or rebuilt plugin needs a restart** to take effect.

`plugins/` is gitignored — no jars are committed, and each plugin's config folder is created there
on first run.

### Custom Plugins

Your code lives in `custom-plugins/`, a Gradle project that builds `CustomPlugins.jar`:

```bash
cd custom-plugins
./gradlew deploy        # compiles and copies the jar into ../plugins/
```

Then restart the server to load it.

Features each live in their own package under `src/main/java/com/rileyedward/smp/features/`. To add
one, create the package and add a single line to the `FEATURES` list in `SmpPlugin.java`.

`sample-plugins/` is a second project kept purely as reference — four worked examples covering
events, commands, saved data, and custom items. It isn't deployed and the server doesn't load it.

### Community Plugins

Download the jar from [Modrinth](https://modrinth.com/plugins) or
[Hangar](https://hangar.papermc.io), drop it into `plugins/`, and restart:

```
plugins/
├── CustomPlugins.jar      ← yours, from ./gradlew deploy
└── EssentialsX.jar        ← downloaded, dropped in
```

Check the plugin supports Minecraft 26.2 before installing, or it may fail to load. Running
`./gradlew deploy` only writes its own jar, so rebuilding your code never touches anything you've
installed.
