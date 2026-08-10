# Cost Optimization

Tuning this server so it runs comfortably on a smaller, cheaper droplet.

**Status: nothing here is applied yet.** Every setting below is still at its stock default. This is
the plan, the reasoning, and the measurements needed to know whether it worked.

Written to be picked up cold. [Baseline](#baseline-measured-2026-08-09) records what was actually
measured, so you shouldn't need to rediscover it — but configs drift, so re-check anything that
looks surprising.

Companion to [Deployment](DEPLOYMENT.md), which already covers droplet sizing, Java 25, the
firewall, systemd, and backups. This doc is only about *spending less*, and it makes that doc's
"modest view distance" note concrete.

---

## The thesis: tuning buys a smaller box, not a smaller bill

**DigitalOcean charges a flat rate per droplet whether it sits idle or runs pegged at 100%.** Nothing
you tune reduces an invoice directly. Halving CPU usage on an 8 GB droplet saves exactly $0.

Optimization saves money in one way only: by making the **next tier down** genuinely viable instead
of marginal.

| Tier | Heap (`-Xmx`) | Approx. cost | Verdict |
|---|---|---|---|
| 8 GB / 4 vCPU Premium | `6G` | ~$56/mo | Works today, untuned |
| **4 GB / 2 vCPU Premium** | **`2500M`** | **~$28/mo** | **The target.** Marginal untuned; comfortable tuned |

⚠ Prices are from August 2026 and DigitalOcean's tiers move. Confirm before committing.

So the goal of this document is one number: **~$28/mo instead of ~$56/mo**, for five players, without
the server feeling worse.

Two corollaries worth internalising, because they save wasted effort:

- **Idle optimisation is worthless here.** `pause-when-empty-seconds` is currently `-1` (never
  pause). Setting it makes the server quieter when empty but does not save a cent. Only change it if
  you want the headroom for something else.
- **Disk is not a constraint.** The whole server is 3.6 GB against 80 GB on the cheap tier. Don't
  spend effort shrinking it.

---

## Baseline (measured 2026-08-09)

Everything below was read off this repo, not estimated.

### Disk

| Path | Size |
|---|---|
| Whole server directory | **3.6 GB** |
| `current/` (all seven worlds) | 2.5 GB |
| `.git` | 896 MB |
| `libraries/` + `cache/` + `versions/` | 166 MB |
| `plugins/` | 41 MB |

### Runtime

| | |
|---|---|
| JVM flags | `exec java -Xms2G -Xmx4G -jar "$JAR" --nogui` — **no GC tuning at all** |
| Boot time | ~8 seconds to `Done` |
| Plugins | Chunky, LuckPerms, Multiverse-Core, Multiverse-Inventories, ViaVersion, Simple Voice Chat, spark, CustomPlugins |
| Always-loaded worlds | `current` + its nether and end. `old` / `oldest` are `auto-load: false`, so they cost nothing until visited |
| Once `/speedrun` runs | **Three more always-loaded worlds**, created `auto-load: true` |

### The settings that matter, all at stock defaults

| File | Setting | Current |
|---|---|---|
| `server.properties` | `view-distance` | `10` |
| `server.properties` | `simulation-distance` | `10` |
| `server.properties` | `entity-broadcast-range-percentage` | `100` |
| `server.properties` | `max-players` | `20` |
| `bukkit.yml` | `spawn-limits.monsters` | `70` |
| `spigot.yml` | `merge-radius.item` | `0.5` |
| `spigot.yml` | `merge-radius.exp` | `-1.0` (**disabled**) |
| `spigot.yml` | `entity-tracking-range.monsters` / `.animals` | `96` |
| `spigot.yml` | `entity-activation-range.monsters` / `.animals` | `32` |
| `spigot.yml` | `nerf-spawner-mobs` | `false` |
| `config/paper-world-defaults.yml` | `redstone-implementation` | `VANILLA` |
| `config/paper-world-defaults.yml` | `update-pathfinding-on-block-update` | `true` |
| `config/paper-world-defaults.yml` | `per-player-mob-spawns` | `true` (already the good value) |

⚠ **Not measured: actual heap usage or TPS under load.** Every "how much this saves" claim below is
reasoning, not observation. [Measure first](#measure-dont-guess) — the whole point of the spark
section is that these numbers are guesses until you check them.

---

## The one lever that dominates

`view-distance` and `simulation-distance`. Loaded chunks scale with the **square** of the radius —
`(2r + 1)²` per player:

| Setting | Chunks per player | vs. current |
|---|---|---|
| view 10 (current) | 441 | — |
| view 8 | 289 | −34% |
| view 7 | 225 | −49% |
| view 6 | 169 | −62% |
| sim 10 (current) | 441 ticking | — |
| sim 6 | 169 ticking | −62% |
| sim 5 | 121 ticking | **−73%** |
| sim 4 | 81 ticking | −82% |

The two do different jobs, and confusing them wastes the lever:

- **`view-distance` drives memory** — how many chunks are held and streamed to clients.
- **`simulation-distance` drives CPU** — how many chunks actually *tick*: entities, redstone, crop
  growth, mob spawning.

**Target: `view-distance=8`, `simulation-distance=5`.** Don't go below sim 5 — mob spawning happens
within simulation distance, so farms start misbehaving below it.

### Why this matters more here than on a normal server

Chunk loads overlap when players stand together, so five people around one base load far fewer than
5 × 441 chunks. The per-player figures above are the *spread-out* worst case.

**A speedrun is permanently the worst case.** People scatter within a minute — one dives the nether,
one hunts a village, one is off looking for blazes. This server will sit at close to worst-case chunk
load whenever it's being used for its most demanding feature, which makes this lever worth more here
than the generic advice implies.

---

## Free wins

Low risk, no perceptible gameplay change, worth doing once you've measured the big lever.

| File | Setting | Current → Target | Why |
|---|---|---|---|
| `config/paper-world-defaults.yml` | `redstone-implementation` | `VANILLA` → `ALTERNATE_CURRENT` | Paper's rewritten redstone dust propagation. Large win on any redstone, behaviour-preserving for normal builds |
| `config/paper-world-defaults.yml` | `update-pathfinding-on-block-update` | `true` → `false` | Mobs recalculate paths less eagerly. Real CPU saving, barely perceptible |
| `server.properties` | `entity-broadcast-range-percentage` | `100` → `50` | Halves entity movement packets. CPU and bandwidth |
| `spigot.yml` | `merge-radius.exp` | `-1.0` → `4.0` | **XP orb merging is currently switched off.** Orbs are notorious entity spam — this is close to free |
| `spigot.yml` | `merge-radius.item` | `0.5` → `3.0` | Fewer dropped-item entities |
| `bukkit.yml` | `spawn-limits.monsters` | `70` → `45` | `per-player-mob-spawns` is on, so this is 70 mobs *per player* |
| `spigot.yml` | `entity-tracking-range.monsters` / `.animals` | `96` → `64` | Less tracking work and bandwidth |
| `server.properties` | `max-players` | `20` → `8` | Matches reality. Minor, but free |

### Weigh these against gameplay first

| Setting | Change | Cost to you |
|---|---|---|
| `spigot.yml` `nerf-spawner-mobs` | `false` → `true` | Spawner mobs get no AI. Big win *if* you build mob farms; changes how those farms behave |
| `spigot.yml` `entity-activation-range` | monsters/animals `32` → `24` | Mobs further away stop ticking. Can make distant mobs look frozen |
| Drop ViaVersion | remove the jar | Only needed if friends run older Minecraft clients. Removing it drops a packet-translation layer |
| Drop Chunky | remove the jar | Only needed while pre-generating. Harmless to leave, one less plugin to load |

Keep spark. It costs almost nothing and it's how you'll answer every future version of this question.

---

## Add GC flags

`start.sh` runs a bare `java -jar` with no garbage-collection tuning. On a memory-constrained droplet
that leaves real performance unclaimed.

Two rules:

1. **Set `-Xms` equal to `-Xmx`.** Stops the JVM resizing the heap at runtime. On the 4 GB tier that
   means `-Xms2500M -Xmx2500M`.
2. **Generate a current G1 flag set** from the Paper documentation rather than copying an older blog
   post. Java 25 is new enough that widely-circulated flag lists predate it and some entries are
   stale or removed.

⚠ Note that `-XX:+AlwaysPreTouch` (part of the standard set) makes the JVM claim the whole heap at
startup, so resident memory jumps to the full `-Xmx` immediately. That's correct on a dedicated box
but it means the RAM must genuinely be there — it will look alarming in `htop` and be fine.

---

## Measure, don't guess

**spark is already installed.** Every percentage in this document is reasoning; spark produces facts.

```
# Before changing anything — capture a baseline while people are actually playing
/spark tps
/spark profiler start
   ... play 10 minutes, ideally with everyone spread out ...
/spark profiler stop
/spark heapsummary          # what is actually holding memory
```

Then change **view/simulation distance only**, re-measure, and go further only if the numbers say
you need to.

Changing twenty settings at once is how you end up with a server that feels wrong and no idea which
change did it. The order in [When you come back](#when-you-come-back) exists for this reason.

Worth capturing in the baseline, because they're what you're actually buying:

- **TPS under load** — should sit at 20. Anything below means the tick loop is behind.
- **ms per tick** — headroom is what matters, not the average. 50 ms is the budget.
- **Heap after a full GC** — the real memory floor, as opposed to whatever the heap has grown to.

---

## What's irreducible

Being honest about the ceiling, so nobody re-litigates this later:

- **World logic is single-threaded.** No configuration changes that. Faster single-core clock is the
  only fix, which is why [Deployment](DEPLOYMENT.md) recommends Premium Intel/AMD over more vCPUs.
  Folia multi-threads by region but targets hundreds of players and would break these plugins.
- **Chunk generation is expensive, full stop.** The `/speedrun` reset freeze will exist on any
  hardware. Faster cores shorten it; only the Phase 3 double-buffer in
  [Speedrun world](SPEEDRUN-WORLD.md) removes it — and it does so by *trading CPU for RAM*, which
  works against the goal of this document. Decide which you're optimising for before building it.
- **The JVM has a floor.** Paper 26.2 won't run well below roughly 2 GB of heap no matter what you
  tune, which is what makes the 4 GB droplet the bottom of the ladder rather than 2 GB.
- **Six loaded worlds cost more than one.** Once `/speedrun` has run, that's the baseline. See the
  RAM knob below.

---

## Where the settings live, and the trap

| File | Scope | Tracked in git? |
|---|---|---|
| `server.properties` | Server-wide | **No — gitignored** (holds `management-server-secret`) |
| `server.properties.example` | Documentation of the above | **Yes** |
| `bukkit.yml`, `spigot.yml` | Server-wide | No — gitignored |
| `config/paper-world-defaults.yml` | All worlds | No — gitignored |
| `current/dimensions/minecraft/<dim>/paper-world.yml` | **One dimension only** | No |

**The trap: none of the files you'll edit are tracked in git.** Tune the droplet and the settings
exist in exactly one place, undocumented, until something eats the disk. Two habits fix it:

1. Mirror every change into **`server.properties.example`**, which *is* tracked.
2. For `bukkit.yml` / `spigot.yml` / `config/`, note the deliberate deviations from default in this
   document as you make them. [Operations](OPERATIONS.md) explains why these are gitignored and how
   to un-ignore one you've deliberately tuned.

### A RAM knob specific to the speedrun feature

Multiverse creates the three speedrun worlds `auto-load: true`, so after a restart you hold six
loaded worlds for five players. If memory gets tight:

```
/mv modify speedrun set auto-load false
/mv modify speedrun_nether set auto-load false
/mv modify speedrun_the_end set auto-load false
```

`WorldSet.ensureCreated()` already loads them on demand, so the feature keeps working — the first
`/speedrun` of a session just pauses while they load. This is the same trade already made for `old`
and `oldest`.

---

## When you come back

Do these in order. Stop as soon as the numbers are good enough — every step after that is risk for
no reward.

1. **Baseline with spark** on the current 8 GB droplet, with people playing and spread out. Record
   TPS, ms/tick, and post-GC heap in this document. Without this, nothing below is measurable.
2. **`view-distance=8`, `simulation-distance=5`.** Re-measure. This alone may be the whole job.
3. **Apply the [free wins](#free-wins).** Re-measure.
4. **Add GC flags** with `-Xms` = `-Xmx`.
5. **Resize the droplet to 4 GB / 2 vCPU** and set `-Xmx2500M`. DigitalOcean resizes are reversible
   for RAM/CPU, so this is a safe experiment — take a snapshot first anyway.
6. **Play a full speedrun session on the smaller box**, including several deaths. This is the real
   test: resets are the heaviest thing this server does, and the reset freeze is the symptom to
   watch.
7. **Record the outcome here** — including if you resized back up. A failed experiment documented is
   worth more than the $28.

---

## Open questions

⚠ Unresolved. Each would change the plan.

1. **What does this server actually use?** Heap and TPS were never measured. It's possible the 4 GB
   tier works untuned and steps 2–4 are unnecessary.
2. **How long is the speedrun reset freeze on 2 vCPU?** Terrain generation is the single most
   CPU-bound thing here, and it's the one operation that might rule out the cheap tier on its own.
   Measure it before committing.
3. **Does `simulation-distance=5` hurt the speedrun?** Lower sim distance means less mob spawning
   around a scattered team. Probably fine, possibly makes early-game food and mob drops feel thin.
4. **Is Simple Voice Chat's bandwidth material?** It runs UDP on 24454 and was never measured.
   Almost certainly irrelevant against DigitalOcean's included transfer, but unverified.

---

## Sources

- Paper configuration reference — [`docs.papermc.io`](https://docs.papermc.io/paper/reference/configuration/)
  (`paper-world-defaults.yml`, `redstone-implementation`, per-world overrides)
- Paper anti-lag and tuning guide — [`docs.papermc.io`](https://docs.papermc.io/paper/misc/)
- spark — [`spark.lucko.me/docs`](https://spark.lucko.me/docs) (`/spark profiler`, `/spark heapsummary`)
- DigitalOcean droplet pricing — [`digitalocean.com/pricing/droplets`](https://www.digitalocean.com/pricing/droplets)
- This repo: [Deployment](DEPLOYMENT.md) for droplet setup and sizing,
  [Operations](OPERATIONS.md) for which config file does what,
  [Speedrun world](SPEEDRUN-WORLD.md) for why resets are the heaviest operation here
