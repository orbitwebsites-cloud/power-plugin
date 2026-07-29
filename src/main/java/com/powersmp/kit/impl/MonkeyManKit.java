package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.mirage.ArmorStandMirageProvider;
import com.powersmp.mirage.MirageProvider;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

/**
 * MonkeyMan4167: the "Light" kit -- mostly passive and conditional buffs, plus Mirage.
 */
public class MonkeyManKit implements PowerKit, Listener {

    public static final String ID = "monkeyman";

    private static final String ABILITY_MIRAGE = "mirage";
    private static final String ABILITY_FLASH = "flash";
    private static final String COOLDOWN_BLIND = "flash_blind";

    private final PowerSMP plugin;
    private final ArmorStandMirageProvider armorStandProvider;
    private MirageProvider mirageProvider;

    // Tuning
    private int flashSpeed;
    private boolean flashOnHit = true;
    private int flashBlindSeconds = 3;
    private double flashBlindCooldown = 5.0d;
    private double flashActivateRadius = 6.0d;
    private double flashActivateCooldown = 30.0d;

    private int sunSpeed;
    private int sunStrength;
    private boolean sunRequiresSky;

    private int mirageCount = 3;
    private int mirageDuration = 12;
    private double mirageCooldown = 60.0d;
    private double mirageRadius = 3.0d;
    private int mirageSpeed = 1;
    private int mirageStrength;
    private boolean mirageFireResistance = true;

    public MonkeyManKit(PowerSMP plugin) {
        this.plugin = plugin;
        this.armorStandProvider = new ArmorStandMirageProvider(plugin);
        this.mirageProvider = armorStandProvider;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Light";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection flash = section.getConfigurationSection("flash");
        if (flash != null) {
            flashSpeed = flash.getInt("speed-amplifier", flashSpeed);
            flashOnHit = !"ON_ACTIVATE".equalsIgnoreCase(flash.getString("mode", "ON_HIT"));
            flashBlindSeconds = flash.getInt("blind-duration-seconds", flashBlindSeconds);
            flashBlindCooldown = flash.getDouble("blind-internal-cooldown-seconds", flashBlindCooldown);
            flashActivateRadius = flash.getDouble("activate-radius", flashActivateRadius);
            flashActivateCooldown = flash.getDouble("activate-cooldown-seconds", flashActivateCooldown);
        }
        ConfigurationSection sun = section.getConfigurationSection("power-of-the-sun");
        if (sun != null) {
            sunSpeed = sun.getInt("speed-amplifier", sunSpeed);
            sunStrength = sun.getInt("strength-amplifier", sunStrength);
            sunRequiresSky = sun.getBoolean("require-sky-access", false);
        }
        ConfigurationSection mirage = section.getConfigurationSection("mirage");
        if (mirage != null) {
            mirageCount = mirage.getInt("clone-count", mirageCount);
            mirageDuration = mirage.getInt("duration-seconds", mirageDuration);
            mirageCooldown = mirage.getDouble("cooldown-seconds", mirageCooldown);
            mirageRadius = mirage.getDouble("spawn-radius", mirageRadius);
            mirageSpeed = mirage.getInt("speed-amplifier", mirageSpeed);
            mirageStrength = mirage.getInt("strength-amplifier", mirageStrength);
            mirageFireResistance = mirage.getBoolean("fire-resistance", true);
            armorStandProvider.configure(
                    mirage.getBoolean("drift", true),
                    mirage.getDouble("drift-blocks-per-second", 0.6d),
                    mirage.getBoolean("wear-owner-armor", true));

            String requested = mirage.getString("provider", "PROTOCOLLIB");
            MirageProvider next;
            if ("ARMOR_STAND".equalsIgnoreCase(requested)) {
                next = armorStandProvider;
            } else if (mirageProvider != armorStandProvider && !protocolProviderDied()) {
                // Reuse the live packet backend. Constructing another one on every config reload
                // registers another USE_ENTITY packet listener and leaks the old listener.
                next = mirageProvider;
            } else {
                next = resolveProvider(requested);
            }
            if (next != mirageProvider) {
                shutdownProvider(mirageProvider);
                mirageProvider = next;
            }
        }

        plugin.cooldowns().registerLabel(ABILITY_MIRAGE, "Mirage");
        plugin.cooldowns().registerLabel(ABILITY_FLASH, "Flash");
    }

