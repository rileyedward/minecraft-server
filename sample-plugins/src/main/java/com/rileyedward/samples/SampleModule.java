package com.rileyedward.samples;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A self-contained unit of plugin behavior.
 *
 * <p>Bukkit has no concept of "modules" — a plugin is one class and that is all the
 * server knows about. This interface is a convention layered on top so each feature
 * owns its own setup and teardown instead of everything piling into onEnable().
 *
 * <p>The payoff: a feature can be deleted by removing one file and one line, and
 * features can't quietly entangle themselves with each other.
 *
 * <p>This interface is NOT sample code. Keep it — write your real features as
 * modules alongside the samples, then delete the samples when you're done with them.
 */
public interface SampleModule {

    /**
     * Stable identifier, matching the key under {@code samples:} in config.yml.
     * Returning "welcome" means the toggle is {@code samples.welcome}.
     */
    String id();

    /**
     * Called once at startup if the module is enabled. Register listeners,
     * commands, and scheduled tasks here.
     */
    void enable(JavaPlugin plugin);

    /**
     * Called at shutdown. Most modules need nothing here — Bukkit automatically
     * unregisters listeners, commands, and tasks when a plugin disables. Override
     * only to release something Bukkit doesn't know about, such as a database
     * connection or an open file.
     */
    default void disable() {
    }
}
