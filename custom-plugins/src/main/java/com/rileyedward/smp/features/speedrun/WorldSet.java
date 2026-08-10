package com.rileyedward.smp.features.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.RegenWorldOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The three worlds that make up one speedrun: overworld, nether, and end.
 *
 * <p>A speedrun needs all three — bastion and fortress in the nether, stronghold and
 * dragon in the end — so "a run" is never one world. Every reset rebuilds the whole set,
 * and this is the only class in the feature that talks to Multiverse.
 *
 * <p><b>The names are not arbitrary.</b> Multiverse links portals by naming convention
 * ({@code world-name-format} in its config.yml): the nether of {@code speedrun} must be
 * called {@code speedrun_nether} and the end {@code speedrun_the_end}, or a nether portal
 * in the run drops you into the survival realm's nether instead.
 *
 * <p><b>Why regen instead of delete-and-create.</b> Multiverse's {@code regenWorld} tears
 * a world down and builds a fresh one <em>under the same name</em>. That matters for more
 * than tidiness: the name is the key that Multiverse-Inventories uses for group membership
 * and that Multiverse's own config uses for per-world difficulty. Recreating under a new
 * name each reset would mean re-registering both every time. Same name, no bookkeeping.
 *
 * <p>Note this deliberately does <em>not</em> dispatch {@code mv regen} as a command.
 * Multiverse treats regen as a dangerous action, and this server has
 * {@code confirm-mode: enable} with {@code use-confirm-otp: true} — the command would sit
 * in a queue waiting for {@code /mv confirm <3-digit-number>} that a plugin has no
 * reasonable way to supply. The Java API skips the queue entirely.
 */
final class WorldSet {

    static final String OVERWORLD = "speedrun";
    static final String NETHER = "speedrun_nether";
    static final String THE_END = "speedrun_the_end";

    /**
     * The set, in build order. The overworld comes first because it is where players are
     * sent after a reset, so a failure there should abort before the other two are touched.
     */
    private static final List<Dimension> DIMENSIONS = List.of(
            new Dimension(OVERWORLD, World.Environment.NORMAL),
            new Dimension(NETHER, World.Environment.NETHER),
            new Dimension(THE_END, World.Environment.THE_END)
    );

    private record Dimension(String name, World.Environment environment) {
    }

    private final Logger logger;

    WorldSet(Logger logger) {
        this.logger = logger;
    }

    /** True if this world is part of the run. The whole set counts, not just the overworld. */
    boolean contains(World world) {
        return world != null && isSpeedrunName(world.getName());
    }

    private static boolean isSpeedrunName(String name) {
        return OVERWORLD.equals(name) || NETHER.equals(name) || THE_END.equals(name);
    }

