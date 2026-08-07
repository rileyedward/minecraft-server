package com.rileyedward.samples.modules;

import com.rileyedward.samples.SampleModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║ [SAMPLE] — safe to delete                                                ║
 * ║                                                                          ║
 * ║ Concept: REPLACING VANILLA BEHAVIOR, not just adding to it.              ║
 * ║                                                                          ║
 * ║ Some events carry a value the server is *about* to use. Overwrite it and ║
 * ║ the server uses yours instead. Here: the join and quit messages.         ║
 * ║                                                                          ║
 * ║ To remove: delete this file and its line in SamplesPlugin.MODULES.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * <p>This is the fastest sample to confirm — it fires the instant you connect.
 */
public final class WelcomeSample implements SampleModule, Listener {

    @Override
    public String id() {
        return "welcome";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // hasPlayedBefore() is false only on a player's very first connection ever.
        // The server tracks this in world/players/<uuid>.dat.
        boolean firstTime = !player.hasPlayedBefore();

        // Replaces "Steve joined the game". Passing null suppresses it entirely —
        // useful for silent-staff-join features.
        event.joinMessage(
                Component.text(firstTime ? "✦ " : "→ ", NamedTextColor.GREEN)
                        .append(Component.text(player.getName(), NamedTextColor.WHITE))
                        .append(Component.text(
                                firstTime ? " joined for the first time!" : " joined",
                                NamedTextColor.GRAY))
        );

        // A title is the large text drawn across the center of the screen.
        // Times: fade in, stay, fade out.
        player.showTitle(Title.title(
                Component.text(firstTime ? "Welcome!" : "Welcome back", NamedTextColor.GOLD),
                Component.text(player.getName(), NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(3),
                        Duration.ofMillis(500))
        ));

        if (firstTime) {
            player.sendMessage(Component.text(
                    "Tip: try /wand and /stats", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(
                Component.text("← ", NamedTextColor.RED)
                        .append(Component.text(event.getPlayer().getName(), NamedTextColor.WHITE))
                        .append(Component.text(" left", NamedTextColor.GRAY))
        );
    }
}
