package com.rileyedward.samples.modules;

import com.rileyedward.samples.SampleModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║ [SAMPLE] — safe to delete                                                ║
 * ║                                                                          ║
 * ║ Concept: EVENTS — reacting to things that happen in the world.           ║
 * ║                                                                          ║
 * ║ Events are the heart of plugin development. The server fires hundreds of ║
 * ║ event types, and a plugin is largely just a set of reactions to them.    ║
 * ║                                                                          ║
 * ║ To remove: delete this file and its line in SamplesPlugin.MODULES.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * <p>Announces rare finds to the whole server, and reports ordinary blocks
 * privately on the breaker's action bar. The split matters: broadcasting every
 * broken block would make chat unusable within about ten seconds of play.
 */
public final class BlockBreakAnnouncerSample implements SampleModule, Listener {

    /** Blocks worth telling everyone about. Set lookup is O(1) — this runs on every break. */
    private static final Set<Material> NOTABLE = Set.of(
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.SPAWNER
    );

    @Override
    public String id() {
        return "block-break-announcer";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        // Tells the server "this object has @EventHandler methods — call them."
        // Forgetting this line is the #1 reason a listener silently does nothing:
        // the code is correct, it's just never wired up.
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * The @EventHandler annotation is what makes this a listener method. The method
     * name is irrelevant — the parameter type is what determines which event it
     * receives.
     *
     * <p>priority = MONITOR means "run last, after every other plugin has decided."
     * Use it when you only observe. If you intend to *change* the outcome, use a
     * lower priority instead; changes made at MONITOR may be ignored by convention.
     *
     * <p>ignoreCancelled = true means skip this if another plugin already cancelled
     * the break — otherwise you'd announce diamonds that were never actually mined.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlock().getType();

        // ── Try uncommenting this to see a plugin overrule the game ──
        // Cancelling stops the block from breaking at all. This is the real power
        // of events: you don't just observe, you decide. (Needs a lower priority
        // than MONITOR to be respected.)
        //
        // if (type == Material.DIAMOND_ORE) {
        //     event.setCancelled(true);
        //     player.sendMessage(Component.text("Not today.", NamedTextColor.RED));
        //     return;
        // }

        if (NOTABLE.contains(type)) {
            // Adventure Components are the modern way to build text. They compose
            // colors and styles as objects rather than the old "§a" escape codes.
            Component announcement = Component.text(player.getName(), NamedTextColor.AQUA)
                    .append(Component.text(" found ", NamedTextColor.GRAY))
                    .append(Component.text(prettify(type), NamedTextColor.GOLD))
                    .append(Component.text("!", NamedTextColor.GRAY));

            Bukkit.broadcast(announcement);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
        } else {
            // The action bar is the thin strip above the hotbar. Ideal for
            // low-importance feedback that shouldn't clutter chat.
            player.sendActionBar(
                    Component.text("Broke ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(prettify(type), NamedTextColor.GRAY))
            );
        }
    }

    /** DEEPSLATE_DIAMOND_ORE -> "Deepslate Diamond Ore" */
    private static String prettify(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
