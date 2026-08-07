# Plugin Ideas

A backlog for this server. Each entry names the event or API it hangs off, so an idea you pick up
has an obvious first line of code.

Everything here is possible with a **vanilla client** — no mods for anyone connecting.

---

## Two things before you start

### Tree Feller and Vein Miner are the same plugin

Both are: *break one block → find all connected blocks of a matching type → break those too.*
Chopping a tree and chain-mining an ore vein differ only in which materials count and where the
search stops.

Write one utility — a connected-block scanner with a max-size cap — and both features become thin
wrappers over it. It'll also cover chain-harvesting bamboo, sugar cane, glowstone, and anything
else you want later.

Doing it as two separate implementations is the most common way this ends up as duplicated,
subtly-divergent code.

### Check vanilla first

Vanilla has absorbed a lot of what used to need plugins. **Sleep voting is now a gamerule:**

```
/gamerule playersSleepingPercentage 50
```

Before building anything, check whether a gamerule or datapack already does it. Others worth
knowing: `keepInventory`, `doFireTick`, `mobGriefing`, `doInsomnia`, `randomTickSpeed`.

---

## Your two, with the traps

### Road Builder

A wand or boots that pave beneath you as you walk.

**Hangs off:** `PlayerMoveEvent`, `block.setType()`

**The trap:** `PlayerMoveEvent` fires on *every* movement packet — several times per second per
player, including pure head-turns. Running your logic on all of them is a real performance problem
on a busy server.

Filter immediately on whether the *block* position changed:

```java
if (event.getFrom().getBlockX() == event.getTo().getBlockX()
    && event.getFrom().getBlockY() == event.getTo().getBlockY()
    && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
    return;   // same block — nothing to do
}
```

