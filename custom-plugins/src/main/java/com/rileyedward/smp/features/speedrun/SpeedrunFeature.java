package com.rileyedward.smp.features.speedrun;

import com.rileyedward.smp.core.Feature;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.mvplugins.multiverse.core.MultiverseCoreApi;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /speedrun} — a hardcore team speedrun world where one death ends the run for everyone.
 *
 * <p>Hardcore is emulated rather than switched on: {@code hardcore=true} in server.properties is
 * server-wide, so it would drag the survival realm and the archived realms in with it, and vanilla
 * hardcore only sends the player who died to spectator. What we want — everyone's run ending — is
 * custom logic either way. So: hard difficulty per world in {@link WorldSet}, and the death
 * listener below.
 *
 * <p>Design notes and the reasoning behind the world lifecycle live in
 * {@code docs/SPEEDRUN-WORLD.md}.
 */
public final class SpeedrunFeature implements Feature, Listener {

    private RunManager runs;
    private WorldSet worlds;

    @Override
    public String id() {
        return "speedrun";
    }

    @Override
    public void enable(JavaPlugin plugin) {
        // Multiverse owns world creation and deletion here, so without it this feature has no
        // way to do its job. plugin.yml declares `depend: [Multiverse-Core]` so this shouldn't
        // happen — but throwing is the right response if it does. SmpPlugin catches per-feature
        // exceptions, so the rest of the plugin still loads.
        if (!MultiverseCoreApi.isLoaded()) {
            throw new IllegalStateException(
                    "Multiverse-Core is not loaded — /speedrun cannot manage worlds without it");
        }

        this.worlds = new WorldSet(plugin.getLogger());
        this.runs = new RunManager(
                plugin,
                worlds,
                new PlayerScrub(worlds, plugin.getLogger()),
                new RunRecords(plugin));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.registerCommand("speedrun", new SpeedrunCommand(runs));
    }

    @Override
    public void disable() {
        if (runs != null) {
            runs.shutdown();
        }
    }

    // ── Events ──────────────────────────────────────────────────────────────

    /**
     * Any death inside the speedrun set ends the run.
     *
     * <p>The world check is what keeps this feature from reaching into the rest of the server:
     * someone dying on the survival realm has nothing to do with a run in progress. It also has
     * to cover the whole set rather than just the overworld — most speedrun deaths happen in the
     * nether, which would be an easy and very confusing thing to miss.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (!worlds.contains(player.getWorld())) {
            return;
        }

        Component message = event.deathMessage();
        runs.onDeath(player, message == null
                ? Component.text(player.getName() + " died")
                : message);
    }

    /** Puts the player who died into spectator so they can watch out the countdown. */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        runs.onRespawnDuringReset(event.getPlayer());
    }

    /**
     * Catches players who were offline for a reset.
     *
     * <p>Nothing teleports or scrubs someone who isn't online, so without this they'd rejoin a
     * rebuilt world still holding the previous run's gear.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        runs.onJoin(event.getPlayer());
    }

    /** The dragon dying is the only way to finish a run. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        if (!WorldSet.THE_END.equals(dragon.getWorld().getName())) {
            return;
        }
        runs.onDragonSlain();
    }

    // ── Command ─────────────────────────────────────────────────────────────

    /**
     * One command with subcommands, rather than one command each.
     *
     * <p>Unlike {@code WorldsFeature}, which registers a separate command per world, everything
     * here operates on the same single run — so the subcommand dispatch below is the natural
     * shape and there's no need to hold the target as instance state.
     */
    private static final class SpeedrunCommand implements BasicCommand {

        private static final List<String> SUBCOMMANDS = List.of("leave", "reset", "seed", "status");

        private final RunManager runs;

        private SpeedrunCommand(RunManager runs) {
            this.runs = runs;
        }

        /**
         * Operators pass automatically. To open it up to everyone:
         * {@code /lp group default permission set smp.speedrun true}
         */
        @Override
        public String permission() {
            return "smp.speedrun";
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (args.length == 0) {
                withPlayer(source, runs::startOrJoin);
                return;
            }

            switch (args[0].toLowerCase()) {
                case "leave" -> withPlayer(source, runs::leave);
                case "status" -> runs.status(source.getSender());
                case "reset" -> runs.requestReset(source.getSender());
                case "seed" -> seed(source, args);
                default -> source.getSender().sendMessage(Component.text(
                        "Unknown subcommand. Try: " + String.join(", ", SUBCOMMANDS),
                        NamedTextColor.RED));
            }
        }

        private void seed(CommandSourceStack source, String[] args) {
            if (args.length < 2) {
                source.getSender().sendMessage(Component.text(
                        "Usage: /speedrun seed <seed>", NamedTextColor.RED));
                return;
            }
            runs.requestReset(source.getSender(), parseSeed(args[1]));
        }

        /**
         * Accepts a number, or hashes anything else.
         *
         * <p>Matches vanilla: typing a word into Minecraft's seed box uses its hash, so
         * {@code /speedrun seed glacier} is a legitimate reproducible seed rather than an error.
         */
        private static long parseSeed(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException notANumber) {
                return raw.hashCode();
            }
        }

        /**
         * Tab completion. {@code /speedrun reset} is one typo away from {@code /speedrun}, and
         * the difference between them is a destroyed world, so this is worth wiring.
         */
        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length > 1) {
                return List.of();
            }
            String partial = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(partial)).toList();
        }

        private void withPlayer(CommandSourceStack source, Consumer<Player> action) {
            if (source.getSender() instanceof Player player) {
                action.accept(player);
                return;
            }
            source.getSender().sendMessage(Component.text(
                    "Only a player can join a run.", NamedTextColor.RED));
        }
    }
}
