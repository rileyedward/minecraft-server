# The Magic Wand, Explained

A complete walkthrough of
[`MagicWandSample.java`](../sample-plugins/src/main/java/com/rileyedward/samples/modules/MagicWandSample.java).

It's ~170 lines and touches most of what you'll use day to day: custom items, commands, events,
persistent data, the scheduler, particles, and sound. If you understand this file, you can read
almost any plugin.

New to this? Read [Plugin Development Basics](plugin-development-basics.md) first.

**What it does:** `/wand` gives you a glowing "Wand of Leaping". Right-click and you launch
forward, trailing particles.

---

## The class declaration

```java
public final class MagicWandSample implements SampleModule, Listener, BasicCommand {
```

Three interfaces, three jobs — this one object *is* the module, the listener, and the command:

| Interface | Purpose | Comparable to |
|---|---|---|
| `SampleModule` | Our own convention for enable/disable | Your own service interface |
| `Listener` | Marks it as holding event handlers | A Laravel listener class |
| `BasicCommand` | Makes it handle `/wand` | A controller action |

Combining them keeps a small feature in one file. For anything larger you'd split them apart —
a 500-line class doing all three is a mess.

`Listener` is a **marker interface**: zero methods. Its only job is to let the server accept the
object for registration. The `@EventHandler` annotations do the real work.

---

## Fields

```java
private JavaPlugin plugin;
private NamespacedKey wandKey;
```

Remember these live for the entire server uptime — nothing resets between events.

`NamespacedKey` is the important one. It's an identifier scoped to your plugin:

```
sampleplugins:is_magic_wand
└─ plugin ──┘ └─── key ───┘
```

Every piece of custom data you attach to anything needs one. The prefix guarantees your
`is_magic_wand` can never collide with another plugin's key of the same name — the same problem
namespaces solve in PHP.

---

## Setup

```java
@Override
public void enable(JavaPlugin plugin) {
    this.plugin = plugin;
    this.wandKey = new NamespacedKey(plugin, "is_magic_wand");

    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    plugin.registerCommand("wand", this);
}
```

Called once at startup, from `SamplesPlugin.onEnable()`.

**Why keep the `plugin` reference?** The scheduler needs it later. Every scheduled task must be
owned by a plugin so the server can cancel it if that plugin unloads. Without an owner, tasks
would outlive their code.

**`registerEvents(this, plugin)`** is the line that makes `@EventHandler` methods fire. Miss it and
the code is correct, compiles fine, and silently never runs. This is the single most common
beginner bug.

**`registerCommand("wand", this)`** wires `/wand` to this object. No `plugin.yml` entry needed.

---

## The command

```java
@Override
public void execute(CommandSourceStack source, String[] args) {
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text(
                "Only a player can hold a wand.", NamedTextColor.RED));
        return;
    }
    player.getInventory().addItem(createWand());
    player.sendMessage(Component.text(
            "Right-click to launch yourself.", NamedTextColor.LIGHT_PURPLE));
}
```

**The sender is not necessarily a player.** It could be the console or a command block. `getSender()`
returns a `CommandSender`; `Player` is a subtype. Skipping this check is a routine crash — the
console has no inventory to put a wand into.

`instanceof Player player` is Java's pattern matching: tests the type *and* binds the variable in
one step, scoped to where the test passed. If you've used PHP 8's `instanceof` plus a manual cast,
this is that, minus the cast.

Note the guard-clause shape — check the bad case, message, `return`. Same style you'd use in PHP.

---

## Building the item

This is the part worth reading twice, because it's how every custom item in Minecraft works.

```java
private ItemStack createWand() {
    ItemStack wand = new ItemStack(Material.BLAZE_ROD);

    wand.editMeta(meta -> {
        meta.displayName(Component.text("Wand of Leaping", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(
                Component.text("Right-click to launch forward.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        meta.setEnchantmentGlintOverride(true);

        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
    });

    return wand;
}
```

**It's a blaze rod.** You cannot invent a new item — the client only knows how to draw what ships
with the game. So you take a vanilla item and change everything *about* it. Every "custom item"
you've seen on a server is this trick.

**`editMeta`** takes a lambda, hands you the metadata, and writes it back after. `ItemMeta` holds
everything beyond "which item and how many" — name, lore, enchantments, custom data.

**`.decoration(TextDecoration.ITALIC, false)`** — item names render italic by default, the same
styling an anvil rename produces. Turning it off is the small detail that makes an item look
deliberately built rather than hand-renamed.

**Lore** is the grey descriptive text under the name in the tooltip.

**`setEnchantmentGlintOverride(true)`** gives the enchanted shimmer without an actual enchantment —
purely cosmetic, no gameplay effect.

### The line that matters most

```java
meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
```

This tags the item as *ours*, invisibly, in data that travels with the item forever — through
chests, drops, trades, and restarts.

**Never identify custom items by display name.** It's the classic beginner mistake:

```java
// BROKEN — do not do this
if (item.getItemMeta().getDisplayName().equals("Wand of Leaping")) { ... }
```

Any player with an anvil and a blaze rod can type that name and get a free wand. Display names are
user-editable input. PDC tags are not.

The value is `(byte) 1` because we only care whether the tag *exists*. If you later wanted wand
tiers, you'd store an integer level here instead.

---

## The interaction

