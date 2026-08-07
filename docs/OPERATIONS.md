# Operations Guide

Everything about running and maintaining this server day to day: what each directory is for,
which config file controls what, and how to perform the common operations safely.

For setup and connecting, see the [README](../README.md).

---

## The one distinction that matters

Every file here falls into one of two categories:

| | |
|---|---|
| **Authored** — you write it, git tracks it | `start.sh`, `stop.sh`, `README.md`, `custom-plugins/` |
| **Generated** — the server creates it, reproducible | `cache/`, `libraries/`, `versions/`, `logs/`, `world/`, most configs |

The generated side is ~165 MB of downloads plus your world. Knowing which is which tells you what's
safe to delete when something breaks: **anything generated can be removed and will come back.** The
sole exception is `world/`, which is generated but irreplaceable — it holds everything players built.

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

### `world/` — the irreplaceable one

```
world/
├── level.dat              world metadata: seed, spawn point, game rules, world time
├── level.dat_old          automatic backup of the above
├── session.lock           the "a server is using this" lock
├── dimensions/minecraft/
│   ├── overworld/region/  the actual terrain, as .mca region files
│   ├── the_nether/
│   └── the_end/
├── data/                  maps, scoreboards, raids, villages
├── datapacks/             vanilla datapacks
└── players/               one file per player: inventory, position, health, XP
```

Note the modern layout — all three dimensions live *inside* `world/`. Older guides describe
sibling `world_nether/` and `world_the_end/` folders; that's no longer how it works, which makes
backup and reset simpler since one folder is the whole world.

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
| `world/dimensions/minecraft/<dim>/paper-world.yml` | Overrides the above for **one dimension** | Restart |
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
| Nether-only tweak | `world/dimensions/minecraft/the_nether/paper-world.yml` |
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
tar -czf backups/world-$(date +%Y%m%d-%H%M).tar.gz world/
./start.sh
```

**Hot** — server running, players connected. Suspend writes so you copy a consistent snapshot:

```
save-off
save-all
```

```bash
tar -czf backups/world-$(date +%Y%m%d-%H%M).tar.gz world/
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

**Wipe and regenerate with a new random seed:**

```bash
./stop.sh
rm -rf world/          # ⚠ permanent — back up first if unsure
./start.sh
```

The modern single-folder layout means this removes the nether and end too.

**Keep the old world, start a fresh one alongside it** — safer, and reversible:

```properties
# server.properties
level-name=world2
```

The old `world/` stays untouched on disk. Switch back by setting the name again.

**Generate a specific seed:**

```properties
level-name=world
level-seed=-1234567890      # or any text, which gets hashed
```

The seed only applies when the world is first created — setting it on an existing world does
nothing. Pair it with a delete or a new `level-name`.

To reset just *one* dimension (a common trick for refreshing depleted nether resources), delete
only `world/dimensions/minecraft/the_nether/` while the server is stopped.

### Update Paper to a new build

```bash
# 1. Find the current build
curl -s https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest

# 2. Download and verify the checksum (see README setup section)

# 3. Edit paper.env — the only place the version is declared
```

`start.sh` and both plugin projects' `build.gradle.kts` read `paper.env`, so there's nothing else to
change. If the jar named there isn't present, `start.sh` says so instead of failing obscurely.

Back up `world/` before a version upgrade — worlds are migrated forward on first load and cannot
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
