package com.powersmp.cooldown;

import com.powersmp.data.DataStore;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One cooldown tracker for every ability in the plugin.
 *
 * <p>Nothing here knows what an ability <em>is</em> -- kits pass an id and a duration. Keeping this
 * generic is the whole point: no kit reimplements "am I off cooldown yet", and the action-bar
 * readout comes for free the moment a kit uses it.
 */
public class CooldownManager {

    private final Plugin plugin;
    /** player -> (ability id -> epoch millis at which it becomes usable again). */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    /** ability id -> label shown on the action bar. */
    private final Map<String, String> labels = new ConcurrentHashMap<>();
    /** Abilities whose cooldown is written through to disk -- see {@link #registerPersistent}. */
    private final Set<String> persistent = ConcurrentHashMap.newKeySet();
    private DataStore store;
    private BukkitTask displayTask;
    /**
     * Cooldowns with more than this left are not drawn on the action bar. Without a ceiling,
     * KornFlakis's 7-day execute and TechKnightGaming's 5-hour restock would each pin a permanent
     * message to their screen. Long cooldowns are reported on use instead.
     */
    private long actionBarMaxMillis = 600_000L;

    public CooldownManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void attachStore(DataStore store) {
        this.store = store;
    }

    public void actionBarMaxSeconds(long seconds) {
        this.actionBarMaxMillis = Math.max(0L, seconds) * 1000L;
    }

    public void registerLabel(String abilityId, String label) {
        labels.put(abilityId, label);
    }

    /**
     * Marks an ability's cooldown as surviving a restart.
     *
     * <p>In-memory tracking is fine for a 10-second cooldown -- nobody restarts a server to skip
     * one. It is not fine for multi-hour cooldowns like TechKnightGaming's 5-hour restock, where a
     * restart (or a crash) would hand back a free use. Those are written through to the data file
     * and rehydrated on join.
     */
    public void registerPersistent(String abilityId) {
        persistent.add(abilityId);
    }

    /** Loads a player's persisted cooldowns into memory. Called on join. */
    public void hydrate(UUID player) {
        if (store == null) {
            return;
        }
        Map<String, Long> saved = store.get(player).cooldowns();
        if (saved.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> target = cooldowns.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, Long> entry : saved.entrySet()) {
            if (entry.getValue() > now) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public boolean isReady(UUID player, String abilityId) {
        return remainingMillis(player, abilityId) <= 0L;
    }

    public long remainingMillis(UUID player, String abilityId) {
        Map<String, Long> forPlayer = cooldowns.get(player);
        if (forPlayer == null) {
            return 0L;
        }
        Long until = forPlayer.get(abilityId);
        if (until == null) {
            return 0L;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            forPlayer.remove(abilityId);
            return 0L;
        }
        return remaining;
    }

    public void set(UUID player, String abilityId, long durationMillis) {
        if (durationMillis <= 0L) {
            return;
        }
        long until = System.currentTimeMillis() + durationMillis;
        cooldowns.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(abilityId, until);
        if (store != null && persistent.contains(abilityId)) {
            store.get(player).cooldowns().put(abilityId, until);
            store.markDirty();
        }
    }

    public void setSeconds(UUID player, String abilityId, double durationSeconds) {
        set(player, abilityId, (long) (durationSeconds * 1000.0d));
    }

    public void clear(UUID player, String abilityId) {
        Map<String, Long> forPlayer = cooldowns.get(player);
        if (forPlayer != null) {
            forPlayer.remove(abilityId);
        }
        if (store != null && persistent.contains(abilityId)) {
            store.get(player).cooldowns().remove(abilityId);
            store.markDirty();
        }
    }

    /** Drops the in-memory copy on quit. Persisted entries stay on disk on purpose. */
    public void clearAll(UUID player) {
        cooldowns.remove(player);
    }

    /**
     * The usual call site: if the ability is ready, start its cooldown and return true. Otherwise
     * tell the player how long is left and return false.
     */
    public boolean tryUse(Player player, String abilityId, double cooldownSeconds) {
        long remaining = remainingMillis(player.getUniqueId(), abilityId);
        if (remaining > 0L) {
            Text.msg(player, "<red>" + label(abilityId) + " is on cooldown for another <white>"
                    + Text.duration(remaining) + "</white>.");
            return false;
        }
        setSeconds(player.getUniqueId(), abilityId, cooldownSeconds);
        return true;
    }

    /** Like {@link #tryUse} but silent -- for passive triggers that fire on every hit. */
    public boolean tryUseSilently(UUID player, String abilityId, double cooldownSeconds) {
        if (remainingMillis(player, abilityId) > 0L) {
            return false;
        }
        setSeconds(player, abilityId, cooldownSeconds);
        return true;
    }

    public String label(String abilityId) {
        return labels.getOrDefault(abilityId, Text.prettify(abilityId));
    }

    public void startDisplay(boolean enabled) {
        if (!enabled || displayTask != null) {
            return;
        }
        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderActionBars, 20L, 10L);
    }

    private void renderActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Long> forPlayer = cooldowns.get(player.getUniqueId());
            if (forPlayer == null || forPlayer.isEmpty()) {
                continue;
            }
            long now = System.currentTimeMillis();
            List<Map.Entry<String, Long>> active = new ArrayList<>();
            for (Iterator<Map.Entry<String, Long>> it = forPlayer.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> entry = it.next();
                long remaining = entry.getValue() - now;
                if (remaining <= 0L) {
                    it.remove();
                } else if (remaining <= actionBarMaxMillis) {
                    active.add(entry);
                }
            }
            if (active.isEmpty()) {
                continue;
            }
            active.sort(Comparator.comparingLong(Map.Entry::getValue));
            StringBuilder bar = new StringBuilder();
            for (Map.Entry<String, Long> entry : active) {
                if (bar.length() > 0) {
                    bar.append("<dark_gray> | </dark_gray>");
                }
                bar.append("<gray>").append(Text.plain(label(entry.getKey()))).append("</gray> ")
                        .append("<aqua>").append(Text.duration(entry.getValue() - now)).append("</aqua>");
            }
            Text.actionBar(player, bar.toString());
        }
    }

    /** Cancels the display task without forgetting anyone's cooldowns -- used across a reload. */
    public void stopDisplay() {
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
    }

    public void shutdown() {
        stopDisplay();
        cooldowns.clear();
    }
}
