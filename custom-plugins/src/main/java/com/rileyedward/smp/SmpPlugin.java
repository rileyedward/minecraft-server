package com.rileyedward.smp;

import com.rileyedward.smp.core.Feature;
import com.rileyedward.smp.features.speedrun.SpeedrunFeature;
import com.rileyedward.smp.features.wand.WandFeature;
import com.rileyedward.smp.features.welcome.WelcomeFeature;
import com.rileyedward.smp.features.worlds.WorldsFeature;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Plugin entry point.
 *
 * <p>
 * Paper finds this class through the {@code main:} field in plugin.yml,
 * constructs
 * it, and calls {@link #onEnable()} at startup and {@link #onDisable()} at
 * shutdown.
 * You never instantiate it yourself — the server owns the lifecycle.
 */
public final class SmpPlugin extends JavaPlugin {

  /**
   * ── THE FEATURE LIST ─────────────────────────────────────────────────────
   *
   * Every feature the server runs. To add one: create its package under
   * {@code features/}, then add a line here.
   *
   * These are constructor references ({@code ClassName::new}), not instances —
   * nothing is built until onEnable() runs.
   */
  private static final List<Supplier<Feature>> FEATURES = List.of(
      WelcomeFeature::new,
      WandFeature::new,
      WorldsFeature::new,
      SpeedrunFeature::new);

  private final List<Feature> active = new ArrayList<>();

  @Override
  public void onEnable() {
    // Writes the bundled config.yml to plugins/CustomPlugins/config.yml the
    // first time only. Existing files are never overwritten, so edits survive
    // updates.
    saveDefaultConfig();
    for (Supplier<Feature> factory : FEATURES) {
      Feature feature = factory.get();

      if (!getConfig().getBoolean("features." + feature.id(), true)) {
        getLogger().info("Skipping '" + feature.id() + "' (disabled in config.yml)");
        continue;
      }

      try {
        feature.enable(this);
        active.add(feature);
      } catch (Exception e) {
        // One broken feature shouldn't take down the whole plugin. Without
        // this, an exception here aborts onEnable() and every later feature
        // never loads.
        getLogger().severe("Feature '" + feature.id() + "' failed to start: " + e);
        e.printStackTrace();
      }
    }

    getLogger().info("Enabled " + active.size() + " feature(s)");
  }

  @Override
  public void onDisable() {
    for (Feature feature : active) {
      feature.disable();
    }
    active.clear();
  }
}
