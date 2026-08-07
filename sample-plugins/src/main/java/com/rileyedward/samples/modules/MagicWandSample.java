package com.rileyedward.samples.modules;

import com.rileyedward.samples.SampleModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║ [SAMPLE] — safe to delete                                                ║
 * ║                                                                          ║
 * ║ Concepts: CUSTOM ITEMS, INTERACTION, SCHEDULED TASKS, EFFECTS.           ║
 * ║                                                                          ║
 * ║ /wand grants a launching item. Right-click to leap, leaving a particle   ║
 * ║ trail behind you.                                                        ║
 * ║                                                                          ║
 * ║ To remove: delete this file and its line in SamplesPlugin.MODULES.       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * <p>This is the sample that shows what's reachable without touching the client.
 * You can't add a genuinely new item — but you can take a vanilla one, rename it,
 * restyle it, tag it as yours, and give it entirely custom behavior. Nearly every
 * "custom item" on a Minecraft server is exactly this.
 */
public final class MagicWandSample implements SampleModule, Listener, BasicCommand {

    private JavaPlugin plugin;
    private NamespacedKey wandKey;

    @Override
    public String id() {
        return "magic-wand";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        // Kept because the scheduler needs a plugin reference to own its tasks.
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "is_magic_wand");

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.registerCommand("wand", this);
    }

    // ── Command: hand over a wand ───────────────────────────────────────────

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

    /** Builds the custom item. */
    private ItemStack createWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);

        // editMeta hands you the metadata, applies your changes, and writes it back.
        wand.editMeta(meta -> {
            // Item names render italic by default. Turning that off is what makes
            // a custom item look intentional rather than like a renamed anvil job.
            meta.displayName(Component.text("Wand of Leaping", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(List.of(
                    Component.text("Right-click to launch forward.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));

            // The enchanted shimmer, without an actual enchantment.
            meta.setEnchantmentGlintOverride(true);

            // ── The important line ──
            // Tag the item as ours in its own persistent data. Identify custom items
            // this way, NEVER by display name: names can be forged on an anvil, and
            // anyone renaming a blaze rod would get a free wand.
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });

        return wand;
    }

    // ── Interaction ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) {
            return;
        }

        ItemStack held = event.getItem();
        if (held == null || !isWand(held)) {
            return;
        }

        // Stops the normal right-click from also happening (placing a block,
        // opening a chest) so the wand doesn't fight with vanilla behavior.
        event.setCancelled(true);

        Player player = event.getPlayer();

        // getDirection() is a unit vector pointing where the player looks.
        // Scaling it and forcing an upward component turns "facing" into "leap".
        Vector launch = player.getLocation().getDirection().multiply(1.6).setY(0.8);
        player.setVelocity(launch);

        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.4f);
        spawnTrail(player);
    }

    private boolean isWand(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /**
     * Draws a particle trail for one second.
     *
     * <p>Scheduled tasks are how you do anything over time. A plugin must never
     * sleep or loop-and-wait — the whole server runs on one thread, so blocking it
     * freezes the game for every player. You schedule instead.
     *
     * <p>Timings are in ticks: 20 ticks = 1 second.
     */
    private void spawnTrail(Player player) {
        new BukkitRunnable() {
            private int ticksElapsed = 0;

            @Override
            public void run() {
                // Always define a stop condition. A task without one runs until the
                // server shuts down — and if it also references a player who left,
                // it leaks that object for the entire uptime.
                if (ticksElapsed++ >= 20 || !player.isOnline()) {
                    cancel();
                    return;
                }
                player.getWorld().spawnParticle(
                        Particle.END_ROD,
                        player.getLocation().add(0, 0.5, 0),
                        6,              // how many
                        0.2, 0.2, 0.2   // random spread on each axis
                );
            }
        }.runTaskTimer(plugin, 0L, 1L); // start now, repeat every tick
    }
}
