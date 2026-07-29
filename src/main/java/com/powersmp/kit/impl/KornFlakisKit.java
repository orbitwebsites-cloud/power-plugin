package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * KornFlakis: one execution a week.
 *
 * <p>{@code /kill <player>} removes a player outright, on a seven-day cooldown. There is no
 * mechanic here beyond that -- the whole design is the enormity of the effect against the length of
 * the wait.
 *
 * <p>Two things this leans on hard. The cooldown is registered as persistent: seven days comfortably
 * outlives any server uptime, so an in-memory timer would hand back a free execution on the next
 * restart or crash. And by default the kill bypasses everything -- armour, Resistance, totems, and
 * NorthOfNowhere's Requiem -- because that is what vanilla {@code /kill} does. Setting
 * {@code bypass-protections: false} routes it through the damage system instead, which gives the
 * target a totem's worth of counterplay.
 */
public class KornFlakisKit implements PowerKit, Listener {

    public static final String ID = "kornflakis";

    public static final String ABILITY_EXECUTE = "kill";

    private final PowerSMP plugin;
    /** victim -> killer, held just long enough to rewrite the death message. */
    private final Map<UUID, UUID> pendingExecutions = new ConcurrentHashMap<>();

    private double cooldownSeconds = 604800.0d;
    private boolean bypassProtections = true;
    private boolean broadcast = true;

    public KornFlakisKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Execution";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection kill = section.getConfigurationSection("kill");
            if (kill != null) {
                cooldownSeconds = kill.getDouble("cooldown-seconds", cooldownSeconds);
                bypassProtections = kill.getBoolean("bypass-protections", true);
                broadcast = kill.getBoolean("broadcast", true);
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_EXECUTE, "Execute");
        // Non-negotiable at this length: a restart must not refund a week-long cooldown.
        plugin.cooldowns().registerPersistent(ABILITY_EXECUTE);
    }

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY_EXECUTE, "Execute",
                "/kill <player> -- kills them outright. " + Text.duration((long) (cooldownSeconds * 1000))
                        + " cooldown."));
    }

    /**
     * Deliberately no primary ability: sneak + right-click must never fire something with a
     * seven-day cooldown by accident.
     */
    @Override
    public String primaryAbilityId() {
        return null;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        if (ABILITY_EXECUTE.equalsIgnoreCase(abilityId)) {
            Text.msg(owner, "<gray>Name your target: <white>/kill <player></white></gray>");
        }
        return false;
    }

    /** @return true if the execution went through. */
    public boolean execute(Player owner, Player target) {
        if (!plugin.unlocks().isUnlocked(owner, Power.KILL_COMMAND)) {
            return plugin.unlocks().denyLocked(owner, Power.KILL_COMMAND);
        }
        if (target.equals(owner)) {
            Text.msg(owner, "<red>Not on yourself -- that would burn the whole cooldown.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_EXECUTE, cooldownSeconds)) {
            return false;
        }

        pendingExecutions.put(target.getUniqueId(), owner.getUniqueId());
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.6f);
        // A week-long cooldown earns the biggest telegraph in the plugin.
        target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0, 1, 0), 80, 0.5, 1.2, 0.5, 0.05);
        target.getWorld().spawnParticle(Particle.SCULK_SOUL, target.getLocation(), 40, 0.6, 0.2, 0.6, 0.02);
        target.getWorld().spawnParticle(Particle.EXPLOSION, target.getLocation().add(0, 1, 0), 1);

        if (bypassProtections) {
            // What vanilla /kill does: straight to zero, through armour, Resistance and totems.
            target.setHealth(0.0d);
        } else {
            target.damage(1000.0d, owner);
        }

        // With protections enabled a totem or another plugin may keep the target alive. Do not
        // leave the pending death-message marker behind to rewrite an unrelated death minutes
        // later, and do not announce an execution that did not happen.
        boolean killed = target.isDead() || target.getHealth() <= 0.0d;
        if (!killed) {
            pendingExecutions.remove(target.getUniqueId());
            Text.msg(owner, "<yellow>" + Text.plain(target.getName())
                    + " survived the execution attempt.</yellow>");
            Text.msg(target, "<yellow>You survived " + Text.plain(owner.getName())
                    + "'s execution attempt.</yellow>");
            return true;
        }

        if (broadcast) {
            Bukkit.broadcast(Text.mm(Text.PREFIX + "<dark_red><bold>EXECUTED</bold></dark_red> <white>"
                    + Text.plain(target.getName()) + "</white> <gray>by</gray> <white>"
                    + Text.plain(owner.getName()) + "</white>"));
        } else {
            Text.msg(owner, "<dark_red>Executed <white>" + Text.plain(target.getName()) + "</white>.");
        }
        return true;
    }

    /** Replaces the bland vanilla death line so the execution reads as what it was. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        UUID killer = pendingExecutions.remove(event.getEntity().getUniqueId());
        if (killer == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(killer);
        String killerName = owner == null ? "KornFlakis" : owner.getName();
        event.deathMessage(Text.mm("<white>" + Text.plain(event.getEntity().getName())
                + "</white> <gray>was executed by</gray> <dark_red>"
                + Text.plain(killerName) + "</dark_red>"));
    }

    @Override
    public void onQuit(Player owner) {
        pendingExecutions.remove(owner.getUniqueId());
    }
}
