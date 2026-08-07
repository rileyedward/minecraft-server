package com.rileyedward.samples;

import com.rileyedward.samples.modules.BlockBreakAnnouncerSample;
import com.rileyedward.samples.modules.MagicWandSample;
import com.rileyedward.samples.modules.PlayerStatsSample;
import com.rileyedward.samples.modules.WelcomeSample;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Plugin entry point.
 *
 * <p>Paper finds this class through the {@code main:} field in plugin.yml, constructs
 * it, and calls {@link #onEnable()} at startup and {@link #onDisable()} at shutdown.
 * Extending {@link JavaPlugin} is what makes a class a plugin.
 *
 * <p>Note there is no constructor and no {@code main} method. You never instantiate
 * this yourself — the server owns the lifecycle.
 */
public final class SamplesPlugin extends JavaPlugin {

    /**
     * ── THE REGISTRATION LIST ────────────────────────────────────────────────
     *
     * Comment out or delete a line to remove that sample entirely. Nothing else
     * references these classes, so a commented line plus a deleted file is a clean
     * removal.
     *
     * These are constructor references ({@code ClassName::new}), not instances —
     * nothing is built until onEnable() runs.
     */
    private static final List<Supplier<SampleModule>> MODULES = List.of(
            BlockBreakAnnouncerSample::new,  // [SAMPLE] events
            WelcomeSample::new,              // [SAMPLE] replacing vanilla behavior
            PlayerStatsSample::new,          // [SAMPLE] commands + saved data
            MagicWandSample::new             // [SAMPLE] custom items + effects
    );

    private final List<SampleModule> active = new ArrayList<>();

    @Override
    public void onEnable() {
        // Writes the bundled config.yml to plugins/SamplePlugins/config.yml the
        // first time only. Existing files are never overwritten, so player edits
        // survive updates.
        saveDefaultConfig();

        for (Supplier<SampleModule> factory : MODULES) {
            SampleModule module = factory.get();

            // Second off-switch: the config toggle. Defaults to true so a module
            // added later doesn't silently stay dark for existing installs.
            if (!getConfig().getBoolean("samples." + module.id(), true)) {
                getLogger().info("Skipping '" + module.id() + "' (disabled in config.yml)");
                continue;
            }

            try {
                module.enable(this);
                active.add(module);
            } catch (Exception e) {
                // One broken module shouldn't take down the whole plugin. Without
                // this, an exception here aborts onEnable() and every later module
                // never loads.
                getLogger().severe("Module '" + module.id() + "' failed to start: " + e);
                e.printStackTrace();
            }
        }

        getLogger().info("Enabled " + active.size() + " sample module(s)");
    }

    @Override
    public void onDisable() {
        for (SampleModule module : active) {
            module.disable();
        }
        active.clear();
    }
}
