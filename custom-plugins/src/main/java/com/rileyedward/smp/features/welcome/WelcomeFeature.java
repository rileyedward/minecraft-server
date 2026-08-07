package com.rileyedward.smp.features.welcome;

import com.rileyedward.smp.core.Feature;
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
 * Replaces the vanilla join and quit messages, and shows a title on join.
 *
 * <p>Some events carry a value the server is <em>about</em> to use. Overwrite it and
 * the server uses yours instead — that's the difference between adding behavior and
 * replacing it.
 */
public final class WelcomeFeature implements Feature, Listener {

    @Override
    public String id() {
        return "welcome";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        // Without this line the @EventHandler methods below never fire. The code
        // compiles, the server starts clean, and nothing happens — the most common
        // bug in plugin development.
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // False only on a player's very first connection ever. The server tracks
        // this in world/players/<uuid>.dat.
        boolean firstTime = !player.hasPlayedBefore();

        // Replaces "Steve joined the game". Passing null suppresses it entirely.
        event.joinMessage(
                Component.text(firstTime ? "✦ " : "→ ", NamedTextColor.GREEN)
                        .append(Component.text(player.getName(), NamedTextColor.WHITE))
                        .append(Component.text(
                                firstTime ? " joined for the first time!" : " joined",
                                NamedTextColor.GRAY))
        );

        // Large text across the center of the screen. Times: fade in, stay, fade out.
        player.showTitle(Title.title(
                Component.text(firstTime ? "Welcome!" : "Welcome back", NamedTextColor.GOLD),
                Component.text(player.getName(), NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(3),
                        Duration.ofMillis(500))
        ));
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