    /**
     * Picks the clone backend.
     *
     * <p>The ProtocolLib provider is loaded by name rather than referenced directly, so that a
     * server without ProtocolLib never tries to link its classes -- referencing it normally would
     * throw {@link NoClassDefFoundError} at class-verification time and take the whole kit with it.
     */
    private MirageProvider resolveProvider(String requested) {
        if ("ARMOR_STAND".equalsIgnoreCase(requested)) {
            return armorStandProvider;
        }
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("Mirage is set to '" + requested
                    + "' but ProtocolLib is not installed; using armour stands instead.");
            return armorStandProvider;
        }
        try {
            Class<?> type = Class.forName("com.powersmp.mirage.ProtocolLibMirageProvider");
            MirageProvider provider =
                    (MirageProvider) type.getConstructor(Plugin.class).newInstance(plugin);
            plugin.getLogger().info("Mirage is using ProtocolLib: clones will be real player "
                    + "entities with MonkeyMan4167's skin.");
            return provider;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Could not start the ProtocolLib Mirage backend; "
                    + "falling back to armour stands.", ex);
            return armorStandProvider;
        }
    }

    /** True once the ProtocolLib backend has reported itself broken at runtime. */
    private boolean protocolProviderDied() {
        if (mirageProvider == armorStandProvider) {
            return false;
        }
        try {
            return !(boolean) mirageProvider.getClass().getMethod("isHealthy").invoke(mirageProvider);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- passives -------------------------------------------------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.FLASH)) {
            Effects.refresh(owner, PotionEffectType.SPEED, flashSpeed);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.POWER_OF_THE_SUN) && inDaylight(owner)) {
            Effects.refresh(owner, PotionEffectType.SPEED, Math.max(sunSpeed, flashSpeed));
            Effects.refresh(owner, PotionEffectType.STRENGTH, sunStrength);
        }
    }

    private boolean inDaylight(Player owner) {
        World world = owner.getWorld();
        if (!world.isDayTime()) {
            return false;
        }
        if (!sunRequiresSky) {
            return true;
        }
        Location at = owner.getLocation();
        return world.getHighestBlockYAt(at) <= at.getBlockY();
    }

    // ---- Flash: blind on hit --------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!flashOnHit || !(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!plugin.kits().isOwner(player, ID) || !plugin.unlocks().isUnlocked(player, Power.FLASH)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        // Internal cooldown, or every swing in a combo re-blinds and it never wears off.
        if (!plugin.cooldowns().tryUseSilently(player.getUniqueId(), COOLDOWN_BLIND, flashBlindCooldown)) {
            return;
        }
        Effects.apply(target, PotionEffectType.BLINDNESS, flashBlindSeconds * 20, 0);
        // Particle.FLASH throws IllegalArgumentException ("missing required data class
        // org.bukkit.Color") on this server build -- END_ROD needs no extra data and reads as a
        // bright flash regardless.
        target.getWorld().spawnParticle(Particle.END_ROD, target.getEyeLocation(), 15, 0.2, 0.2, 0.2, 0.1);
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_MIRAGE, "Mirage",
                        "Spawn decoys of yourself and gain Speed, Strength and Fire Resistance."),
                new Ability(ABILITY_FLASH, "Flash",
                        flashOnHit
                                ? "Passive: blinds what you hit. (Set mode to ON_ACTIVATE to fire it manually.)"
                                : "Blind everything around you."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_MIRAGE;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_MIRAGE -> mirage(owner);
            case ABILITY_FLASH -> flash(owner);
            default -> false;
        };
    }

    private boolean mirage(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.MIRAGE)) {
            return plugin.unlocks().denyLocked(owner, Power.MIRAGE);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_MIRAGE, mirageCooldown)) {
            return false;
        }
        int spawned = mirageProvider.spawn(owner, mirageCount, mirageRadius, mirageDuration * 20);
        if (spawned == 0 && protocolProviderDied()) {
            // The packet backend gave up mid-cast; retry on stands so the ability still fires.
            mirageProvider = armorStandProvider;
            spawned = mirageProvider.spawn(owner, mirageCount, mirageRadius, mirageDuration * 20);
        }

        int ticks = mirageDuration * 20;
        Effects.apply(owner, PotionEffectType.SPEED, ticks, mirageSpeed);
        Effects.apply(owner, PotionEffectType.STRENGTH, ticks, mirageStrength);
        if (mirageFireResistance) {
            Effects.apply(owner, PotionEffectType.FIRE_RESISTANCE, ticks, 0);
        }

        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0), 40,
                mirageRadius, 1.0, mirageRadius, 0.02);
        Text.msg(owner, "<light_purple>Mirage</light_purple> <gray>-- " + spawned
                + " decoy(s) for " + mirageDuration + "s.</gray>");
        return true;
    }

    private boolean flash(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.FLASH)) {
            return plugin.unlocks().denyLocked(owner, Power.FLASH);
        }
        if (flashOnHit) {
            Text.msg(owner, "<gray>Flash is passive right now -- it blinds whatever you hit. "
                    + "Set <white>monkeyman.flash.mode</white> to <white>ON_ACTIVATE</white> to fire it manually.</gray>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_FLASH, flashActivateCooldown)) {
            return false;
        }
        int blinded = 0;
        for (org.bukkit.entity.Entity nearby : owner.getNearbyEntities(
                flashActivateRadius, flashActivateRadius, flashActivateRadius)) {
            if (nearby instanceof LivingEntity target && !target.equals(owner)) {
                Effects.apply(target, PotionEffectType.BLINDNESS, flashBlindSeconds * 20, 0);
                blinded++;
            }
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.8f);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0.15);
        Text.msg(owner, "<yellow>Flash</yellow> <gray>-- blinded " + blinded + " nearby.</gray>");
        return true;
    }

    @Override
    public void onDisable() {
        shutdownProvider(mirageProvider);
    }

    private void shutdownProvider(MirageProvider provider) {
        provider.despawnAll();
        // The ProtocolLib backend also holds a packet listener that must be handed back.
        try {
            provider.getClass().getMethod("shutdown").invoke(provider);
        } catch (NoSuchMethodException expected) {
            // The armour-stand backend has nothing extra to release.
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Mirage backend did not shut down cleanly.", ex);
        }
    }

    public MirageProvider provider() {
        return mirageProvider;
    }
}
