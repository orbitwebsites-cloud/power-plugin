package com.powersmp.combat;

import com.powersmp.PowerSMP;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Text;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Post-death invincibility: for a set window after respawning, nothing can hurt you.
 *
 * <p>Server-wide, not tied to any kit -- every player gets it. The window resets on each death, not
 * stacked, so dying twice in a row does not double the protection.
 */
public class RespawnGuard implements Listener {

    private final PowerSMP plugin;
    /** player -> the timestamp their invincibility ends. */
    private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private double durationSeconds = 180.0d;
    private boolean message = true;

    private BukkitTask expiryTask;

    public RespawnGuard(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            enabled = section.getBoolean("enabled", true);
            durationSeconds = Math.max(0.0d, section.getDouble("duration-seconds", durationSeconds));
            message = section.getBoolean("message", true);
        }
    }

    /** Polls once a second for windows that just ran out, purely to send the "protection ended" line. */
    public void start() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : protectedUntil.entrySet()) {
                if (entry.getValue() > now) {
                    continue;
                }
                protectedUntil.remove(entry.getKey(), entry.getValue());
                Player player = Bukkit.getPlayer(entry.getKey());
                if (message && player != null && player.isOnline()) {
                    Text.msg(player, "<gray>Your respawn protection has worn off.</gray>");
                }
            }
        }, 20L, 20L);
    }

    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        protectedUntil.clear();
    }

    public boolean isProtected(Player player) {
        Long until = protectedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!enabled || durationSeconds <= 0.0d) {
            return;
        }
        Player player = event.getPlayer();
        protectedUntil.put(player.getUniqueId(), System.currentTimeMillis() + (long) (durationSeconds * 1000.0d));
        if (message) {
            Text.msg(player, "<green>You are protected from damage for <white>"
                    + (int) durationSeconds + "s</white>. <gray>You cannot attack players during protection.</gray>");
        }
    }

    /**
     * LOWEST so kit-specific damage handlers (which mostly run at MONITOR) still see the event and
     * can react to it being cancelled -- this is meant to look like the hit never landed at all.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isProtected(player)) {
            return;
        }
        event.setCancelled(true);
    }

    /** Protection cannot be used to attack players without risk of retaliation. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProtectedPlayerAttack(EntityDamageByEntityEvent event) {
        Player attacker = TeamRules.playerSource(event.getDamager());
        if (!(event.getEntity() instanceof Player)
                || attacker == null
                || !isProtected(attacker)) {
            return;
        }
        event.setCancelled(true);
        Text.actionBar(attacker,
                "<red>You cannot attack players during respawn protection.</red>");
    }
}