    /**
     * True once Multiverse knows about all three worlds.
     *
     * <p>Distinguishes the very first run — where creation already gives a fresh world — from
     * every later one, which needs a regen. Without it the first {@code /speedrun} would
     * generate three worlds and then immediately throw them away and generate three more.
     */
    boolean exists() {
        WorldManager worldManager = worldManager();
        for (Dimension dimension : DIMENSIONS) {
            if (worldManager.getWorld(dimension.name()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The overworld's spawn — where players land at the start of a run. Null if not loaded. */
    Location spawn() {
        World world = Bukkit.getWorld(OVERWORLD);
        return world == null ? null : world.getSpawnLocation();
    }

    /** The seed the current set was generated from, for {@code /speedrun status}. */
    long seed() {
        LoadedMultiverseWorld world = worldManager().getLoadedWorld(OVERWORLD).getOrNull();
        return world == null ? 0L : world.getSeed();
    }

    /**
     * Everyone standing in any of the three worlds right now.
     *
     * <p>Used to evacuate before a regen. This is deliberately "who is physically here"
     * rather than "who joined the run" — a world cannot be regenerated with players still
     * inside it, so a visitor who wandered in via {@code /mvtp} has to be swept out too.
     */
    List<Player> playersInside() {
        List<Player> found = new ArrayList<>();
        for (Dimension dimension : DIMENSIONS) {
            World world = Bukkit.getWorld(dimension.name());
            if (world != null) {
                found.addAll(world.getPlayers());
            }
        }
        return found;
    }

    /**
     * Creates any of the three worlds that don't exist yet, all from one seed.
     *
     * <p>Called on the first {@code /speedrun}. Generating three worlds takes a while and
     * blocks the main thread; there is no way around that on first creation, and it happens
     * once rather than on every reset.
     *
     * @return true if the whole set is present and loaded afterwards
     */
    boolean ensureCreated(long seed) {
        WorldManager worldManager = worldManager();

        for (Dimension dimension : DIMENSIONS) {
            MultiverseWorld known = worldManager.getWorld(dimension.name()).getOrNull();

            if (known != null) {
                // Already known to Multiverse. Load it if it isn't in memory.
                if (known.isLoaded()) {
                    continue;
                }
                if (worldManager.loadWorld(LoadWorldOptions.world(known)).isFailure()) {
                    logger.severe("Could not load existing speedrun world '" + dimension.name() + "'");
                    return false;
                }
                continue;
            }

            logger.info("Creating speedrun world '" + dimension.name() + "' (seed " + seed + ")");

            var attempt = worldManager.createWorld(CreateWorldOptions.worldName(dimension.name())
                    .environment(dimension.environment())
                    .seed(seed)
                    .generateStructures(true));

            if (attempt.isFailure()) {
                logger.severe("Could not create speedrun world '" + dimension.name() + "': "
                        + attempt.getFailureReason().name());
                return false;
            }

            applyRunSettings(attempt.get());
        }
        return true;
    }

    /**
     * Rebuilds all three worlds from a single seed.
     *
     * <p>One seed for the whole set, not one each: in vanilla a single seed drives every
     * dimension, so sharing it is what makes {@code /speedrun seed <n>} reproduce a run
     * people can compare against.
     *
     * <p><b>Every player must already be out of these worlds.</b> Regen cannot run on a
     * world that still has players in it.
     *
     * <p>This blocks the main thread while terrain generates. That freeze is the known cost
     * of the simple design — the fix is a pre-generated spare set, which is deliberately
     * not built yet. See docs/SPEEDRUN-WORLD.md, Phase 3.
     *
     * @return true if all three were rebuilt; false leaves the set in an unknown state and
     *         the caller must not start a run
     */
    boolean regenAll(long seed) {
        WorldManager worldManager = worldManager();

        for (Dimension dimension : DIMENSIONS) {
            // Nothing here should ever be able to reach the default world, but the cost of
            // being wrong is deleting the survival realm, so the guard is worth its two lines.
            if (!isSpeedrunName(dimension.name()) || dimension.name().equals(defaultWorldName())) {
                logger.severe("Refusing to regenerate '" + dimension.name()
                        + "' — it is not a speedrun world. This is a bug; no worlds were touched.");
                return false;
            }

            LoadedMultiverseWorld world = worldManager.getLoadedWorld(dimension.name()).getOrNull();
            if (world == null) {
                logger.severe("Cannot regenerate '" + dimension.name() + "': it is not loaded");
                return false;
            }

            var attempt = worldManager.regenWorld(RegenWorldOptions.world(world)
                    .seed(seed)
                    // Keep the per-world difficulty and gamemode set at creation, so hard mode
                    // and survival survive every reset without being reapplied.
                    .keepWorldConfig(true)
                    .keepGameRule(true)
                    .keepWorldBorder(true));

            if (attempt.isFailure()) {
                logger.severe("Regen of '" + dimension.name() + "' failed: "
                        + attempt.getFailureReason().name()
                        + " — the speedrun world set is now half-rebuilt.");
                return false;
            }
        }
        return true;
    }

    /**
     * The per-world settings that make a run a run, applied once at creation.
     *
     * <p>{@code hardcore=true} in server.properties is server-wide, so it would drag the
     * survival realm and the archived realms in with it — and vanilla hardcore only sends
     * the player who died to spectator, when what we want is everyone's run to end. Both
     * reasons point the same way: emulate it. Hard difficulty here, team wipe in
     * {@link RunManager}.
     */
    private void applyRunSettings(LoadedMultiverseWorld world) {
        world.setDifficulty(Difficulty.HARD)
                .onFailure(t -> logger.warning("Could not set difficulty on " + world.getName() + ": " + t));
        world.setGameMode(GameMode.SURVIVAL)
                .onFailure(t -> logger.warning("Could not set gamemode on " + world.getName() + ": " + t));
        // Three extra always-loaded worlds on a 4G heap. Their spawn chunks don't need to be
        // pinned in memory when nobody is standing in them.
        world.setKeepSpawnInMemory(false)
                .onFailure(t -> logger.warning("Could not unpin spawn on " + world.getName() + ": " + t));
    }

    private static String defaultWorldName() {
        return Bukkit.getWorlds().get(0).getName();
    }

    private static WorldManager worldManager() {
        return MultiverseCoreApi.get().getWorldManager();
    }
}
