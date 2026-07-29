package com.powersmp.progression;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.kit.PowerKit;
import com.powersmp.util.Text;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Decides whether a player currently has a given power, and hands out kill-gated unlocks.
 *
 * <p>Open question #2 (what actually unlocks each tier) is unresolved, so the default config sets
 * {@code progression.unlock-all: true} and every kill-gated power is simply on. Flip that to false
 * and the thresholds in kits.yml start applying, with no code change. Trigger-gated powers -- the
 * advancement ones and Wither Wings -- are never affected by {@code unlock-all}; earning those is
 * the design, not a placeholder.
 */
public class UnlockManager implements Listener {

    private final PowerSMP plugin;
    private final Map<String, Integer> killThresholds = new HashMap<>();
    private boolean unlockAll = true;
    private boolean broadcast = true;

    public UnlockManager(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection section) {
        killThresholds.clear();
        if (section == null) {
            return;
        }
        unlockAll = section.getBoolean("unlock-all", true);
        broadcast = section.getBoolean("broadcast-unlocks", true);
        ConfigurationSection thresholds = section.getConfigurationSection("kill-thresholds");
        if (thresholds != null) {
            for (String key : thresholds.getKeys(false)) {
                killThresholds.put(key.toLowerCase(java.util.Locale.ROOT), thresholds.getInt(key, 0));
            }
        }
    }

    public boolean isUnlocked(Player player, Power power) {
        if (!plugin.kits().isOwner(player, power.kitId())) {
            return false;
        }
        // "All powers are disabled" inside the Illusory Realm -- one choke point rather than
        // teaching all 14 kits about domains.
        if (plugin.realm() != null && plugin.realm().powersSuppressed(player, power)) {
            return false;
        }
        PlayerData data = plugin.data().get(player.getUniqueId());
        if (data.isRevoked(power.id())) {
            return false;
        }
        return switch (power.gate()) {
            case ALWAYS -> true;
            case TRIGGER -> data.hasUnlocked(power.id());
            case KILLS -> unlockAll
                    || data.hasUnlocked(power.id())
                    || data.kills() >= threshold(power);
        };
    }

    /**
     * Tells the player they cannot use something yet. Kept here so every kit words it the same way.
     *
     * @return false, always -- so callers can {@code return denied(...)}.
     */
    public boolean denyLocked(Player player, Power power) {
        Text.msg(player, "<red>You have not unlocked <white>" + power.displayName() + "</white> yet.");
        return false;
    }

    public int threshold(Power power) {
        return killThresholds.getOrDefault(power.id(), 0);
    }

    /** @return true if this call newly unlocked the power. */
    public boolean unlock(Player player, Power power) {
        PlayerData data = plugin.data().get(player.getUniqueId());
        if (data.isRevoked(power.id())) {
            return false;
        }
        if (!data.unlock(power.id())) {
            return false;
        }
        plugin.data().markDirty();

        announceUnlock(player, power);
        return true;
    }

    /** Admin grant: clears an explicit revocation and grants regardless of the normal gate. */
    public boolean grant(Player player, Power power) {
        PlayerData data = plugin.data().get(player.getUniqueId());
        if (!data.grant(power.id())) {
            return false;
        }
        plugin.data().markDirty();
        announceUnlock(player, power);
        return true;
    }

    private void announceUnlock(Player player, Power power) {
        Text.msg(player, "<gradient:#ffd479:#ff7b7b><bold>POWER UNLOCKED</bold></gradient> <white>"
                + Text.plain(power.displayName()) + "</white>");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        if (broadcast) {
            Bukkit.broadcast(Text.mm(Text.PREFIX + "<white>" + Text.plain(player.getName())
                    + "</white> <gray>unlocked</gray> <gold>" + Text.plain(power.displayName()) + "</gold><gray>.</gray>"));
        }

        PowerKit kit = plugin.kits().byId(power.kitId());
        if (kit != null) {
            kit.onUnlock(player, power);
        }
    }

    public boolean revoke(Player player, Power power) {
        PlayerData data = plugin.data().get(player.getUniqueId());
        if (!data.revoke(power.id())) {
            return false;
        }
        plugin.data().markDirty();
        PowerKit kit = plugin.kits().byId(power.kitId());
        if (kit != null) {
            kit.onRevoke(player, power);
        }
        return true;
    }

    public void addKills(Player player, int amount) {
        PlayerData data = plugin.data().get(player.getUniqueId());
        data.kills(data.kills() + amount);
        plugin.data().markDirty();
        checkKillUnlocks(player);
    }

    /** Persists any kill-gated power whose threshold the player has now passed. */
    public void checkKillUnlocks(Player player) {
        if (unlockAll) {
            return;
        }
        PlayerData data = plugin.data().get(player.getUniqueId());
        for (Power power : Power.values()) {
            if (power.gate() != Power.Gate.KILLS || !plugin.kits().isOwner(player, power.kitId())) {
                continue;
            }
            if (!data.isRevoked(power.id())
                    && !data.hasUnlocked(power.id())
                    && data.kills() >= threshold(power)) {
                unlock(player, power);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.equals(event.getEntity())) {
            return;
        }
        if (!plugin.kits().kitsOf(killer).isEmpty()) {
            addKills(killer, 1);
        }
    }
}
