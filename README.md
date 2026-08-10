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

**Custom Plugins**: A Gradle project in `custom-plugins/` that compiles straight into the server's `plugins/` folder.

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
   _exactly_. Minecraft uses date-based versioning now, so you're looking for `26.2` — not a
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

#### Installed

Because `plugins/` is gitignored, the jars themselves aren't in the repo — this table is the record
of what's meant to be there. A fresh clone starts with an empty `plugins/`; re-download these to
rebuild the set.

| Plugin            | Version | Does                                                       | Source                                                    |
| ----------------- | ------- | ---------------------------------------------------------- | --------------------------------------------------------- |
| Chunky            | 1.5.3   | Pre-generates chunks so exploration doesn't lag the server | [Modrinth](https://modrinth.com/plugin/chunky)            |
| LuckPerms         | 5.5.71  | Permissions and groups — who can run what                  | [Modrinth](https://modrinth.com/plugin/luckperms)         |
| Multiverse-Core   | 5.7.3   | Multiple worlds in one server, with portals between them   | [Modrinth](https://modrinth.com/plugin/multiverse-core)   |
| Multiverse-Inventories | 5.3.5 | Gives each world its own inventory, XP, and health        | [Modrinth](https://modrinth.com/plugin/multiverse-inventories) |
| ViaVersion        | 5.11.0  | Lets clients on older Minecraft versions connect           | [Modrinth](https://modrinth.com/plugin/viaversion)        |
| Simple Voice Chat | 2.6.21  | Proximity voice chat                                       | [Modrinth](https://modrinth.com/plugin/simple-voice-chat) |

Two of these carry caveats worth knowing before you rely on them:

- **Simple Voice Chat is the one exception to "players install nothing."** The server half is a
  plugin, but players need the matching client mod (Fabric, Forge, or NeoForge) to hear or speak.
  Anyone without it still joins and plays normally — they just have no voice.
- **Multiverse-Core takes over world loading.** On first start it writes
  `plugins/Multiverse-Core/worlds.yml` and imports the default world. Note that on Paper 26.2 extra
  worlds are *not* top-level folders — they live inside the default world's directory under
  `current/dimensions/minecraft/<name>/`. See [Worlds](#worlds) below.

When you add or update a plugin, update this table. It's the only place the versions are recorded.

## Worlds

The server hosts three archived realm worlds, switchable in-game:

| Command   | World    | Was                                      |
| --------- | -------- | ---------------------------------------- |
| `/current` | `current` | The current realm — the default world    |
| `/old`     | `old`     | The previous realm (imported from 1.21.4) |
| `/oldest`  | `oldest`  | The earliest realm (imported from 1.21.6) |

The commands come from the `worlds` feature in `custom-plugins/`. They're gated behind the
`smp.worlds` permission, which operators have automatically. To let everyone use them:

```
/lp group default permission set smp.worlds true
```

`current` is the default world (`level-name=current` in `server.properties`), so players spawn
there on join. `old` and `oldest` are set `auto-load: false` in `plugins/Multiverse-Core/worlds.yml`
— they stay unloaded until someone runs the command, which keeps them out of the 4G heap on an 8GB
machine. The first `/old` of a session pauses briefly while the world loads.

Each realm keeps its own inventory, XP, and health via Multiverse-Inventories. The groups are in
`plugins/Multiverse-Inventories/groups.yml`: `current` shares with its nether and end, `oldest`
shares with `oldest_nether` and `oldest_the_end`, and `old` stands alone.

### Where the world data lives

**On Paper 26.2, every world is a dimension of the default world's folder.** Importing a world named
`old` does not create `old/` — Paper's `LegacyCraftBukkitWorldMigration` moves it to
`current/dimensions/minecraft/old/` and deletes the staging folder. Back up `current/` and you have
captured all of them.

The three archived realms are **committed to git** (1.5 GB); the live world is not. They're also set
to adventure mode with mob spawning off, so they stay museum pieces rather than rewriting region
files — and growing the history — every time someone walks through. See the version control section
of [Operations](docs/OPERATIONS.md) for the `.gitignore` rules, which are fussier than usual.

### Importing another old save

Paper converts pre-26 saves automatically, including the 1.21.x chunk format. There is no need to
open them in the Minecraft client first.

1. Copy the save to the server root under a lowercase name, and delete `session.lock`, `gc-logs/`,
   and `realms-upload.log` from the copy. **Copy, never move** — the originals are the only backup.
2. Run `/mv import <name> NORMAL`. Paper migrates the layout and the staging folder disappears.
3. Add the world to a group in `plugins/Multiverse-Inventories/groups.yml`.

One catch: a *vanilla* save keeps its nether and end in `DIM-1/` and `DIM1/`, and the migrator only
handles the overworld — it discards those two without warning. To keep them, stage each separately
before importing, with the save's `level.dat` alongside the dimension folder:

```
oldest_nether/level.dat + oldest_nether/DIM-1/   →  /mv import oldest_nether NETHER
oldest_the_end/level.dat + oldest_the_end/DIM1/  →  /mv import oldest_the_end THE_END
```

The environment argument must be one of `NORMAL`, `NETHER`, `THE_END`, or `CUSTOM`.

## Documentation

| Guide                                                          | Covers                                                                              |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| [Operations](docs/OPERATIONS.md)                               | Running the server day to day — directories, config files, backups, troubleshooting |
| [Deployment](docs/DEPLOYMENT.md)                               | Putting the server on a DigitalOcean droplet and keeping it updated                 |
| [Plugin development basics](docs/plugin-development-basics.md) | The Paper API from scratch, written for someone new to Java                         |
| [Creating a new plugin](docs/creating-a-new-plugin.md)         | Adding a feature to `custom-plugins/`                                               |
| [The magic wand, explained](docs/magic-wand-explained.md)      | A worked example, walked through line by line                                       |
| [Plugin ideas](docs/plugin-ideas.md)                           | A backlog of things worth building next                                             |
| [Admin panel](docs/ADMIN-PANEL.md)                             | Design notes for a web management panel — not built yet                             |
| [Speedrun world](docs/SPEEDRUN-WORLD.md)                       | Design notes for a resettable hardcore speedrun dimension — not built yet           |
