package com.rileyedward.smp.features.speedrun;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * Best times, persisted across restarts.
 *
 * <p>A timer nobody records is just a clock. This keeps the server record and a personal
 * best per player in {@code plugins/CustomPlugins/speedrun.yml} — separate from the plugin's
 * own config.yml, which is hand-edited and shouldn't be rewritten by code.
 *
 * <p>Deliberately the smallest thing that works: a flat YAML file, loaded once on enable and
 * saved after a completed run. Nothing else in this plugin persists state, so this is not the
 * place to introduce a storage layer.
 */
final class RunRecords {

    private static final String SERVER_BEST_MILLIS = "server-best.millis";
    private static final String SERVER_BEST_HOLDERS = "server-best.holders";
    private static final String SERVER_BEST_SEED = "server-best.seed";

    private final File file;
    private final Logger logger;
    private YamlConfiguration config;

    RunRecords(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "speedrun.yml");
        this.logger = plugin.getLogger();
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    /** The server record in milliseconds, or 0 if nobody has finished a run yet. */
    long serverBestMillis() {
        return config.getLong(SERVER_BEST_MILLIS, 0L);
    }

    /** Names of the team that set the server record, for the completion message. */
    String serverBestHolders() {
        return config.getString(SERVER_BEST_HOLDERS, "");
    }

    /** A personal best in milliseconds, or 0 if this player has never finished. */
    long personalBestMillis(Player player) {
        return config.getLong(playerKey(player), 0L);
    }

    /**
     * Records a finished run.
     *
     * @return true if this beat the server record, so the caller can say so loudly
     */
    boolean recordCompletion(long millis, long seed, Collection<? extends Player> finishers) {
        for (Player player : finishers) {
            long previous = personalBestMillis(player);
            if (previous == 0L || millis < previous) {
                config.set(playerKey(player), millis);
            }
        }

        long serverBest = serverBestMillis();
        boolean isRecord = serverBest == 0L || millis < serverBest;

        if (isRecord) {
            config.set(SERVER_BEST_MILLIS, millis);
            config.set(SERVER_BEST_SEED, seed);
            config.set(SERVER_BEST_HOLDERS, String.join(", ",
                    finishers.stream().map(Player::getName).toList()));
        }

        save();
        return isRecord;
    }

    private static String playerKey(Player player) {
        return "players." + player.getUniqueId() + ".best-millis";
    }

    private void save() {
        try {
            // Small enough that a main-thread write is cheaper than the machinery needed to
            // move it off-thread, and it happens once per completed run.
            config.save(file);
        } catch (IOException e) {
            // A lost best time is not worth interrupting the run over.
            logger.warning("Could not save speedrun records to " + file + ": " + e.getMessage());
        }
    }
}
