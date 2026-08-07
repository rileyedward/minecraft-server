# Creating a New Plugin

Two paths, depending on what you're building. Start with the first one — it's the right answer far
more often than people expect.

---

## Which path?

| | Add a feature | New standalone plugin |
|---|---|---|
| **Effort** | 2 files touched | A new Gradle project |
| **Ships as** | Part of `CustomPlugins.jar` | Its own jar |
| **Shares code** | Just call the method | Needs `depend:` + the services API |
| **Use when** | Almost always | It must run on a *different* server without the rest |

New work goes in as a feature of `custom-plugins/`. Split one out later if it earns it — the
feature boundary is already the seam, so waiting costs you nothing.

---

# Path A: Add a feature

Worked example: a `/spawn` command that teleports you to the world spawn.

## 1. Create the file

`custom-plugins/src/main/java/com/rileyedward/smp/features/spawn/SpawnFeature.java`

```java
package com.rileyedward.smp.features.spawn;

import com.rileyedward.smp.core.Feature;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnFeature implements Feature, BasicCommand {

    @Override
    public String id() {
        return "spawn";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        plugin.registerCommand("spawn", this);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text(
                    "Only players can teleport.", NamedTextColor.RED));
            return;
        }

        player.teleport(player.getWorld().getSpawnLocation());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.sendMessage(Component.text("Teleported to spawn.", NamedTextColor.GREEN));
    }
}
```

No `Listener` here — this feature has no event handlers, only a command. Implement only what you use.

Its own package (`features/spawn/`) even though it's one file. When the feature grows a listener, a
config class, or helpers, they have somewhere to live that isn't a shared dumping ground.

## 2. Register it

In `SmpPlugin.java`, add one line to `FEATURES`:

```java
private static final List<Supplier<Feature>> FEATURES = List.of(
        WelcomeFeature::new,
        SpawnFeature::new             // ← your new feature
);
```

Add the import at the top. That's the whole wiring.

## 3. Build and run

```bash
cd custom-plugins && ./gradlew deploy
cd .. && ./stop.sh && ./start.sh
```

Join and type `/spawn`.

Optionally add a toggle to `config.yml` — the key matches `id()`:

```yaml
features:
  spawn: true
```

Not required. Features default to enabled.

---

## Adding an event to it

Features commonly grow from one command into something larger. To add an event handler, implement
`Listener` and register:

```java
public final class SpawnFeature implements Feature, Listener, BasicCommand {

    @Override
    public void enable(JavaPlugin plugin) {
        plugin.registerCommand("spawn", this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);  // ← add this
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(event.getPlayer().getWorld().getSpawnLocation());
    }
}
```

Both registration lines are needed — one per capability. **Forgetting `registerEvents` is the most
common bug in plugin development**: the code compiles, the server starts clean, and your handler
simply never fires.

---

# Path B: A standalone plugin

Only when it genuinely needs to ship separately. It's four scaffolding files.

## 1. Directory

Alongside `custom-plugins/`, not inside `plugins/` — that folder is for compiled jars only:

```
minecraft-server/
├── plugins/           ← jars land here
├── custom-plugins/
└── my-plugin/         ← new
```

## 2. `my-plugin/settings.gradle.kts`

```kotlin
rootProject.name = "MyPlugin"
```

## 3. `my-plugin/build.gradle.kts`

```kotlin
plugins {
    java
}

group = "com.rileyedward"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // compileOnly, NOT implementation — the server already provides this.
    compileOnly("io.papermc.paper:paper-api:26.2.build.103-stable")  // or read ../paper.env
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)   // paper-api is Java 25 bytecode; cannot be lower
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("MyPlugin")
    archiveVersion.set("")    // stable filename so deploys overwrite
}

tasks.register<Copy>("deploy") {
    from(tasks.jar)
    into(layout.projectDirectory.dir("../plugins"))
}
```

## 4. `my-plugin/src/main/resources/plugin.yml`

The file that makes a jar a plugin:

```yaml
name: MyPlugin
version: '1.0.0'
main: com.rileyedward.myplugin.MyPlugin
api-version: '26.2'
description: Does something useful.
author: rileyedward
```

`name` must be unique across every plugin on the server, and `main` must exactly match your class's
full package path. A typo here means the jar loads and nothing happens.

## 5. `my-plugin/src/main/java/com/rileyedward/myplugin/MyPlugin.java`

```java
package com.rileyedward.myplugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("MyPlugin enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("MyPlugin disabled");
    }
}
```

## 6. Wrapper and build

```bash
cd my-plugin
gradle wrapper          # once — needs Gradle installed (brew install gradle)
./gradlew deploy
```

Then restart the server and look for `MyPlugin enabled` in the log.

Commit `gradlew` and `gradle/wrapper/` — that's what lets a fresh clone build without Gradle
installed. The `.gitignore` already handles this via `!**/gradle/wrapper/gradle-wrapper.jar`.

---

## The checklist

Whichever path, a plugin needs all of:

- [ ] A class extending `JavaPlugin`
- [ ] `plugin.yml` with `name`, `version`, `main`, `api-version`
- [ ] `main:` exactly matching the class's package path
- [ ] `compileOnly` on the Paper API — never `implementation`
- [ ] Java 25 target
- [ ] Every listener registered with `registerEvents`
- [ ] Every command registered with `registerCommand`
- [ ] The jar in `plugins/`, and a server **restart**

---

## When it doesn't work

| Symptom | Cause |
|---|---|
| Plugin not in `/plugins` list | Jar not in `plugins/`, or the server wasn't restarted |
| `Cannot find main class` | `main:` in plugin.yml doesn't match the real package path |
| Loads, but nothing happens | Missing `registerEvents` — the classic |
| `UnsupportedClassVersionError` | Compiled for the wrong Java version; needs 25 |
| Weird duplicate-class errors | Used `implementation` instead of `compileOnly` |
| Command "unknown" | `registerCommand` never called, or called outside `onEnable()` |

`logs/latest.log` has the answer for essentially all of these — load failures always print a full
stack trace, and the cause is usually in the first few lines.

---

## Iterating

```bash
cd custom-plugins && ./gradlew deploy && cd .. && ./stop.sh && ./start.sh
```

Restart every time. `/reload` exists, appears to work, and is a well-known source of bugs that
waste hours — plugins get re-instantiated while old listeners and tasks survive. Don't build the
habit.

---

## Next

- [Plugin Development Basics](plugin-development-basics.md) — the model
- [The Magic Wand, Explained](magic-wand-explained.md) — a full feature, line by line
- [Paper javadocs](https://jd.papermc.io/paper/) — browse `org.bukkit.event` for what you can hook
