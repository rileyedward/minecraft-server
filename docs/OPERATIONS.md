# Operations Guide

Everything about running and maintaining this server day to day: what each directory is for,
which config file controls what, and how to perform the common operations safely.

For setup and connecting, see the [README](../README.md). For putting this on a remote host, see
[Deployment](DEPLOYMENT.md).

---

## The one distinction that matters

Every file here falls into one of two categories:

| | |
|---|---|
| **Authored** — you write it, git tracks it | `start.sh`, `stop.sh`, `README.md`, `custom-plugins/` |
| **Generated** — the server creates it, reproducible | `cache/`, `libraries/`, `versions/`, `logs/`, `current/`, most configs |

The generated side is ~165 MB of downloads plus your world. Knowing which is which tells you what's
safe to delete when something breaks: **anything generated can be removed and will come back.** The
sole exception is `current/`, which is generated but irreplaceable — it holds everything players built.

---

## Directory-by-directory

### The Paper bootstrap chain: `paper-*.jar` → `cache/` → `versions/` → `libraries/`

This confuses people, because the jar you downloaded is not the server that runs.

```
paper-26.2-103.jar   (59 MB)  ← what you downloaded. A small launcher + patch data.
      ↓ on first run
cache/mojang_26.2.jar (58 MB) ← Mojang's actual vanilla server, downloaded automatically
      ↓ patches applied
versions/26.2/paper-26.2.jar (28 MB) ← the real patched server that executes
libraries/            (80 MB)  ← Java dependencies (Netty, Guava, Adventure, …)
```

Paper can't legally redistribute Mojang's server code, so it ships patches and applies them to a
copy it fetches on your machine. That's why the first run took longer and printed
`Downloading mojang_26.2.jar` / `Applying patches`.

**Safe to delete all three?** Yes — `cache/`, `versions/`, and `libraries/` regenerate on next
start. Deleting them is a legitimate fix if the server won't boot after a botched update. It costs
one slow startup.

### `current/` — the irreplaceable one

The world folder is named after `level-name` in `server.properties`, which on this server is
`current`.

```
current/
├── level.dat              world metadata: seed, spawn point, game rules, world time
├── level.dat_old          automatic backup of the above
├── session.lock           the "a server is using this" lock
├── dimensions/minecraft/
│   ├── overworld/region/  the actual terrain, as .mca region files
│   ├── the_nether/
│   ├── the_end/
│   ├── old/               the previous realm — a whole extra world
│   ├── oldest/            the earliest realm
│   ├── oldest_nether/
│   └── oldest_the_end/
├── data/                  maps, scoreboards, raids, villages
├── datapacks/             vanilla datapacks
└── players/               one file per player: inventory, position, health, XP
```

Note the modern layout — every dimension lives *inside* `current/`. Older guides describe sibling
`world_nether/` and `world_the_end/` folders; that's no longer how it works.

**This includes entire additional worlds.** On Paper 26.2 an imported world is stored as another
entry under `dimensions/minecraft/`, not as a folder beside `current/`. That makes backup simple —
one folder is every world — but it also means `current/` is much larger than a single world's worth
of data. See the Worlds section of the README for how the three realms are set up.

Region files (`r.0.0.mca`) each hold a 32×32 chunk area. They grow as players explore and are
never automatically pruned.

### `logs/`

```
logs/latest.log          current session, plain text
logs/2026-08-06-1.log.gz rotated and compressed on each restart
```

`latest.log` is overwritten every start, so a crash you want to investigate must be read before
restarting, or found in the rotated archive. **This is the first place to look for anything** —
plugin load failures, stack traces, players connecting.

```bash
tail -f logs/latest.log                    # watch live
grep -i "error\|exception" logs/latest.log # find problems
zgrep -i "someplayer" logs/*.log.gz        # search history
```

### `plugins/`

Compiled `.jar` files, plus a data folder per plugin (`plugins/CustomPlugins/config.yml`).
Deleting a plugin means deleting both. Deleting only the jar leaves its config orphaned but
harmless.

### The JSON state files

`ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json`, `usercache.json`. Edit
these with console commands (`op`, `ban`, `whitelist add`) rather than by hand — the server holds
them in memory and will overwrite your edits on shutdown. If you must edit them directly, stop the
server first.

---

## The config files, and which one to use

There are five, because Paper inherits three generations of ancestry. The practical rule: **the
more specific file wins.**