Other decisions worth making up front: only replace air/grass/water (never someone's build),
consume real blocks from the player's inventory, and cap the width. Frost Walker's model — a
temporary ring that reverts — is worth considering over permanent placement, since permanent paving
by accident is griefing.

### Tree Feller

Break one log, the whole tree comes down.

**Hangs off:** `BlockBreakEvent`, plus the shared scanner above

**The traps:**

- **Cap the search.** A jungle tree is huge, and a player-built log cabin is bigger. Without a hard limit (say 256 blocks) someone chain-breaks half a build and the server hitches while it processes.
- **Require an axe**, and check the player isn't sneaking — sneak is the standard "just this one block" modifier.
- **Durability.** Free tree removal that costs one durability point makes axes near-immortal. Charge per log.
- **Only fell natural trees** if you can — a leaves-attached check is the usual heuristic for separating trees from buildings.

---

## Quality of life

The highest value-per-line category on an SMP.

| Idea | What | Hangs off |
|---|---|---|
| **Homes** | `/sethome`, `/home`, `/delhome`. The single most-missed feature on a fresh server. | Commands + PDC or a config file |
| **/back** | Return to where you died or last teleported from. | `PlayerDeathEvent`, teleport tracking |
| **Graves** | Death drops go into a marked container only you can open, instead of scattering. | `PlayerDeathEvent`, `BlockDisplay` or a chest + PDC owner tag |
| **Death coordinates** | A clickable chat message with where you died. | `PlayerDeathEvent` + Adventure click events |
| **/craft, /ec, /trash** | Portable crafting table, ender chest, and a disposal window. Three near-one-liners. | `Bukkit.createInventory`, `openInventory` |
| **Auto-replant** | Right-click a mature crop to harvest and replant in one action. | `PlayerInteractEvent`, `Ageable` block data |
| **Shulker preview** | Open a shulker box from inside your inventory without placing it. | `InventoryClickEvent` |
| **Chest sorting** | A button or command that sorts a container. | `InventoryClickEvent` |
| **Vein miner** | Chain-mine ore. Same scanner as Tree Feller. | `BlockBreakEvent` |
| **AFK indicator** | Mark idle players in the tab list; optionally exempt them from sleep percentage. | `PlayerMoveEvent` + scheduler |

---

## Travel and movement

An SMP lives or dies on how annoying it is to get around.

| Idea | What | Hangs off |
|---|---|---|
| **Elevators** | Stand on a marked block, jump to rise to the next one above. Delightful, ~60 lines. | `PlayerMoveEvent` / jump detection |
| **Waystones** | Craftable teleport nodes you must physically discover before using. Rewards exploration instead of skipping it. | `PlayerInteractEvent`, PDC on blocks |
| **/tpa** | Teleport requests with accept/deny and a timeout. | Commands + a `Map<UUID, Request>` |
| **/rtp** | Random teleport into unexplored land. Watch for chunk-generation lag — pre-generate or load async. | Commands, `World.getHighestBlockAt` |
| **Compass tracking** | Right-click a compass to point at a player or waypoint. | `PlayerInteractEvent`, `setCompassTarget` |
| **Grappling hook** | Your wand, but it pulls you *toward* where you look. | `ProjectileHitEvent` + velocity |

**Elevators are the sleeper pick** — small, immediately satisfying, and the first thing every player
tries to show someone else.

---

## Fun and flavor

| Idea | What | Hangs off |
|---|---|---|
| **Custom enchantments** | A "Smelting" pickaxe that drops ingots instead of ore; "Magnet" that auto-collects. Tag with PDC, implement in `BlockBreakEvent`. | PDC + events |
| **Mob heads** | A drop chance for mob heads as trophies. | `EntityDeathEvent` |
| **Player heads on death** | The killer receives the victim's head. Very SMP. | `PlayerDeathEvent` |
| **Custom recipes** | Make normally-uncraftable things craftable. Pure API, no events. | `ShapedRecipe`, `Bukkit.addRecipe` |
| **World events** | Scheduled meteor showers, mob sieges, loot drops at random coordinates. | `runTaskTimer` |
| **Expanding border** | The world border grows on a schedule or on milestones — turns exploration into progression. | `World.getWorldBorder()` |
| **Bounties** | Put a reward on a player's head, paid to whoever collects. | `PlayerDeathEvent` + storage |
| **Holographic leaderboards** | Floating text showing top miners/builders, fed by your existing stats module. | `TextDisplay` entity |

`TextDisplay` is the modern way to do floating text — real entities with proper text rendering.
Older guides use invisible armor stands with custom names; that still works but looks worse and
costs more.

---

## Bigger projects

Worth doing, but go in knowing the scope.

| Idea | Why it's big |
|---|---|
| **Land claims** | You must intercept every way a block can change — breaking, placing, pistons, explosions, fire, water, endermen, mobs. Dozens of events, and the ones you miss become the griefing exploits. |
| **Economy + shops** | Needs real storage, transaction safety, and a UI. Two plugins pretending to be one. |
| **Minigames** | Arena state, queueing, per-game inventories, resets, edge cases when someone logs out mid-round. |
| **Custom mobs** | No new entity types on a vanilla client. You reskin existing mobs with attributes, equipment, and AI overrides. Convincing results take real work. |
| **Quests/dialogue** | Storage plus UI plus state machines. Rewarding, but a project, not a weekend. |

---

## What you genuinely cannot do

Worth knowing before designing around it. The client is vanilla, so:

- **No new blocks, items, or entities.** Only reskinned existing ones.
- **No new GUI types.** Inventory windows are your UI vocabulary — chests, hoppers, anvils. They're more flexible than they sound.
- **No new rendering.** No custom shaders or models without a resource pack, and a resource pack is a separate opt-in download.
- **No client-side keybinds.** You detect existing actions: sneak, jump, swap-hands, drop. That's why swap-hands is such a common "ability" trigger.

Resource packs lift the visual limits (custom item models are how "custom items" look custom), but
they're a client download and a separate project.

---

## A suggested order

1. **Elevators** — small, self-contained, instantly gratifying
2. **Homes + /back** — teaches persistence properly, and you'll use it daily
3. **The connected-block scanner** — then Tree Feller and Vein Miner on top of it
4. **Road Builder** — teaches the `PlayerMoveEvent` filtering discipline
5. **Graves** — combines death events, custom blocks, and ownership
6. **Custom enchantments** — the PDC pattern from the wand, generalized
7. **Waystones** — persistence + discovery + teleport, a real feature
8. Then pick a big one

Roughly increasing difficulty, and each reuses something from the last.

---

## Related

- [Plugin Development Basics](plugin-development-basics.md)
- [The Magic Wand, Explained](magic-wand-explained.md) — the PDC tagging pattern most of these need
- [Creating a New Plugin](creating-a-new-plugin.md)
- [Paper javadocs](https://jd.papermc.io/paper/) — browse `org.bukkit.event` for what's hookable
