# Minecraft Paper Server

A local [Paper](https://papermc.io/) Minecraft server, set up as the foundation for a long-term
plugin development project.

- **Server software:** Paper 26.2, build 103 (stable channel, released 2026-08-07)
- **Minecraft version:** 26.2
- **Java:** 26.0.2 (Paper 26.2 requires Java 21 or newer)
- **Port:** 25565
- **Platform set up on:** macOS (Apple Silicon)

---

## What Paper is, and why it's here

Vanilla Minecraft's server has no plugin system. To run custom server-side code you need a
modified server, and there's a lineage of them:

| | |
|---|---|
| **Bukkit** | Defined the original plugin API. Long dead as a project, but its API shape survives — you still `import org.bukkit.*` today. |
| **Spigot** | A performance-focused fork of Bukkit. Added the Spigot API on top. |
| **Paper** | A fork of Spigot. Significantly faster, actively maintained, fixes many vanilla bugs, and adds the modern Paper API. |

**Paper is a superset.** A server running Paper can load Bukkit plugins, Spigot plugins, and
Paper plugins. That's why it's the standard choice — you get the largest possible ecosystem of
existing plugins plus the best API to write your own against.

Critically for this project, Paper is a *server-side* modification. Plugins run entirely on the
server. **Players connect with an unmodified, vanilla Minecraft client** — no mod installation,
no Forge, no Fabric, nothing for anyone to set up on their end. That is the single biggest
practical reason to build on Paper rather than a mod loader.

### What this gets you

Once you start writing plugins, you can hook into essentially anything the server does: player
join/leave, block place/break, damage, chat, crafting, entity spawns, redstone, and hundreds
more events. You can register custom commands, build custom items and recipes, store persistent
data on entities and item stacks, schedule repeating tasks, and manipulate the world directly.

The tradeoff, and it's worth understanding up front: because the client is vanilla, you cannot
add genuinely new blocks, entities, or rendering. What you do instead is repurpose existing
things — custom item models via resource packs, display entities, and creative reuse of vanilla
mechanics. Within that boundary the range is enormous (minigames, economies, skill systems, RPG
mechanics, world management), but it *is* a boundary.

---

## Prerequisites

**Java 21 or newer.** Check what you have:

```bash
java -version
```

If it's missing or older than 21, install a current JDK:

```bash
brew install openjdk
```

Homebrew will print a `sudo ln -sfn ...` command at the end — run it, or the system won't find
the new Java.

**Minecraft Java Edition.** The client must be the *same version as the server* — **26.2**. Note
that Minecraft has moved to date-based versioning, so the current release is `26.2`, not a
`1.21.x`. Set this up in the Minecraft Launcher under Installations → New Installation → choose
version `26.2`.

---

## Installing from scratch

The repo already contains a working server, so you only need this section if you're rebuilding
on a new machine or upgrading the Paper version.

**1. Find the current build.** Paper's API tells you the latest build for a given Minecraft
version:

```bash
curl -s https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest
```

Look for `channel: "STABLE"` and pull the URL out of `downloads.server:default.url`, plus the
`sha256` checksum next to it.

**2. Download it:**

```bash
curl -o paper-26.2-103.jar "<the url from step 1>"
```

**3. Verify the checksum** — the jar is 59 MB of code that will run with full access to your
machine, so confirm it's what Paper actually published:

```bash
shasum -a 256 paper-26.2-103.jar
```

The output must match the `sha256` from step 1 exactly. For this build it is:

```
3a717f68e330212219497cd3e224d76f175f14cc5d474c82a1c7cb354d6eca68
```

**4. First run.** This will fail on purpose — it exists to generate the config files:

```bash
java -Xms2G -Xmx4G -jar paper-26.2-103.jar --nogui
```

You'll see `You need to agree to the EULA in order to run the server.` That's expected. On this
first pass Paper also downloads the vanilla Mojang server jar and applies its patches to it,
which is why the `cache/`, `libraries/`, and `versions/` directories appear.

**5. Accept the EULA.** Open `eula.txt` and change `eula=false` to `eula=true`. Doing this is
your agreement to the [Minecraft EULA](https://aka.ms/MinecraftEULA), so read it first if you
haven't.

**6. Start the server:**

```bash
./start.sh
```

First real startup generates the world, which takes a bit longer than subsequent boots. You're
ready when you see:

```
Done (8.917s)! For help, type "help"
```

---

## Running the server

**Start:**

```bash
./start.sh
```

**Stop** — type this into the server console:

```
stop
```

Use `stop`, not Ctrl+C. `stop` flushes all loaded chunks and player data to disk and closes the
world cleanly. Killing the process can leave the world corrupted or roll back recent changes.

### What `start.sh` does

```bash
java -Xms2G -Xmx4G -jar paper-26.2-103.jar --nogui
```

- `-Xms2G` — allocate 2 GB of heap immediately at startup
- `-Xmx4G` — allow the heap to grow to at most 4 GB
- `--nogui` — don't open Mojang's little Swing window; run in the terminal

2–4 GB is comfortable for solo plugin development. If you later run a heavily-plugged server or
host several players, raise `-Xmx`. Don't set it near your total system RAM — the JVM needs
headroom outside the heap, and so does macOS.

---

## Connecting

With the server running:

1. Launch Minecraft Java Edition on version **26.2**
2. **Multiplayer** → **Direct Connection**
3. Server address: `localhost`
4. **Join Server**

Or click **Add Server** instead to save it permanently to your server list.

`localhost` works because the server is running on the same machine. Others on your network can
connect using your Mac's LAN IP (`ipconfig getifaddr en0`) as long as macOS's firewall permits
it. Exposing the server to the open internet is a separate matter involving port forwarding and
real security considerations — don't do it casually.

### Give yourself operator permissions

You'll want this for plugin testing — it unlocks `/gamemode`, `/give`, `/tp`, and administrative
commands. In the server console:

```
op YourMinecraftUsername
```

This writes to `ops.json` and persists across restarts.

---

## Directory layout

```
minecraft-server/
├── paper-26.2-103.jar        # the server itself
├── start.sh                  # launch script
├── README.md                 # this file
│
├── eula.txt                  # EULA acceptance (eula=true)
├── server.properties         # core server settings
│
├── plugins/                  # ← your compiled plugin .jar files go here
│   ├── bStats/               #   anonymous stats collection (Paper built-in)
│   └── spark/                #   built-in profiler, for diagnosing lag
│
├── config/
│   ├── paper-global.yml      # Paper-specific server-wide settings
│   └── paper-world-defaults.yml
├── bukkit.yml                # legacy Bukkit settings
├── spigot.yml                # legacy Spigot settings
├── commands.yml              # command aliases
│
├── ops.json                  # operators
├── whitelist.json            # allowed players (if white-list=true)
├── banned-players.json       # bans by account
├── banned-ips.json           # bans by IP
├── usercache.json            # username → UUID cache
│
├── world/
│   ├── level.dat             # world metadata, seed, spawn point
│   ├── dimensions/minecraft/ # overworld, the_nether, the_end
│   ├── data/                 # map items, scoreboards, world data
│   ├── datapacks/            # vanilla datapacks
│   └── players/              # per-player inventories, positions, stats
│
├── logs/
│   ├── latest.log            # current session — read this when debugging
│   └── *.log.gz              # rotated, compressed older logs
│
├── cache/                    # downloaded vanilla Mojang jar
├── libraries/                # Java dependencies Paper pulls at runtime
└── versions/                 # patched server jar built at first run
```

`cache/`, `libraries/`, and `versions/` are generated artifacts (~165 MB combined). If you ever
version-control this directory, exclude those along with `logs/` and `world/`.

---

## Configuration

Everything is on defaults right now:

| Setting | Value | Note |
|---|---|---|
| `server-port` | `25565` | the standard Minecraft port |
| `gamemode` | `survival` | |
| `difficulty` | `easy` | |
| `max-players` | `20` | |
| `online-mode` | `true` | **verifies accounts against Mojang's auth servers — leave this on** |
| `white-list` | `false` | set `true` to restrict to `whitelist.json` |
| `view-distance` | `10` | chunks; lower it if the server struggles |
| `motd` | `A Minecraft Server` | the line shown in the server list |
| `spawn-protection` | `16` | block radius around spawn only ops can build in |

`online-mode=true` is the security-relevant one. It means every connecting player is
authenticated against Mojang, which is what stops someone from simply claiming your username.
Turning it off — occasionally suggested for LAN convenience — means anyone who can reach the
port can log in as anyone. Keep it on.

Changes to `server.properties` require a restart to take effect.

---

## Plugin development

Not set up yet — this is the next step. The shape of it:

A plugin is a `.jar` built against the Paper API. You write Java (or Kotlin), compile with
Gradle or Maven, and drop the resulting jar into `plugins/`. On startup Paper reads each jar's
`plugin.yml`, loads the main class, and calls its `onEnable()`.

From there you register event listeners and commands, and your code runs inside the server
process with full access to the world, players, and entities.

The typical project setup:

- **Gradle** with the `paperweight-userdev` plugin
- **Java 21** as the target, matching Paper's baseline
- The Paper API as a `compileOnly` dependency — the server provides it at runtime
- A build task that copies the output jar directly into this `plugins/` folder, so the loop is
  build → restart → test

Restarting is the reliable way to load changes. `/reload` exists but is genuinely unreliable and
a well-known source of confusing bugs — avoid the habit.

---

## Version control

A `.gitignore` is in place, but no repository has been initialized yet. When you're ready:

```bash
git init
git add .
git commit -m "Initial Paper server setup"
```

The guiding principle is **version what you author, ignore what the server generates**. Everything
excluded is reproducible from a fresh run. After `git add .` only these are staged:

```
.gitignore
README.md
start.sh
server.properties.example
plugins/.gitkeep
```

Notable exclusions and why:

| Excluded | Reason |
|---|---|
| `paper-*.jar` | 59 MB. GitHub warns above 50 MB, rejects at 100 MB. Re-download it — the README has the build and checksum. |
| `server.properties` | **Contains secrets** — `management-server-secret` is auto-generated with a live value, and `rcon.password` if you enable RCON. |
| `world/` | Large, changes every tick, merges catastrophically. Back this up separately; git is the wrong tool. |
| `cache/`, `libraries/`, `versions/` | ~165 MB Paper downloads and patches on first run. |
| `ops.json`, `usercache.json`, `banned-ips.json` | Usernames, UUIDs, IP addresses. |
| `eula.txt` | Accepting the EULA is a personal legal act — let whoever runs the server accept it themselves. |
| `logs/`, `build/`, `.gradle/`, `.DS_Store` | Churn and local noise. |

### The server.properties problem

Because the real file is ignored, `server.properties.example` is committed in its place — identical
but with all three credential fields blanked. It documents your settings without leaking anything.

When you change a setting worth keeping, refresh the example:

```bash
sed -E 's/^(management-server-secret|rcon\.password|management-server-tls-keystore-password)=.*/\1=/' \
  server.properties > server.properties.example
```

And when setting up on a new machine, copy it the other way — the server regenerates its own secret
on first boot:

```bash
cp server.properties.example server.properties
```

If you ever *do* want a tuned config tracked, check it for credentials first, then force-add it:
`git add -f <file>`. Bear in mind that anything committed stays in git history even if you delete it
later — scrubbing a leaked secret means rewriting history, so it's far easier not to commit it.

## Troubleshooting

**"Outdated server!" or "Outdated client!" when connecting**
Client and server versions don't match. The server is 26.2; set your Minecraft installation to
26.2 exactly.

**`UnsupportedClassVersionError` on startup**
Java is too old. Paper 26.2 needs Java 21+. Check with `java -version`.

**"Failed to bind to port"**
Something already holds 25565 — most likely a server you forgot to stop. Find it:

```bash
lsof -nP -iTCP:25565 -sTCP:LISTEN
```

**`Failed to start the minecraft server` … `session.lock: already locked`**
A server is *already running* and holding this world. Minecraft locks `world/session.lock` so two
processes can never write the same chunks — this error is the safety mechanism working, not a
corruption. Find the running server and stop it:

```bash
lsof -nP -iTCP:25565 -sTCP:LISTEN          # what's on the port
ps -eo pid,etime,command | grep [p]aper    # the server process
```

If it has a console, type `stop` there. Otherwise `kill -TERM <pid>` — Paper's shutdown hook runs
on SIGTERM and saves the world properly. Confirm it saved by looking for `All dimensions are
saved` in the log, then start again.

If the lock persists with genuinely no server running (only happens after a hard crash or power
loss), delete `world/session.lock` — it's regenerated on startup. Never delete it to bypass a
live server.

**Server won't start, `Failed to load eula.txt`**
`eula.txt` still says `eula=false`. See step 5 above.

**A plugin isn't loading**
Read `logs/latest.log`. Plugin load failures print a full stack trace there and the reason is
almost always in it — usually a malformed `plugin.yml` or a version mismatch against the API.

**Server is lagging**
`spark` is already installed. Run `/spark profiler start` in game, let it collect during the
lag, then `/spark profiler stop` for a report showing exactly what's consuming tick time.

---

## References

- [Paper documentation](https://docs.papermc.io/)
- [Paper API javadocs](https://jd.papermc.io/paper/)
- [Paper plugin development guide](https://docs.papermc.io/paper/dev/)
- [Minecraft EULA](https://aka.ms/MinecraftEULA)