| File | Owns | Reload |
|---|---|---|
| `server.properties` | Core vanilla: port, gamemode, difficulty, max players, MOTD, view distance, online-mode | Restart |
| `config/paper-global.yml` | Paper server-wide: chunk system, watchdog, packet limits, console, proxies | Restart |
| `config/paper-world-defaults.yml` | Paper per-world defaults: mob spawning, growth rates, hoppers, entity ticking | Restart |
| `current/dimensions/minecraft/<dim>/paper-world.yml` | Overrides the above for **one dimension** | Restart |
| `bukkit.yml` | Legacy: spawn limits, chunk GC, tick intervals | Restart |
| `spigot.yml` | Legacy: entity activation ranges, item merge radius, messages | Restart |
| `commands.yml` | Command aliases | Restart |

**Where do I change X?**

| Want to change | Edit |
|---|---|
| Port, difficulty, gamemode, MOTD, max players | `server.properties` |
| Whitelist on/off | `server.properties` (`white-list=true`) |
| Render/view distance | `server.properties` (`view-distance`, `simulation-distance`) |
| Mob spawn caps | `bukkit.yml` → `spawn-limits` |
| Mob spawn behavior, crop growth, hopper speed | `config/paper-world-defaults.yml` |
| Nether-only tweak | `current/dimensions/minecraft/the_nether/paper-world.yml` |
| Lag from too many entities | `spigot.yml` → `entity-activation-range` |
| Timeout before the watchdog kills a frozen server | `config/paper-global.yml` → `watchdog` |