```java
@EventHandler
public void onInteract(PlayerInteractEvent event) {
    if (!event.getAction().isRightClick()) {
        return;
    }

    ItemStack held = event.getItem();
    if (held == null || !isWand(held)) {
        return;
    }

    event.setCancelled(true);

    Player player = event.getPlayer();
    Vector launch = player.getLocation().getDirection().multiply(1.6).setY(0.8);
    player.setVelocity(launch);

    player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.4f);
    spawnTrail(player);
}
```

**`PlayerInteractEvent` fires constantly** — every click, both buttons, air or block, for every
player. So it bails out fast:

1. Not a right-click? Leave.
2. Nothing in hand, or not our wand? Leave.

That ordering is deliberate. Cheap checks first, because this runs on the tick thread many times
per second. Filtering early is how you avoid becoming the reason the server lags.

**`event.getItem()` can be null** — clicking with an empty hand. Java has no `?->`; the null check
is mandatory.

**`event.setCancelled(true)`** stops the *normal* right-click from also happening. Without it, the
wand would launch you *and* place a block or open the chest you're looking at.

### The physics

```java
Vector launch = player.getLocation().getDirection().multiply(1.6).setY(0.8);
player.setVelocity(launch);
```

`getDirection()` returns a **unit vector** — length 1, pointing where the player looks.

- `.multiply(1.6)` scales it to a strength
- `.setY(0.8)` forces an upward component, so looking at the floor still launches you up instead of into it

`setVelocity` applies it as momentum. Physics, fall damage, and collisions then behave normally.

Tune the two numbers to taste — `1.6` is distance, `0.8` is height.

**`playSound(location, sound, volume, pitch)`** — pitch above 1.0 raises it. The same sound at a
different pitch is often all you need for a distinct effect.

---

## Identifying the wand

```java
private boolean isWand(ItemStack item) {
    ItemMeta meta = item.getItemMeta();
    return meta != null
            && meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
}
```

Reads back the tag written in `createWand()`. `has()` only asks whether the key exists — we don't
care about the value.

---

## The particle trail

```java
private void spawnTrail(Player player) {
    new BukkitRunnable() {
        private int ticksElapsed = 0;

        @Override
        public void run() {
            if (ticksElapsed++ >= 20 || !player.isOnline()) {
                cancel();
                return;
            }
            player.getWorld().spawnParticle(
                    Particle.END_ROD,
                    player.getLocation().add(0, 0.5, 0),
                    6,
                    0.2, 0.2, 0.2
            );
        }
    }.runTaskTimer(plugin, 0L, 1L);
}
```

**This is where the single-threaded model becomes concrete.** You want particles for one second.
The PHP instinct is a loop with a sleep. That would freeze the entire server for a second — every
player, every mob, all of it.

Instead you hand the server a callback and it runs it between ticks.

**`runTaskTimer(plugin, 0L, 1L)`** — owned by `plugin`, start after 0 ticks, repeat every 1 tick.
**20 ticks = 1 second**, so this runs 20 times per second.

**`new BukkitRunnable() { ... }`** is an anonymous class — Java's version of an inline object. It
needs to be a class rather than a lambda because the body calls `cancel()` on itself, and it holds
state (`ticksElapsed`) across invocations.

### The stop condition is not optional

```java
if (ticksElapsed++ >= 20 || !player.isOnline()) {
    cancel();
    return;
}
```

A repeating task with no stop condition runs until the server shuts down. Worse, this one captures
`player` — so without the `isOnline()` check it would pin that player object in memory forever
after they disconnect, spawning particles at a ghost. That's a textbook leak, and the kind of thing
that only shows up after a server has been up for a week.

Two exits: elapsed, or the player left.

**`spawnParticle(type, location, count, offsetX, offsetY, offsetZ)`** — the offsets are a random
spread, giving a soft cloud rather than 6 particles stacked in one spot. `.add(0, 0.5, 0)` lifts
the origin from the player's feet to their middle.

---

## What to take from it

The pattern generalizes to nearly any feature:

1. **Register** in `enable()` — listeners, commands, keys
2. **Filter fast** in handlers — bail on the common case first
3. **Tag data** with `NamespacedKey` + PDC, never with display names
4. **Never block** — schedule instead
5. **Always define a stop condition** on repeating tasks
6. **Cancel the event** when replacing vanilla behavior
7. **Give feedback** — sound and particles are what make it feel real

---

## Things to try

Each is a small edit with an immediately visible result:

| Change | Where |
|---|---|
| Make it launch harder | `.multiply(1.6)` → `.multiply(3.0)` |
| Different particles | `Particle.END_ROD` → `FLAME`, `HAPPY_VILLAGER`, `ELECTRIC_SPARK` |
| Longer trail | `>= 20` → `>= 60` (three seconds) |
| Different item | `Material.BLAZE_ROD` → `STICK`, `NETHERITE_SWORD` |
| Left-click does something else | Add a branch on `event.getAction().isLeftClick()` |
| Cooldown | `Map<UUID, Long>` of last-use times, check before launching |
| No fall damage after a launch | Tag the player, listen for `EntityDamageEvent`, cancel `FALL` |

That last one is a genuinely good exercise — it needs two events cooperating through shared state,
which is most of what real plugin logic turns out to be.

Rebuild and test:

```bash
cd sample-plugins && ./gradlew deploy
./stop.sh && ./start.sh
```
