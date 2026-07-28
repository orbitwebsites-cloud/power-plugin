package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * disasterflames: Return by Death.
 *
 * <p>Replaces the earlier Sugar Rush concept -- Marb signed off on swapping it out for this instead
 * of an earlier "remove a player" pitch he did not like.
 *
 * <p>A Re:Zero reference in mechanic as well as name: a checkpoint quietly re-anchors to wherever he
 * is at unpredictable moments -- he never chooses when, which is the whole point -- and dying returns
 * him to that last checkpoint rather than his bed or the world spawn. The checkpoint is just his
 * vanilla bed-spawn location, forced without a real bed via {@code setBedSpawnLocation}; the
 * "randomness" is an independent per-tick dice roll rather than a fixed timer, so the gap between
 * saves is exponentially distributed and genuinely unpredictable, not something he could learn to
 * read. One known edge case, left as-is rather than specially guarded against: a checkpoint saved
 * inside the Illusory Realm's temporary arena would be a bad respawn point after the domain closes.
 *
 * <p>All three powers are passive -- there is nothing here to press a button for.
 */
public class ReturnByDeathKit implements PowerKit, Listener {

    public static final String ID = "returnbydeath";

    private final PowerSMP plugin;

    // Keep Inventory
    private boolean keepExperience;

    // Return by Death (the checkpoint)
    private double checkpointAverageIntervalSeconds = 300.0d;

    // Second Wind
    private int respawnStrengthAmplifier;
    private int respawnSpeedAmplifier;
    private int respawnBuffSeconds = 30;

    public ReturnByDeathKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Return by Death";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection keepInventory = section.getConfigurationSection("keep-inventory");
            if (keepInventory != null) {
                keepExperience = keepInventory.getBoolean("keep-experience", keepExperience);
            }
            ConfigurationSection checkpoint = section.getConfigurationSection("return-by-death");
            if (checkpoint != null) {
                checkpointAverageIntervalSeconds = checkpoint.getDouble(
                        "average-interval-seconds", checkpointAverageIntervalSeconds);
            }
            ConfigurationSection secondWind = section.getConfigurationSection("second-wind");
            if (secondWind != null) {
                respawnStrengthAmplifier = secondWind.getInt("strength-amplifier", respawnStrengthAmplifier);
                respawnSpeedAmplifier = secondWind.getInt("speed-amplifier", respawnSpeedAmplifier);
                respawnBuffSeconds = secondWind.getInt("buff-seconds", respawnBuffSeconds);
            }
        }
    }

    // ---- Power 1: Keep Inventory -------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID) || !plugin.unlocks().isUnlocked(player, Power.KEEP_INVENTORY)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(keepExperience);
    }

    // ---- Power 2: Return by Death (the checkpoint) -------------------------

    /** Once per tick, a small independent chance the checkpoint quietly re-anchors to where he is. */
    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RANDOM_CHECKPOINT)) {
            return;
        }
        // Per-tick probability tuned so the *average* gap between saves is the configured interval,
        // while actual timing stays unpredictable -- an exponential distribution, not a clock.
        double perTickChance = 1.0d / Math.max(1.0d, checkpointAverageIntervalSeconds);
        if (ThreadLocalRandom.current().nextDouble() >= perTickChance) {
            return;
        }
        Location here = owner.getLocation().clone();
        owner.setBedSpawnLocation(here, true);
        owner.playSound(here, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.6f);
        Text.actionBar(owner, "<dark_purple><italic>...a memory anchors itself here.</italic></dark_purple>");
    }

    // ---- Power 3: Second Wind -----------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!plugin.kits().isOwner(player, ID) || !plugin.unlocks().isUnlocked(player, Power.POST_RESPAWN_VIGOR)) {
            return;
        }
        Effects.apply(player, PotionEffectType.STRENGTH, respawnBuffSeconds * 20, respawnStrengthAmplifier);
        Effects.apply(player, PotionEffectType.SPEED, respawnBuffSeconds * 20, respawnSpeedAmplifier);
    }
}
