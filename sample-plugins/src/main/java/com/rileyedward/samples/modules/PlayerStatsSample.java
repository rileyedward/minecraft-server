package com.rileyedward.samples.modules;

import com.rileyedward.samples.SampleModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║ [SAMPLE] — safe to delete                                                ║
 * ║                                                                          ║
 * ║ Concepts: COMMANDS and PERSISTENT DATA.                                  ║
 * ║                                                                          ║
 * ║ Counts blocks broken and placed per player, stored on the player so the  ║
 * ║ numbers survive restarts. Adds /stats [player].                          ║
 * ║                                                                          ║
 * ║ To remove: delete this file and its line in SamplesPlugin.MODULES.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * <p>Persistence here uses the PersistentDataContainer (PDC) — arbitrary data
 * attached directly to a player, entity, item, or block, saved by the server with
 * no database or file handling on your part.
 *
 * <p>PDC suits small per-object values. For anything you need to query across all
 * players at once ("top 10 miners"), reach for a real database instead — PDC can
 * only be read from an object you already have loaded.
 */
public final class PlayerStatsSample implements SampleModule, Listener, BasicCommand {

    private NamespacedKey brokenKey;
    private NamespacedKey placedKey;

    @Override
    public String id() {
        return "player-stats";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        // A NamespacedKey is "who owns this data" + "what is it called". The plugin
        // prefix means your "blocks_broken" can never collide with another plugin's.
        this.brokenKey = new NamespacedKey(plugin, "blocks_broken");
        this.placedKey = new NamespacedKey(plugin, "blocks_placed");

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Registering the command in code — no `commands:` block in plugin.yml.
        // "stats" is the name players type; `this` handles it because the class
        // implements BasicCommand below.
        plugin.registerCommand("stats", this);
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        increment(event.getPlayer(), brokenKey);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        increment(event.getPlayer(), placedKey);
    }

    // ── Command ─────────────────────────────────────────────────────────────

    /**
     * Runs when a player types /stats. Note {@code args} excludes the command name
     * itself, so "/stats Notch" arrives as {@code ["Notch"]}.
     */
    @Override
    public void execute(CommandSourceStack source, String[] args) {
        // The sender may be a player, the console, or a command block — never
        // assume it's a player.
        CommandSender sender = source.getSender();
        Player target;

        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text(
                        "No online player named '" + args[0] + "'.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text(
                    "Console must name a player: /stats <player>", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("── ", NamedTextColor.DARK_GRAY)
                .append(Component.text(target.getName(), NamedTextColor.AQUA))
                .append(Component.text(" ──", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(Component.text("  Broken: ", NamedTextColor.GRAY)
                .append(Component.text(read(target, brokenKey), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("  Placed: ", NamedTextColor.GRAY)
                .append(Component.text(read(target, placedKey), NamedTextColor.WHITE)));
    }

    /**
     * Powers tab completion. Called as the player types, so keep it cheap — no
     * database queries or file reads here.
     */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0].toLowerCase() : "";
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(typed))
                .toList();
    }

    // ── Persistence helpers ─────────────────────────────────────────────────

    private void increment(Player player, NamespacedKey key) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(key, PersistentDataType.INTEGER, read(player, key) + 1);
    }

    private int read(Player player, NamespacedKey key) {
        // getOrDefault avoids a null check for players who have never triggered this.
        return player.getPersistentDataContainer()
                .getOrDefault(key, PersistentDataType.INTEGER, 0);
    }
}
