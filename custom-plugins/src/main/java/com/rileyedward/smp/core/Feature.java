package com.rileyedward.smp.core;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A self-contained unit of server behavior.
 *
 * <p>Bukkit has no concept of "features" — a plugin is one class and that is all the
 * server knows about. This interface is a convention layered on top so each feature
 * owns its own setup and teardown instead of everything piling into onEnable().
 *
 * <p>Each feature lives in its own package under {@code features/}, holds its own
 * listeners and commands, and is registered by adding one line to
 * {@link com.rileyedward.smp.SmpPlugin}. Adding or removing a feature touches
 * exactly two places: its package, and that list.
 */
public interface Feature {

    /**
     * Stable identifier, matching the key under {@code features:} in config.yml.
     * Returning "welcome" means the toggle is {@code features.welcome}.
     */
    String id();

    /**
     * Called once at startup if the feature is enabled. Register listeners,
     * commands, and scheduled tasks here.
     */
    void enable(JavaPlugin plugin);

    /**
     * Called at shutdown. Most features need nothing here — Bukkit automatically
     * unregisters listeners, commands, and tasks when a plugin disables. Override
     * only to release something Bukkit doesn't know about, such as a database
     * connection or an open file.
     */
    default void disable() {
    }
}
