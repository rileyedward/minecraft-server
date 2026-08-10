# Hardcore Speedrun World

Design notes for a resettable, team-hardcore speedrun dimension. Nothing here is built yet — this
is the shape of the feature, the constraints it has to live inside, and the order to build it in.

Written to be picked up cold: everything a fresh session needs to know about this server is in the
[Ground truth](#ground-truth) section, so you shouldn't need to rediscover it.

---

## What we want

A `/speedrun` world that you and friends jump into for Minecraft speedruns, with two rules:

1. **If anyone dies, the run is over for everyone** — the world resets and a fresh one begins.
2. **Resetting is a command, not a chore.** No editing configs, no restarting the server.

---

## The headline: no server restart needed

Worlds can be created, unloaded, and deleted entirely at runtime through the Bukkit API and
Multiverse. Nothing about this requires bouncing the server or a separate instance.

That was the main open question, and the answer is the easy one. The genuine difficulty is
everywhere else.

---

## Ground truth

Facts about this server that shape the design. Verify anything marked ⚠ before relying on it.

| | |
|---|---|
| Server | PaperMC **26.2 build 103**, bare metal, repo root *is* the server directory |
| Java | 25+ (hard floor — `paper-api` is compiled to Java 25 bytecode) |
| Version pin | `paper.env` — read by both `start.sh` and `custom-plugins/build.gradle.kts` |
| Heap | `-Xms2G -Xmx4G` in `start.sh`, on an **8 GB** machine. This is the real constraint. |
| Multiverse-Core | 5.7.3 |
| Multiverse-Inventories | 5.3.5 |
| Chunky | 1.5.3 — already installed, and the tool for background pre-generation |
| Permissions | LuckPerms. This plugin's convention is `smp.<feature>`; ops pass automatically. |
| Default world | `level-name=current` |

**Where worlds live.** On Paper 26.2 every world is a *dimension inside the default world's folder*,
not a sibling directory:

```
current/dimensions/minecraft/{overworld,the_nether,the_end,old,oldest,…}
```

So a new `speedrun` world lands at `current/dimensions/minecraft/speedrun/`. This surprises
everyone; see the README's Worlds section and `OPERATIONS.md`.

**Multiverse environment names** must be one of `NORMAL`, `NETHER`, `THE_END`, `CUSTOM`. Lowercase
`end` fails with an unhelpful error — this cost time once already.

**Multiverse naming convention** (`plugins/Multiverse-Core/config.yml`) is what makes portals link:

```yaml
world-name-format:
  nether: '%overworld%_nether'
  end: '%overworld%_the_end'
```

So the set must be named `speedrun`, `speedrun_nether`, `speedrun_the_end` for portals to work.

`enforce-gamemode: true` is already set, so per-world gamemode is applied on entry.

### The plugin pattern to follow

`custom-plugins/` is a Gradle project. Build and install with `./gradlew deploy`, then restart.

- Features implement `com.rileyedward.smp.core.Feature` (`id()`, `enable(JavaPlugin)`, `disable()`).
- Each lives in its own package under `features/`, and is registered by adding one line to the
  `FEATURES` list in `SmpPlugin.java`.
- A `<id>: true` toggle goes in `src/main/resources/config.yml`.
- Commands are registered **in code** — `plugin.registerCommand("name", basicCommand)` using Paper's
  `io.papermc.paper.command.brigadier.BasicCommand`. They are *not* declared in `plugin.yml`.

**Closest precedent: `features/worlds/WorldsFeature.java`.** It registers three commands, holds the
target world as instance state (because `BasicCommand.execute` isn't told which name invoked it),
loads a world on demand, and teleports with `teleportAsync`. Read it first — this feature is that
one with a lifecycle attached.

---

## The three complications

### 1. It's three worlds, not one

A speedrun needs the nether (bastion, fortress) and the end (stronghold, dragon). `/mv create
speedrun NORMAL` creates only the overworld.

So "a run" is a **set of three worlds**, and every reset tears down and rebuilds all three. This is
the single biggest source of scope in the feature — the lifecycle code is inherently three-at-a-time,
and a half-deleted set is the most likely way to end up in a broken state.

### 2. Hardcore isn't per-world

`hardcore=true` in `server.properties` is **server-wide**. Turning it on would make `current` and
the archived realms hardcore too. There is no per-world hardcore flag in Bukkit.

You don't want it anyway. Vanilla hardcore sends *the dead player* to spectator. You want *everyone's
run* to end. That's custom logic either way, so emulate it:

- Per-world `difficulty: hard` (Multiverse supports this per world).
- A `PlayerDeathEvent` listener scoped to the speedrun set that triggers the team reset.
- Optionally `player.setHealthScale(...)` or a resource-pack-free hearts tweak for flavor — cosmetic,
  skip for v1.

### 3. The reset freeze, and the trick that fixes it

The naive reset — delete three worlds, generate three new ones — **blocks the main thread** while
terrain generates. A multi-second freeze on every death, which on a speedrun server is constant.

**Double-buffer it.** Always keep a spare set pre-generated:

```
speedrun_a   ← being played
speedrun_b   ← already generated, warm, waiting
```

On death: teleport everyone into `speedrun_b` (instant), then delete `speedrun_a` and pre-generate a
fresh spare in the background. Resets feel immediate because generation happens during play, not
during the reset.

Chunky is installed for exactly this — pre-generating a spawn radius in the background.

Naming note: the double-buffer means world names alternate, so `world-name-format` gives you
`speedrun_a` / `speedrun_a_nether` / `speedrun_a_the_end`. Keep the suffix scheme simple and derive
all three names from one base string in code.

---

## Architecture

One `SpeedrunFeature` in `custom-plugins/src/main/java/com/rileyedward/smp/features/speedrun/`,
probably split into:

| Class | Responsibility |
|---|---|
| `SpeedrunFeature` | `Feature` impl — wires commands and the death listener |
| `RunManager` | The state machine: current run, spare set, reset orchestration |
| `WorldSetLifecycle` | Create / load / unload / delete a named trio of worlds |
| `PlayerScrub` | Resetting a player to a legal run-start state |

### World lifecycle: prefer dispatching Multiverse commands

`WorldsFeature` already dispatches `mv load <name>` rather than calling `WorldCreator` directly, so
that Multiverse stays the single owner of world state. Follow that precedent:

```java
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv create " + name + " NORMAL --seed " + seed);
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + name);
```

Loading a world behind Multiverse's back leaves its `worlds.yml` and the running server disagreeing.

If `mv delete` proves too slow or too chatty, the Bukkit fallback is `Bukkit.unloadWorld(world,
false)` followed by a recursive directory delete — but **capture `world.getWorldFolder()` before
unloading**, and ⚠ verify what that returns on 26.2 given the dimensions layout. Do not hardcode
`current/dimensions/minecraft/<name>/`; ask the API.

Whichever path: **every player must be teleported out before unload.** Unloading a world with
players in it fails or strands them.

### Reset state machine

```
IDLE ──/speedrun──▶ RUNNING ──death or /speedrun reset──▶ RESETTING ──▶ RUNNING
                                                              │
                                                  (swap to spare, scrub players,
                                                   delete old set, pre-gen new spare)
```

Guard `RESETTING` against re-entry. Two players dying in the same tick must trigger **one** reset —
this is the most likely real bug in the whole feature.

### Player scrub checklist

A run isn't legitimate if state leaks across resets. On entering a fresh run, for each player:

- Inventory and ender chest — cleared
- XP and level — zeroed
- Health, hunger, saturation — full
- Potion effects — cleared
- Fire ticks, fall distance — cleared
- Gamemode — survival
- Spawn point — the new world's spawn
- Statistics — optional, but they're how you'd time the run
- **Advancements** — cleared, and easy to forget:

```java
Iterator<Advancement> it = Bukkit.advancementIterator();
while (it.hasNext()) {
    AdvancementProgress p = player.getAdvancementProgress(it.next());
    for (String criterion : p.getAwardedCriteria()) p.revokeCriteria(criterion);
}
```

### Inventories

Give the speedrun set **its own Multiverse-Inventories group** in
`plugins/Multiverse-Inventories/groups.yml`, alongside the existing `default` / `oldest` / `old`
groups. Without it, gear leaks between your survival realm and the run.

⚠ Open question: whether MV-Inventories copes gracefully with a world that is deleted and recreated
under a new name on every reset. Worth testing early — if it holds stale data per world name, the
scrub step has to compensate. This is the highest-risk unknown in the design.

---

## Commands

| Command | Does |
|---|---|
| `/speedrun` | Join the current run |
| `/speedrun leave` | Return to `/current` |
| `/speedrun reset` | Manual reset with a fresh random seed |
| `/speedrun seed <seed>` | Start a set-seed run |
| `/speedrun status` | Current seed, elapsed time, who's in |

Permission `smp.speedrun`, matching the `smp.*` convention. Ops pass automatically; open it up with
`/lp group default permission set smp.speedrun true`.

`BasicCommand` gives you `suggest()` for tab completion of the subcommands — worth wiring, since
`/speedrun reset` being one typo from `/speedrun` is a bad time.

---

## Build order

**Phase 1 — prove the format is fun (~2 hours).** Overworld only, blocking reset, no
double-buffering, no pre-generation. `/speedrun`, `/speedrun reset`, death listener, player scrub.
Accept the freeze. This answers "do we actually enjoy this?" before any of the hard work, and it's
genuinely usable on its own.

**Phase 2 — make it a real speedrun.** Add `speedrun_nether` and `speedrun_the_end` to the set,
verify portal linking through the naming convention, add the MV-Inventories group, add
`/speedrun seed`.

**Phase 3 — make it feel good.** Double-buffering with a warm spare, Chunky background
pre-generation, run timer, personal-best tracking, broadcast on death with the cause and who did it.

Resist doing 3 before 1. The freeze is annoying but not blocking, and Phase 1 is where you learn
whether the whole idea earns its keep.

---

## Constraints and gotchas

- **RAM is the real ceiling.** 8 GB machine, 4 GB heap, already hosting `current` plus three
  archived realms. Adding three live speedrun worlds *plus* a pre-generated spare set is six extra
  worlds. Fresh worlds are small, and `old`/`oldest` are `auto-load: false` so they cost nothing
  until visited — but do not raise `-Xmx` past 4G to compensate, that starves macOS. If it's tight,
  drop the double-buffer's nether and end and pre-generate only the overworld.
- **`.gitignore` needs a rule.** New worlds land inside `current/`, which uses fussy nested
  unignore rules so the archived realms stay tracked. A `speedrun*` world must **not** get caught by
  those. Check with `git check-ignore -q <path>` before the first commit after building this —
  silently committing a regenerating world would be miserable.
- **Disk churn.** Every abandoned run leaves a world folder until deleted. Make deletion part of the
  reset path, not a cleanup task someone remembers to run.
- **Seeds.** Random per reset by default. Record the seed of each run so a good one can be replayed
  with `/speedrun seed`.
- **Deaths in the nether or end** must trigger the reset too — scope the listener to the whole world
  set, not just the overworld. Easy to miss.
- **Don't delete the default world.** `current` can never be unloaded at runtime; the speedrun set
  is always separate. Guard the delete path against ever being handed `current`.
- **Where players land on reset** — spawn of the new world, and make sure they aren't left in
  spectator or with a bed spawn pointing at a deleted world.

---

## Settle these before building

1. **Death scope** — does *any* death reset the run, or only deaths of players currently in the
   speedrun world? (Someone dying in `current` shouldn't end a run.)
2. **Grace period** — instant reset on death, or a countdown with a chance to see what happened?
3. **Who can reset** — anyone in the run, or ops only?
4. **Run goal** — dragon kill, or just "survive and go fast"? Determines the win condition and
   whether there's a completion event to celebrate.
5. **Timer** — real time, or in-game ticks? Speedruns conventionally use real time from world
   creation to the dragon's death.

---

## Verification

1. `cd custom-plugins && ./gradlew deploy`, restart, confirm the feature count in
   `logs/latest.log` goes up and no exception is thrown on enable.
2. `/speedrun` — you're teleported into a fresh world, survival, empty inventory, no advancements.
3. `/mv list` — the speedrun set appears; the archived realms are untouched.
4. Die on purpose. The run resets, everyone in the run moves to a new world, inventories are clear.
5. Have two players die in the same tick — confirm **one** reset happens, not two.
6. `/speedrun leave`, then `/current` — your survival inventory is intact and unaffected.
7. `git status` — no world data staged.
8. Run several resets in a row, then check `du -sh current/dimensions/minecraft/` to confirm old
   run folders are actually being deleted rather than accumulating.

---

## Sources

- Paper API — [`docs.papermc.io`](https://docs.papermc.io/paper/dev/) (`BasicCommand`, `WorldCreator`,
  `Bukkit.unloadWorld`, `advancementIterator`)
- Multiverse-Core commands — [`docs.mvplugins.org`](https://docs.mvplugins.org/)
- Chunky pre-generation — [`modrinth.com/plugin/chunky`](https://modrinth.com/plugin/chunky)
- This repo: `docs/creating-a-new-plugin.md` for the feature workflow,
  `docs/plugin-development-basics.md` for the Paper API from scratch
