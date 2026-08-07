# Plugin Development Basics

Written for someone who knows OOP well from PHP and is new to Minecraft plugins. Your instinct is
right — it *is* essentially a giant event system. The rest is understanding what's different about
the environment your code runs in, and that's where most of the surprises live.

---

## The mental model shift from PHP

This is the part worth reading carefully. Everything else follows from it.

| | PHP (web request) | Minecraft plugin |
|---|---|---|
| **Lifetime** | Milliseconds. Process dies at the end. | Days or weeks. Your objects stay alive. |
| **State** | Shared-nothing. Every request starts blank. | Everything persists in memory between events. |
| **Who calls whom** | You own entry (`index.php`) | The server owns the loop and calls *you* |
| **Concurrency** | Many requests in parallel, isolated | **One thread. Everything. Everyone.** |
| **Blocking** | `sleep(5)` stalls one visitor | `Thread.sleep(5000)` freezes the game for *everyone* |
| **Storage** | Database, from the first day | Often none — data attaches to game objects |

### Nothing resets

In PHP, forgetting to clean up a variable rarely matters — the process is about to die anyway. Here
a field on your plugin class is alive for the entire uptime of the server.

That's mostly a gift: caches actually stay cached, no session juggling, no re-fetching. It's also
how you leak. A `Map<Player, Something>` you never remove entries from will hold every player who
has *ever* connected, keeping their objects in memory long after they left.

### The server calls you, not the other way around

You never write the main loop. You register interest in things and wait. If you know Laravel, this
is exactly its event/listener split — `@EventHandler` is `Event::listen()`, and `onEnable()` is a
service provider's `boot()`.

The inversion is the same one you already know from a framework. It's just that the "requests"
are now *a player broke a block*, twenty times a second, forever.

### The single thread is the big one

Minecraft runs a **tick loop**: 20 ticks per second, one every 50 ms. Every block update, mob AI
step, redstone pulse, and plugin event handler runs inside one tick, on one thread, in sequence.

If your code takes 100 ms, the server misses two ticks. Players see rubber-banding and lag. If it
takes 5 seconds, the whole server freezes for 5 seconds. Every player. Simultaneously.

So the PHP habits that are merely *slow* become *fatal*:

```java
// Never do this in an event handler:
Thread.sleep(1000);              // freezes the entire server for a second
var result = database.query();   // blocks every player until the DB answers
httpClient.get("https://...");   // same, but worse — network latency
```

What you do instead:

- **Deferred work** → the scheduler (`runTaskLater`, `runTaskTimer`)
- **Slow I/O** → an async task, then hop back to the main thread to touch the game

The rule: **anything touching the world, players, or entities must run on the main thread.** Async
tasks are for the waiting, not for the acting.

`tps` in the console tells you if you're winning. 20.0 is healthy.

---

## What a plugin actually is

A `.jar` containing compiled classes and a `plugin.yml`. On startup, Paper scans `plugins/`, reads
each `plugin.yml`, finds the class named in `main:`, instantiates it, and calls `onEnable()`.

`plugin.yml` is the closest thing to `composer.json` — metadata plus an entry point:

```yaml
name: SamplePlugins
version: '1.0.0'
main: com.rileyedward.samples.SamplesPlugin   # ← the class to load
api-version: '26.2'
```

Get this file wrong and the server ignores your jar entirely, usually with a one-line log message
that's easy to miss.

### The lifecycle

```
Server starts
   ↓
onLoad()      rarely used — before worlds exist
   ↓
onEnable()    ← you register everything here
   ↓
… the server runs for days, calling your handlers …
   ↓
onDisable()   save anything unsaved
```

You never construct your plugin class. The server owns it — like a framework instantiating a
controller.

---

## Events: the core of everything

An event is a class. You write a method taking it as a parameter, annotate it, and register the
object.

```java
public class MyListener implements Listener {

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        player.sendMessage("You broke something!");
    }
}
```

Three things make this work, and missing any one produces silence rather than an error:

1. The class implements `Listener` (a marker interface — no methods)
2. The method has `@EventHandler`
3. **The object is registered:** `getServer().getPluginManager().registerEvents(new MyListener(), this)`

Number 3 is the one people miss. Correct code, never wired up, no error message.

The method name is irrelevant. **The parameter type is what determines which event you receive** —
`BlockBreakEvent` means block breaks. Rename the method to `onBanana` and it still works.

### Cancelling

Many events are cancellable, which is where plugins stop *observing* and start *deciding*:

```java
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    if (event.getBlock().getType() == Material.BEDROCK) {
        event.setCancelled(true);   // the block does not break
    }
}
```

The server fires the event *before* acting. Cancelling means it never acts. This is how protection
plugins, anti-grief, and custom rules all work.

### Priority

