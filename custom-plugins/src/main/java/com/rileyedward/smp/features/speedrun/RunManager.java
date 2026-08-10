package com.rileyedward.smp.features.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The run itself: who's in it, how long it's been going, and what happens when it ends.
 *
 * <pre>
 * IDLE ──/speedrun──▶ RUNNING ──death or /speedrun reset──▶ RESETTING ──▶ RUNNING
 * </pre>
 *
 * <p>Everything here runs on the main thread. That's not incidental — it's what makes the
 * re-entry guard in {@link #onDeath} correct without any locking.
 */
final class RunManager {

    /**
     * Seconds between a death and the world rebuilding.
     *
     * <p>Not just politeness. An instant yank means nobody ever finds out what killed the run,
     * and the arguing that follows is worse than the wait.
     */
    private static final int GRACE_SECONDS = 10;

    /**
     * Ticks to wait after a player lands in the new world before wiping them.
     *
     * <p>Multiverse-Inventories loads the player's speedrun profile on world change, and
     * Multiverse applies the per-world gamemode a tick later
     * ({@code gamemode-and-flight-enforce-delay} in its config). Scrubbing inside that window
     * gets silently overwritten by both — the wipe appears to work and then quietly undoes
     * itself. A few ticks of headroom is the whole fix.
     */
    private static final long SCRUB_DELAY_TICKS = 5L;

    private enum State {
        /** No run in progress. The worlds may or may not exist. */
        IDLE,
        /** A run is live and the clock is going. */
        RUNNING,
        /** Mid-reset. Nothing may start another one. */
        RESETTING
    }

    private final JavaPlugin plugin;
    private final WorldSet worlds;
    private final PlayerScrub scrub;
    private final RunRecords records;
    private final Random random = new Random();

    /** Insertion-ordered so {@code /speedrun status} lists people in the order they joined. */
    private final Set<UUID> participants = new LinkedHashSet<>();

    private State state = State.IDLE;
    private long runStartMillis;

    /**
     * The final time, frozen when the dragon dies.
     *
     * <p>Zero while a run is still live. Without this the clock would keep climbing after the
     * run was already over, and {@code /speedrun status} would report a different "finished"
     * time every time you asked it.
     */
    private long finishedMillis;
    private BukkitTask countdown;

    RunManager(JavaPlugin plugin, WorldSet worlds, PlayerScrub scrub, RunRecords records) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.scrub = scrub;
        this.records = records;
    }

    // ── Commands ────────────────────────────────────────────────────────────

    /** {@code /speedrun} — start a run if there isn't one, otherwise join it. */
    void startOrJoin(Player player) {
        switch (state) {
            case RESETTING -> player.sendMessage(info("A reset is in progress — hang on."));
            case RUNNING -> join(player);
            case IDLE -> {
                participants.add(player.getUniqueId());
                player.sendMessage(info("Building a new world. This takes a moment and the "
                        + "server will hitch — that's expected."));
                beginRebuild(randomSeed());
            }
        }
    }

    /** {@code /speedrun leave} — drop out without ending the run for everyone else. */
    void leave(Player player) {
        boolean wasIn = participants.remove(player.getUniqueId());

        if (!wasIn && !worlds.contains(player.getWorld())) {
            player.sendMessage(info("You're not in a run."));
            return;
        }

        if (worlds.contains(player.getWorld())) {
            player.teleportAsync(fallbackSpawn()).thenAccept(moved -> {
                if (moved) {
                    player.sendMessage(info("Left the run. Your survival gear is where you left it."));
                }
            });
        } else {
            player.sendMessage(info("Left the run."));
        }

        announce(Component.text(player.getName() + " left the run.", NamedTextColor.GRAY));
    }

    /** {@code /speedrun reset} — deliberate restart on a fresh random seed. */
    void requestReset(CommandSender sender) {
        requestReset(sender, randomSeed());
    }

    /** {@code /speedrun seed <seed>} — deliberate restart on a chosen seed, no countdown. */
    void requestReset(CommandSender sender, long seed) {
        if (state == State.RESETTING) {
            sender.sendMessage(info("Already resetting."));
            return;
        }

        String who = sender instanceof Player player ? player.getName() : "console";

        // /speedrun seed <n> from idle is a legitimate way to start a set-seed run, so this is
        // allowed rather than guarded — but saying "reset the run" when there is no run to reset
        // reads like something went wrong.
        announce(Component.text(state == State.IDLE
                ? who + " started a run."
                : who + " reset the run.", NamedTextColor.YELLOW));

        // No grace period here: whoever ran the command already knows what happened.
        beginRebuild(seed);
    }

    /** {@code /speedrun status} — seed, clock, and who's in. */
    void status(CommandSender sender) {
        sender.sendMessage(Component.text("Speedrun", NamedTextColor.AQUA, TextDecoration.BOLD));

        switch (state) {
            case IDLE -> sender.sendMessage(info("No run in progress. /speedrun to start one."));
            case RESETTING -> sender.sendMessage(info("Resetting..."));
            case RUNNING -> {
                sender.sendMessage(field("Seed", String.valueOf(worlds.seed())));
                sender.sendMessage(field(finishedMillis > 0 ? "Finished in" : "Elapsed",
                        formatDuration(finishedMillis > 0 ? finishedMillis : elapsedMillis())));

                List<String> names = new ArrayList<>();
                for (Player player : onlineParticipants()) {
                    names.add(player.getName());
                }
                sender.sendMessage(field("In the run",
                        names.isEmpty() ? "nobody online" : String.join(", ", names)));
            }
        }

        long best = records.serverBestMillis();
        if (best > 0) {
            sender.sendMessage(field("Server best",
                    formatDuration(best) + " (" + records.serverBestHolders() + ")"));
        }
    }

    // ── Events ──────────────────────────────────────────────────────────────

    /**
     * A player died inside the run. Everyone's run is over.
     *
     * <p><b>The two lines that matter most in this feature.</b> Two players dying in the same
     * tick must produce one reset, not two. Because every Bukkit event is delivered on the main
     * thread, checking the state and then setting it happens with no window in between — the
     * second death this tick sees {@code RESETTING} and returns. A lock or an
     * {@code AtomicBoolean} would add nothing here.
     */
    void onDeath(Player player, Component deathMessage) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.RESETTING;

        long elapsed = elapsedMillis();

        announce(Component.text("Run over — ", NamedTextColor.RED)
                .append(deathMessage.colorIfAbsent(NamedTextColor.RED)));
        announce(Component.text("Survived " + formatDuration(elapsed) + ". Resetting in "
                + GRACE_SECONDS + "s.", NamedTextColor.GRAY));

        // A dead player sitting on the death screen cannot be teleported, which would strand
        // them in a world we're about to regenerate. Force the respawn a tick later; the
        // respawn listener then puts them in spectator to watch out the countdown.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        }, 1L);

        startCountdown(randomSeed());
    }

    /**
     * Wipes anyone who logs in already standing in a speedrun world.
     *
     * <p>Closes a real state leak. A player who disconnects mid-run misses the reset entirely —
     * nothing teleports them or scrubs them — so they'd log back in holding pre-reset gear while
     * standing in a world that was rebuilt without them. Their position survives the regen; their
     * right to that inventory doesn't.
     *
     * <p>Safe to apply bluntly: entering a run always clears you anyway, and
     * {@link PlayerScrub#scrub} refuses to touch anyone outside the set, so a player logging into
     * the survival realm is never affected.
     */
    void onJoin(Player player) {
        if (!worlds.contains(player.getWorld())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                scrub.scrub(player);
                player.sendMessage(info("You logged back into a run that reset without you — "
                        + "starting you clean."));
            }
        }, SCRUB_DELAY_TICKS);
    }

    /**
     * Puts a just-respawned participant into spectator for the rest of the countdown.
     *
     * <p>Called from the respawn listener rather than done at death, because gamemode set
     * during {@code PlayerDeathEvent} doesn't survive the respawn that follows it.
     */
    void onRespawnDuringReset(Player player) {
        if (state != State.RESETTING || !participants.contains(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && state == State.RESETTING) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }, 1L);
    }

    /** The dragon is down — the run is complete. */
    void onDragonSlain() {
        if (state != State.RUNNING || finishedMillis > 0) {
            return;
        }
        finishedMillis = elapsedMillis();

        long elapsed = finishedMillis;
        long seed = worlds.seed();
        List<Player> finishers = onlineParticipants();
        boolean isRecord = records.recordCompletion(elapsed, seed, finishers);

        // Server-wide, unlike the death and countdown noise: finishing a run is worth bragging
        // about to people who aren't in it.
        Bukkit.broadcast(Component.text("Dragon down! ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text("Speedrun finished in " + formatDuration(elapsed),
                        NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)));
        Bukkit.broadcast(Component.text("  seed " + seed + " — "
                + String.join(", ", finishers.stream().map(Player::getName).toList()),
                NamedTextColor.GRAY));

        if (isRecord) {
            Bukkit.broadcast(Component.text("  a new server record.", NamedTextColor.GOLD));
        } else {
            Bukkit.broadcast(Component.text("  server best is "
                    + formatDuration(records.serverBestMillis()), NamedTextColor.GRAY));
        }

        announce(Component.text("/speedrun reset when you're ready to go again.", NamedTextColor.GRAY));
    }

    /** Cancels the countdown so a reload doesn't leave a task pointing at a dead plugin. */
    void shutdown() {
        cancelCountdown();
    }

    boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    // ── Reset machinery ─────────────────────────────────────────────────────

    private void join(Player player) {
        boolean rejoining = !participants.add(player.getUniqueId());

        if (rejoining && worlds.contains(player.getWorld())) {
            player.sendMessage(info("You're already in the run."));
            return;
        }

        // Entering mid-run wipes you. That's not a courtesy to the run, it's the point: without
        // it, /speedrun leave and back is a way to fetch survival gear or dodge a bad situation.
        player.sendMessage(info("Joining the run — your run inventory is being cleared."));
        sendIntoRun(player);
    }

    private void startCountdown(long seed) {
        cancelCountdown();

        countdown = new BukkitRunnable() {
            private int secondsLeft = GRACE_SECONDS;

            @Override
            public void run() {
                secondsLeft--;

                if (secondsLeft <= 0) {
                    cancel();
                    countdown = null;
                    beginRebuild(seed);
                    return;
                }
                if (secondsLeft <= 3) {
                    announce(Component.text(secondsLeft + "...", NamedTextColor.YELLOW));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void cancelCountdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
    }

    /**
     * Evacuate, rebuild, repopulate.
     *
     * <p>Split across two ticks on purpose: the teleports out are asynchronous and every one of
     * them has to land before the regen starts, because Multiverse cannot rebuild a world that
     * still has a player standing in it.
     */
    private void beginRebuild(long seed) {
        state = State.RESETTING;
        cancelCountdown();

        List<Player> evacuees = worlds.playersInside();
        Location safety = fallbackSpawn();

        List<CompletableFuture<Boolean>> moves = new ArrayList<>(evacuees.size());
        for (Player player : evacuees) {
            moves.add(player.teleportAsync(safety));
        }

        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("A teleport out of the speedrun set failed: " + error);
                    }
                    // whenComplete may fire on any thread; world work must be on the main one.
                    Bukkit.getScheduler().runTask(plugin, () -> rebuildWorlds(seed));
                });
    }

    private void rebuildWorlds(long seed) {
        // Anyone still inside means a teleport silently failed. Regen would fail anyway, and
        // failing here is the difference between "reset didn't work" and a half-built set.
        if (!worlds.playersInside().isEmpty()) {
            failReset("Could not clear the speedrun worlds of players.");
            return;
        }

        boolean firstEverRun = !worlds.exists();

        if (!worlds.ensureCreated(seed)) {
            failReset("Could not create the speedrun worlds. Check the console.");
            return;
        }
        // Freshly created worlds are already fresh. Regenerating them again would just be a
        // second multi-second freeze for no gain.
        if (!firstEverRun && !worlds.regenAll(seed)) {
            failReset("Could not regenerate the speedrun worlds. Check the console.");
            return;
        }

        runStartMillis = System.currentTimeMillis();
        finishedMillis = 0L;
        state = State.RUNNING;

        for (Player player : onlineParticipants()) {
            sendIntoRun(player);
        }

        announce(Component.text("New run — seed " + worlds.seed() + ". Go.", NamedTextColor.GREEN));
    }

    private void failReset(String message) {
        state = State.IDLE;
        cancelCountdown();
        announce(Component.text(message, NamedTextColor.RED));
        plugin.getLogger().severe("Speedrun reset aborted: " + message);
    }

    /** Teleport into the run's overworld and wipe the player once they've landed. */
    private void sendIntoRun(Player player) {
        Location spawn = worlds.spawn();
        if (spawn == null) {
            player.sendMessage(Component.text("The speedrun world isn't loaded.", NamedTextColor.RED));
            return;
        }

        player.teleportAsync(spawn).thenAccept(moved -> {
            if (!moved) {
                plugin.getLogger().warning("Could not teleport " + player.getName() + " into the run");
                return;
            }
            // Deliberately delayed — see SCRUB_DELAY_TICKS. runTaskLater is safe to call from
            // the async thread this callback may be on.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    scrub.scrub(player);
                }
            }, SCRUB_DELAY_TICKS);
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private long elapsedMillis() {
        return runStartMillis == 0L ? 0L : System.currentTimeMillis() - runStartMillis;
    }

    private long randomSeed() {
        return random.nextLong();
    }

    private List<Player> onlineParticipants() {
        List<Player> online = new ArrayList<>();
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    /**
     * Where evacuated players wait during a rebuild.
     *
     * <p>The default world, because it is the one world that can never be unloaded at runtime,
     * so it is always a valid destination.
     */
    private static Location fallbackSpawn() {
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /**
     * Messages the run's audience — participants plus anyone standing in the set.
     *
     * <p>Scoped rather than {@code Bukkit.broadcast} so that resets, countdowns and deaths
     * don't spam people quietly building on the survival realm. Completing a run is the one
     * exception and broadcasts server-wide.
     */
    private void announce(Component message) {
        Set<UUID> seen = new LinkedHashSet<>();
        for (Player player : onlineParticipants()) {
            if (seen.add(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
        for (Player player : worlds.playersInside()) {
            if (seen.add(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
        plugin.getLogger().info("[speedrun] "
                + PlainTextComponentSerializer.plainText().serialize(message));
    }

    private static Component info(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    private static Component field(String label, String value) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    /** {@code 4m 12.3s} — readable at a glance, which a raw millisecond count is not. */
    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long tenths = (millis % 1000L) / 100L;

        if (minutes == 0) {
            return seconds + "." + tenths + "s";
        }
        return minutes + "m " + seconds + "." + tenths + "s";
    }
}
