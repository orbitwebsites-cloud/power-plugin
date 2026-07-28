package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Phantom: permanent Speed, a plain invisibility toggle, and a stronger timed vanish.
 *
 * <p>Two different flavours of "invisible" on purpose, because vanilla's own Invisibility potion
 * effect does not hide worn armour or held items -- only the entity's own model. Phantom Cloak is
 * that plain effect: fast to toggle, no cooldown, but armour still shows. Full Vanish additionally
 * uses per-viewer {@code hidePlayer}/{@code showPlayer} (the same trick The Ghost's Astral Form
 * uses), which hides the whole client-side entity -- armour included -- for its 30-second window.
 */
public class PhantomKit implements PowerKit, Listener {

    public static final String ID = "phantom";

    private static final String ABILITY_CLOAK = "cloak";
    private static final String ABILITY_VANISH = "vanish";

    private final PowerSMP plugin;
    private final Set<UUID> cloaked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    private int speedAmplifier = 1;
    private int vanishSeconds = 30;
    private double vanishCooldownSeconds = 300.0d;

    public PhantomKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Phantom";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            speedAmplifier = section.getInt("speed-amplifier", speedAmplifier);
            vanishSeconds = section.getInt("vanish-seconds", vanishSeconds);
            vanishCooldownSeconds = section.getDouble("vanish-cooldown-seconds", vanishCooldownSeconds);
        }
        plugin.cooldowns().registerLabel(ABILITY_VANISH, "Full Vanish");
    }

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.PHANTOM_SPEED)) {
            Effects.refresh(owner, PotionEffectType.SPEED, speedAmplifier);
        }
    }

    // ---- Phantom Cloak: plain toggle, armour still visible ---------------

    private boolean toggleCloak(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.PHANTOM_CLOAK)) {
            return plugin.unlocks().denyLocked(owner, Power.PHANTOM_CLOAK);
        }
        UUID id = owner.getUniqueId();
        if (cloaked.remove(id)) {
            if (!vanished.contains(id)) {
                Effects.remove(owner, PotionEffectType.INVISIBILITY);
            }
            Text.msg(owner, "<gray>You uncloak.</gray>");
            owner.playSound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.4f);
            return true;
        }
        cloaked.add(id);
        Effects.applyInfinite(owner, PotionEffectType.INVISIBILITY, 0);
        Text.msg(owner, "<dark_gray>You fade from sight.</dark_gray> <gray>(your gear is still visible)</gray>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 0.8f);
        return true;
    }

    // ---- Full Vanish: hides the whole model, armour included -------------

    private boolean vanish(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.PHANTOM_VANISH)) {
            return plugin.unlocks().denyLocked(owner, Power.PHANTOM_VANISH);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_VANISH, vanishCooldownSeconds)) {
            return false;
        }
        UUID id = owner.getUniqueId();
        vanished.add(id);
        setVisibility(owner, false);
        Effects.applyInfinite(owner, PotionEffectType.INVISIBILITY, 0);
        Text.msg(owner, "<light_purple>You vanish completely for " + vanishSeconds + "s.</light_purple>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.5f);
        owner.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation().add(0.0, 1.0, 0.0),
                40, 0.4, 0.6, 0.4, 0.05);
        Bukkit.getScheduler().runTaskLater(plugin, () -> endVanish(owner), vanishSeconds * 20L);
        return true;
    }

    private void endVanish(Player owner) {
        UUID id = owner.getUniqueId();
        if (!vanished.remove(id) || !owner.isOnline()) {
            return;
        }
        setVisibility(owner, true);
        if (!cloaked.contains(id)) {
            Effects.remove(owner, PotionEffectType.INVISIBILITY);
        }
        Text.msg(owner, "<gray>You are visible again.</gray>");
    }

    private void setVisibility(Player owner, boolean visible) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(owner)) {
                continue;
            }
            if (visible) {
                other.showPlayer(plugin, owner);
            } else {
                other.hidePlayer(plugin, owner);
            }
        }
    }

    /** Covers someone joining while a Phantom is already vanished. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOtherJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(joined) || !plugin.kits().isOwner(online, ID)
                    || !vanished.contains(online.getUniqueId())) {
                continue;
            }
            joined.hidePlayer(plugin, online);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        if (vanished.remove(id)) {
            setVisibility(player, true);
        }
        cloaked.remove(id);
        Effects.remove(player, PotionEffectType.INVISIBILITY);
    }

    // ---- abilities ---------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_CLOAK, "Phantom Cloak",
                        "Toggle plain invisibility. No cooldown, but worn armour stays visible."),
                new Ability(ABILITY_VANISH, "Full Vanish",
                        "Vanish completely, armour included, for " + vanishSeconds + "s."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_CLOAK;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_CLOAK -> toggleCloak(owner);
            case ABILITY_VANISH -> vanish(owner);
            default -> false;
        };
    }

    @Override
    public void onQuit(Player owner) {
        cloaked.remove(owner.getUniqueId());
        vanished.remove(owner.getUniqueId());
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.kits().isOwner(player, ID)) {
                continue;
            }
            if (vanished.remove(player.getUniqueId())) {
                setVisibility(player, true);
            }
            if (cloaked.remove(player.getUniqueId())) {
                Effects.remove(player, PotionEffectType.INVISIBILITY);
            }
        }
    }
}