When several plugins listen to one event, priority sets the order:

```
LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR
```

`MONITOR` runs last and means "I only want to observe the final outcome." Don't change anything
there — by convention nothing after it will see your change.

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
```

`ignoreCancelled = true` skips your handler if someone already cancelled it. Usually what you want
when observing.

### Finding events

There are hundreds. Browse [`org.bukkit.event`](https://jd.papermc.io/paper/) in the javadocs —
`org.bukkit.event.player`, `.block`, `.entity`, `.inventory` are the busy packages. The naming is
predictable: `PlayerJoinEvent`, `EntityDamageEvent`, `InventoryClickEvent`.

---

## Storing data

There's no database and no ORM. Three options, in increasing order of effort:

**1. `config.yml`** — settings, not per-player data. Like a `.env` or config file.

```java
boolean enabled = getConfig().getBoolean("samples.magic-wand", true);
```

**2. PersistentDataContainer (PDC)** — arbitrary key-value data attached directly to a player,
entity, item, or block. The server saves it for you.

```java
NamespacedKey key = new NamespacedKey(plugin, "blocks_broken");
player.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 42);
```

Perfect for per-object data. Its limit is that you can only read it from an object you already have
loaded — there's no "query all players where blocks_broken > 100."

**3. A real database** — when you need queries across everything. JDBC works normally. Just run it
async and never on the tick thread.

A `NamespacedKey` is `plugin-name:key-name`. The prefix means your `blocks_broken` can never
collide with another plugin's.

---

## Doing things over time

You cannot sleep. You schedule.

```java
// Once, after 5 seconds (100 ticks)
Bukkit.getScheduler().runTaskLater(plugin, () -> {
    player.sendMessage("Five seconds later!");
}, 100L);

// Repeatedly, every second, starting now
Bukkit.getScheduler().runTaskTimer(plugin, () -> {
    player.sendMessage("tick");
}, 0L, 20L);
```

**Timings are in ticks: 20 ticks = 1 second.**

A repeating task runs until you cancel it or the plugin disables. Always define a stop condition —
see the wand's particle trail for the pattern.

---

## Commands

Modern Paper registers commands in code:

```java
public class HealCommand implements BasicCommand {
    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (source.getSender() instanceof Player player) {
            player.setHealth(20.0);
        }
    }
}

// in onEnable():
registerCommand("heal", new HealCommand());
```

`args` excludes the command name, so `/heal Notch` arrives as `["Notch"]`.

Always check who sent it. `getSender()` may be a player, the console, or a command block — assuming
it's a player is a common crash.

Older tutorials declare a `commands:` block in `plugin.yml` and implement `onCommand`. That still
works; the code approach keeps the definition next to the implementation.

---

## Text

Minecraft uses **Adventure Components** — objects, not strings:

```java
player.sendMessage(
    Component.text("Hello ", NamedTextColor.GRAY)
        .append(Component.text(player.getName(), NamedTextColor.AQUA))
);
```

You'll see `"§aGreen text"` in older guides. That's the legacy format — avoid it.

Where text can go: `sendMessage` (chat), `sendActionBar` (above the hotbar), `showTitle` (big
center-screen), and `Bukkit.broadcast` (everyone).

---

## The client is vanilla — what that means

Plugins are **server-side only**. Players connect with an unmodified client and install nothing.
That's the great strength, and it draws a hard line:

**You can:** change any rule, add commands, custom items with custom behavior, GUIs built from
inventory windows, particles, sounds, mob behavior, teleporting, world edits.

**You cannot:** add new blocks, new entities, new rendering, or a new UI the client doesn't already
know how to draw.

Custom content is made by *repurposing* vanilla: a renamed, tagged, retextured item; an armor stand
posing as a decoration; an inventory window acting as a menu. The wand is exactly this — a blaze
rod that isn't a blaze rod.

---

## Common mistakes

| Mistake | What happens |
|---|---|
| Forgot `registerEvents` | Handler never fires. No error. |
| Blocking on the main thread | Whole server freezes |
| Identifying custom items by display name | Anyone with an anvil can forge one |
| Assuming `getSender()` is a Player | Crash when run from console |
| Repeating task with no stop condition | Runs until shutdown, leaks memory |
| `implementation` instead of `compileOnly` for the API | Bundles a duplicate Bukkit, breaks confusingly |
| Using `/reload` | Bizarre, unreproducible bugs. Restart instead. |

---

## Where to go next

1. **[The Magic Wand, Explained](magic-wand-explained.md)** — one complete plugin, line by line
2. **[Creating a New Plugin](creating-a-new-plugin.md)** — build your own
3. **[Paper javadocs](https://jd.papermc.io/paper/)** — the API reference you'll live in
4. **[Paper dev docs](https://docs.papermc.io/paper/dev/)** — official guides
