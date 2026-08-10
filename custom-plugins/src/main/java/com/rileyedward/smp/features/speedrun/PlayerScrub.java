package com.rileyedward.smp.features.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Returns one player to a legal run-start state.
 *
 * <p>A run isn't a run if state leaks across a reset. Carrying a diamond pickaxe or a
 * half-finished advancement tree into a "fresh" world makes the timer meaningless, so this
 * is the checklist of everything that has to go.
 */
final class PlayerScrub {

    private final WorldSet worlds;
    private final Logger logger;

    PlayerScrub(WorldSet worlds, Logger logger) {
        this.worlds = worlds;
        this.logger = logger;
    }

    /**
     * Wipes a player back to a fresh start.
     *
     * <p><b>The guard on the first line is the most important part of this class.</b> A reset
     * moves players out through the survival realm, which means Multiverse-Inventories loads
     * their real survival inventory while they pass through. Running this method a moment too
     * early — or on the wrong player — would clear that instead of the run inventory, and
     * there is no undo. So the check lives here rather than being every caller's problem: if
     * the player isn't standing in a speedrun world, this does nothing at all.
     */
    void scrub(Player player) {
        if (!worlds.contains(player.getWorld())) {
            logger.warning("Refused to scrub " + player.getName() + " in world '"
                    + player.getWorld().getName() + "' — not a speedrun world. "
                    + "This is a bug, but their inventory is safe.");
            return;
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getEnderChest().clear();
        player.setItemOnCursor(null);

        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());

        // Survival explicitly: whoever just died has been sitting in spectator watching the
        // countdown, and Multiverse's per-world gamemode enforcement is not something to rely
        // on for correctness here.
        player.setGameMode(GameMode.SURVIVAL);

        // A bed spawn from the previous run points into a world that no longer exists. Left
        // alone it either strands the player or silently ignores them; either way it's wrong.
        player.setRespawnLocation(player.getWorld().getSpawnLocation(), true);

        revokeAdvancements(player);
    }

    /**
     * Revokes every advancement the player has earned.
     *
     * <p>The easiest item on the checklist to forget, and the most visible when missed —
     * "Stone Age" popping on a fresh run gives the game away immediately. There's no bulk
     * API, so this walks every advancement the server knows about and revokes each awarded
     * criterion individually.
     */
    private void revokeAdvancements(Player player) {
        Iterator<Advancement> advancements = Bukkit.advancementIterator();
        while (advancements.hasNext()) {
            AdvancementProgress progress = player.getAdvancementProgress(advancements.next());
            for (String criterion : progress.getAwardedCriteria()) {
                progress.revokeCriteria(criterion);
            }
        }
    }
}
