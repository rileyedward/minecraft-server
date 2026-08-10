package com.rileyedward.smp.features.worlds;

import com.rileyedward.smp.core.Feature;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * {@code /current}, {@code /old}, and {@code /oldest} — one command per archived realm.
 *
 * <p>The three worlds are the realm saves from {@code ~/Documents/all_saves}, copied into
 * the server root as {@code current/}, {@code old/}, and {@code oldest/}. {@code current}
 * is the server's default world ({@code level-name} in server.properties); the other two
 * are registered with Multiverse.
 *
 * <p>Multiverse still owns world <em>management</em> — per-world gamemode, difficulty,
 * spawn, and portal rules all live in its config. This feature only provides the short
 * commands, because {@code /mv tp oldest} is a mouthful to type every time.
 */
public final class WorldsFeature implements Feature {

    /**
     * Command name → world folder name. They match here, but keeping the mapping
     * explicit means renaming a command never means renaming a directory on disk.
     */
    private static final Map<String, String> WORLDS = Map.of(
            "current", "current",
            "old", "old",
            "oldest", "oldest"
    );

    @Override
    public String id() {
        return "worlds";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        WORLDS.forEach((command, worldName) ->
                plugin.registerCommand(command, new TeleportToWorld(plugin, worldName)));
    }

    /**
     * Sends a player to one specific world's spawn.
     *
     * <p>One instance per command. {@link BasicCommand#execute} isn't told which name
     * invoked it, so the target world is held as state here rather than branched on.
     */
    private static final class TeleportToWorld implements BasicCommand {

        private final JavaPlugin plugin;
        private final String worldName;

        private TeleportToWorld(JavaPlugin plugin, String worldName) {
            this.plugin = plugin;
            this.worldName = worldName;
        }

        /**
         * Operators pass automatically. To open these up to everyone:
         * {@code /lp group default permission set smp.worlds true}
         */
        @Override
        public String permission() {
            return "smp.worlds";
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(Component.text(
                        "Only a player can travel between worlds.", NamedTextColor.RED));
                return;
            }

            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                // The old realms are configured auto-load: false so they cost no memory
                // until someone actually visits — this server has 4G of heap and three
                // multi-gigabyte worlds. Loading is what makes that trade-off invisible.
                player.sendMessage(Component.text(
                        "Loading '" + worldName + "' — this takes a moment the first time...",
                        NamedTextColor.YELLOW));

                // Routed through Multiverse rather than Bukkit's WorldCreator so that MV
                // stays the single owner of world state. Loading a world behind its back
                // leaves its config and the running server disagreeing.
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
                world = Bukkit.getWorld(worldName);
            }

            if (world == null) {
                player.sendMessage(Component.text(
                        "World '" + worldName + "' isn't available yet.", NamedTextColor.RED));
                plugin.getLogger().warning(
                        "/" + worldName + " failed: no world named '" + worldName
                                + "'. Is the folder present and imported with /mv import?");
                return;
            }

            if (player.getWorld().equals(world)) {
                player.sendMessage(Component.text(
                        "You're already in '" + worldName + "'.", NamedTextColor.GRAY));
                return;
            }

            // teleportAsync, not teleport: a cross-world jump has to load the destination
            // chunks, and the synchronous call blocks the main thread while it does.
            World destination = world;
            player.teleportAsync(destination.getSpawnLocation()).thenAccept(moved -> {
                if (moved) {
                    player.sendMessage(Component.text(
                            "Welcome to '" + worldName + "'.", NamedTextColor.AQUA));
                }
            });
        }
    }
}