Paper's per-setting documentation is at
[docs.papermc.io/paper/reference/configuration](https://docs.papermc.io/paper/reference/configuration/).

### Worth changing early

```properties
# server.properties
motd=My Dev Server           # shown in the multiplayer list
difficulty=normal            # easy is the default; normal or hard for real play
view-distance=8              # lower = less CPU/bandwidth. 10 is default, 8 is plenty locally
simulation-distance=6        # where entities actually tick. Bigger performance lever than view-distance
spawn-protection=0           # default 16 stops non-ops building near spawn — usually unwanted on a private server
```

### Leave alone unless you know why

- `online-mode=true` — turning this off lets anyone log in as anyone
- `management-server-secret` — auto-generated credential, never commit it
- `max-tick-time` / `watchdog` — raising these hides real problems rather than fixing them
- `config-version` fields — managed by the server's own migrations

---

## Common operations

### Console commands

Type these directly into the terminal running the server (no leading `/`):

| Command | Does |
|---|---|
| `stop` | Graceful shutdown. Always use this. |
| `save-all` | Force a write to disk right now |
| `save-off` / `save-on` | Suspend/resume saving — for hot backups, see below |
| `list` | Who's online |
| `op <player>` / `deop <player>` | Grant/revoke admin |
| `whitelist add <player>` | Add to allowlist |
| `ban <player>` / `pardon <player>` | Ban/unban |
| `kick <player> [reason]` | Disconnect someone |
| `say <message>` | Broadcast |
| `tps` | Ticks per second — 20.0 is healthy, below 18 means trouble |
| `spark profiler start` / `stop` | Profile what's eating tick time |

### Back up the world

**Cold (safest)** — server stopped:

```bash
./stop.sh
tar -czf backups/world-$(date +%Y%m%d-%H%M).tar.gz current/
./start.sh
```

**Hot** — server running, players connected. Suspend writes so you copy a consistent snapshot:

```
save-off
save-all
```

```bash
tar -czf backups/world-$(date +%Y%m%d-%H%M).tar.gz current/
```

```
save-on
```

Do not forget `save-on`. With saving suspended, everything players do lives only in memory — a
crash before you re-enable it loses all of it.

Back up `plugins/` too if plugin configs matter to you. Skip `cache/`, `libraries/`, and
`versions/` — they're re-downloadable.

### Reset the world

Three approaches, depending on what you want.

> ⚠️ **`current/` is not one world — it is all of them.** Since Paper 26.2 stores imported worlds
> under `current/dimensions/minecraft/`, deleting `current/` destroys the `old` and `oldest` realms
> along with the current one. The advice below is written with that in mind.

**Wipe and regenerate with a new random seed:**

```bash
./stop.sh
rm -rf current/        # ⚠ permanent — this deletes EVERY world, not just the current one
./start.sh
```

**Keep the existing worlds, start a fresh one alongside them** — safer, and reversible:

```properties
# server.properties
level-name=world2
```

`current/` stays untouched on disk, archived realms and all. Switch back by setting the name again.
This is the option you almost always want.

**Generate a specific seed:**

```properties
level-name=world2
level-seed=-1234567890      # or any text, which gets hashed
```

The seed only applies when the world is first created — setting it on an existing world does
nothing. Pair it with a delete or a new `level-name`.

To reset just *one* dimension (a common trick for refreshing depleted nether resources), delete
only `current/dimensions/minecraft/the_nether/` while the server is stopped. Note that the same path
shape is how whole worlds are stored, so check the name carefully before deleting —
`dimensions/minecraft/oldest/` is a realm, not a dimension of the current world.

### Update Paper to a new build

```bash
# 1. Find the current build
curl -s https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest

# 2. Download and verify the checksum (see "Installing from scratch" below)

# 3. Edit paper.env — the only place the version is declared
```

`start.sh` and both plugin projects' `build.gradle.kts` read `paper.env`, so there's nothing else to
change. If the jar named there isn't present, `start.sh` says so instead of failing obscurely.

Back up `current/` before a version upgrade — worlds are migrated forward on first load and cannot
be migrated back. A downgrade after an upgrade means restoring from a backup.

Update the `paper-api` dependency in lockstep so you compile against the API you're running.

### Performance

`tps` is the health metric — 20.0 means the server keeps up, and sustained drops mean it doesn't.

The biggest levers, roughly in order:

1. `simulation-distance` — how far entities and redstone actually tick. Cheaper to lower than view-distance and less noticeable.
2. `view-distance` — how much terrain is sent to clients.
3. `spigot.yml` → `entity-activation-range` — how close a player must be for mobs to act.
4. `bukkit.yml` → `spawn-limits` — caps on mobs per world.

When something is slow, profile rather than guess:

```
spark profiler start
   … let the lag happen …
spark profiler stop
```

It reports exactly which code consumed tick time — including your own plugin.

---

## Installing from scratch

Only needed when rebuilding on a new machine or upgrading Paper — the repo already contains a
working server.

**1. Find the current build.** Paper's API tells you the latest build for a given Minecraft version:

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
first pass Paper also downloads the vanilla Mojang server jar and applies its patches to it, which
is why `cache/`, `libraries/`, and `versions/` appear.

**5. Accept the EULA.** Open `eula.txt` and change `eula=false` to `eula=true`. Doing this is your
agreement to the [Minecraft EULA](https://aka.ms/MinecraftEULA), so read it first if you haven't.

**6. Start the server** with `./start.sh`. The first real startup generates the world, which takes
longer than subsequent boots.

Record the new version in `paper.env` so `start.sh` and both plugin projects stay in lockstep.

---

## Version control

The guiding principle is **version what you author, ignore what the server generates**. Everything
excluded is reproducible from a fresh run. After `git add .` only these are staged:

```
.gitignore
README.md
docs/
start.sh, stop.sh
paper.env
server.properties.example
plugins/.gitkeep
backups/.gitkeep
custom-plugins/            # source, build scripts, and the Gradle wrapper
sample-plugins/            # same, kept as reference
```

Plugin *source* is tracked; the *compiled* jar in `plugins/` is not — it's a build artifact, rebuilt
with `./gradlew deploy`. Same for `build/` and `.gradle/`.

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) **is** committed deliberately, so a fresh clone can
build without installing Gradle. The `.gitignore` needs `!**/gradle/wrapper/gradle-wrapper.jar`
rather than `!gradle/wrapper/...` for this — a pattern containing a slash is anchored to the repo
root and would miss the nested copies.

Notable exclusions and why:

| Excluded | Reason |
|---|---|
| `paper-*.jar` | 59 MB. GitHub warns above 50 MB, rejects at 100 MB. Re-download it — the build and checksum are above. |
| `server.properties` | **Contains secrets** — `management-server-secret` is auto-generated with a live value, and `rcon.password` if you enable RCON. |
| `current/` — the live world | Changes every tick and merges catastrophically. Back this up separately; git is the wrong tool for a world in active use. **Partial exception:** the archived realms inside it *are* tracked, see below. |
| `cache/`, `libraries/`, `versions/` | ~165 MB Paper downloads and patches on first run. |
| `ops.json`, `usercache.json`, `banned-ips.json` | Usernames, UUIDs, IP addresses. |
| `eula.txt` | Accepting the EULA is a personal legal act — let whoever runs the server accept it themselves. |
| `logs/`, `build/`, `.gradle/`, `.DS_Store` | Churn and local noise. |

### The archived realms are the one tracked world data

`old`, `oldest`, `oldest_nether`, and `oldest_the_end` are committed — 1.5 GB across 995 files.
They're finished history, so versioning them means a clone has the realms rather than them existing
only on one machine.

This is why `.gitignore` can't just say `current/`. Git will not re-include a path inside an
excluded directory, so each level is unignored on the way down:

```gitignore
current/*
!current/dimensions/
current/dimensions/*
!current/dimensions/minecraft/
current/dimensions/minecraft/*
!current/dimensions/minecraft/old/          # …and the other three
```

Two traps worth remembering if you add another realm:

- **Anchor the staging patterns.** `/old/` with a leading slash matches only the repo root. Written
  as `old/` it matches a directory of that name at *any* depth and silently re-ignores the archived
  realm — the tracked files just vanish from `git status` with no error.
- **Verify before committing**, since a mistake here is a 1.5 GB commit to undo:

  ```bash
  git check-ignore -q current/dimensions/minecraft/old/region/r.0.0.mca && echo IGNORED || echo tracked
  ```

The realms are set to adventure mode with mob spawning off in `plugins/Multiverse-Core/worlds.yml`,
so walking through them doesn't rewrite region files. That matters because `.mca` files are
internally compressed and git can't delta them — every rewritten region file is a whole new ~9 MB
blob in history, permanently.

Repo size is now ~876 MB. Every file is under GitHub's 100 MB hard limit, but the repo is past
GitHub's ~1 GB guidance, which is worth knowing before you add a remote.

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

---

## Troubleshooting by symptom

| Symptom | Look at |
|---|---|
| Server won't start | `logs/latest.log`, bottom first |
| `already locked` | A server is still running — `./stop.sh` |
| Plugin not loading | `logs/latest.log` — load failures always print a stack trace |
| Won't boot after an update | Delete `cache/`, `versions/`, `libraries/` and restart |
| Lag | `tps`, then `spark profiler start` |
| Player can't connect | Version mismatch, or `white-list=true` |
| Corrupt world after a crash | Restore your backup; `level.dat_old` can substitute for a damaged `level.dat` |

### In detail

**"Outdated server!" or "Outdated client!" when connecting**
Client and server versions don't match. The server is 26.2; set your Minecraft installation to 26.2
exactly.

**`UnsupportedClassVersionError` on startup**
Java is too old. Paper 26.1+ needs Java 25 or newer. Check with `java -version`.

**"Failed to bind to port"**
Something already holds 25565 — most likely a server you forgot to stop:

```bash
lsof -nP -iTCP:25565 -sTCP:LISTEN
```

**`Failed to start the minecraft server` … `session.lock: already locked`**
A server is *already running* and holding this world. Minecraft locks `current/session.lock` so two
processes can never write the same chunks — this error is the safety mechanism working, not a
corruption. The fix is one command:

```bash
./stop.sh && ./start.sh
```

`start.sh` catches this before Java even launches, so you should see a plain-English prompt rather
than a stack trace. If you're seeing the trace, the server was started some other way (`java -jar`
directly, or an old copy of the script).

To inspect it manually:

```bash
lsof -nP -iTCP:25565 -sTCP:LISTEN          # what's on the port
ps -eo pid,etime,command | grep [p]aper    # the server process
```

If it has a console, type `stop` there. Otherwise `kill -TERM <pid>` — Paper's shutdown hook runs on
SIGTERM and saves the world properly. Confirm it saved by looking for `All dimensions are saved` in
the log, then start again.

**Why this keeps happening:** a server started in a background window, or by a tool, has no console
you can type `stop` into — so it's easy to forget it's alive. Checking the port first is the quickest
guard.

If the lock persists with genuinely no server running (only after a hard crash or power loss), delete
`current/session.lock` — it's regenerated on startup. Never delete it to bypass a live server.

**`Failed to load eula.txt`**
`eula.txt` still says `eula=false`. See step 5 of *Installing from scratch* above.

**A plugin isn't loading**
Read `logs/latest.log`. Plugin load failures print a full stack trace and the reason is almost always
in it — usually a malformed `plugin.yml` or a version mismatch against the API.

**Server is lagging**
`spark` is already installed. Run `/spark profiler start` in game, let it collect during the lag,
then `/spark profiler stop` for a report showing exactly what's consuming tick time.

---

## References

- [Paper documentation](https://docs.papermc.io/)
- [Paper API javadocs](https://jd.papermc.io/paper/)
- [Paper plugin development guide](https://docs.papermc.io/paper/dev/)
- [Minecraft EULA](https://aka.ms/MinecraftEULA)
