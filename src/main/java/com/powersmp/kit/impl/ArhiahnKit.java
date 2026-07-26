package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * arhiahn: three long-cooldown ults with a wide blast radius.
 *
 * <p>Built last on purpose -- these are the only powers that reach out and take control away from
 * other players, so they sit on top of freeze and cooldown infrastructure already proven by the
 * lower-stakes kits.
 */
public class ArhiahnKit implements PowerKit, Listener {

    public static final String ID = "arhiahn";

    private static final String ABILITY_THE_WORLD = "the_world";
    private static final String ABILITY_MADE_IN_HEAVEN = "made_in_heaven";
    private static final String ABILITY_REQUIEM = "requiem";

    private final PowerSMP plugin;

    /** Owner -> epoch millis until which fall damage is ignored (Made In Heaven). */
    private final Map<UUID, Long> noFallDamage = new ConcurrentHashMap<>();
    /** Owner -> epoch millis until which all damage is nullified (Requiem). */
    private final Map<UUID, Long> invulnerable = new ConcurrentHashMap<>();

    // Tuning
    private double worldRadius = 8.0d;
    private int worldDuration = 9;
    private double worldCooldown = 240.0d;
    private boolean worldAffectsPlayers = true;
    private boolean worldAffectsMobs = true;
    /** False by default: a time-stop you cannot attack into is only half a power. */
    private boolean worldBlocksDamage;

    private int mihDuration = 20;
    private double mihCooldown = 240.0d;
    private double mihRadius = 12.0d;
    private int mihSelfSpeed = 2;
    private int mihSelfHaste = 1;
    private double mihSelfAttackSpeed = 2.0d;
    private boolean mihNoFall = true;
    private int mihOthersSlowness = 1;
    private int mihOthersFatigue = 1;
    private double mihOthersVelocityMultiplier = 0.35d;

    private boolean requiemEnabled;
    private int requiemDuration = 2;
    private double requiemCooldown = 600.0d;

    public ArhiahnKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stand User";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection world = section.getConfigurationSection("the-world");
        if (world != null) {
            worldRadius = world.getDouble("radius", worldRadius);
            worldDuration = world.getInt("duration-seconds", worldDuration);
            worldCooldown = world.getDouble("cooldown-seconds", worldCooldown);
            worldAffectsPlayers = world.getBoolean("affect-players", true);
            worldAffectsMobs = world.getBoolean("affect-mobs", true);
            worldBlocksDamage = world.getBoolean("block-damage-to-frozen", false);
        }
        ConfigurationSection mih = section.getConfigurationSection("made-in-heaven");
        if (mih != null) {
            mihDuration = mih.getInt("duration-seconds", mihDuration);
            mihCooldown = mih.getDouble("cooldown-seconds", mihCooldown);
            mihRadius = mih.getDouble("radius", mihRadius);
            mihSelfSpeed = mih.getInt("self-speed-amplifier", mihSelfSpeed);
            mihSelfHaste = mih.getInt("self-haste-amplifier", mihSelfHaste);
            mihSelfAttackSpeed = mih.getDouble("self-bonus-attack-speed", mihSelfAttackSpeed);
            mihNoFall = mih.getBoolean("self-no-fall-damage", true);
            mihOthersSlowness = mih.getInt("others-slowness-amplifier", mihOthersSlowness);
            mihOthersFatigue = mih.getInt("others-mining-fatigue-amplifier", mihOthersFatigue);
            mihOthersVelocityMultiplier =
                    mih.getDouble("others-velocity-multiplier", mihOthersVelocityMultiplier);
        }
        ConfigurationSection requiem = section.getConfigurationSection("requiem");
        if (requiem != null) {
            requiemEnabled = requiem.getBoolean("enabled", false);
            requiemDuration = requiem.getInt("duration-seconds", requiemDuration);
            requiemCooldown = requiem.getDouble("cooldown-seconds", requiemCooldown);
        }

        plugin.cooldowns().registerLabel(ABILITY_THE_WORLD, "The World");
        plugin.cooldowns().registerLabel(ABILITY_MADE_IN_HEAVEN, "Made In Heaven");
        plugin.cooldowns().registerLabel(ABILITY_REQUIEM, "Requiem");
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        List<Ability> list = new ArrayList<>();
        list.add(new Ability(ABILITY_THE_WORLD, "The World",
                "Stop everything within " + (int) worldRadius + " blocks for " + worldDuration + "s."));
        list.add(new Ability(ABILITY_MADE_IN_HEAVEN, "Made In Heaven",
                "You speed up, everything near you slows down, for " + mihDuration + "s."));
        if (requiemEnabled) {
            list.add(new Ability(ABILITY_REQUIEM, "Requiem",
                    "Nullify all incoming damage for " + requiemDuration + "s."));
        }
        return list;
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_THE_WORLD;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId) {
            case ABILITY_THE_WORLD -> theWorld(owner);
            case ABILITY_MADE_IN_HEAVEN -> madeInHeaven(owner);
            case ABILITY_REQUIEM -> requiem(owner);
            default -> false;
        };
    }

    /** Freezes everything in range except the caster, for exactly the configured duration. */
    private boolean theWorld(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.THE_WORLD)) {
            return plugin.unlocks().denyLocked(owner, Power.THE_WORLD);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_THE_WORLD, worldCooldown)) {
            return false;
        }
        long ticks = worldDuration * 20L;
        int caught = 0;
        for (Entity entity : owner.getNearbyEntities(worldRadius, worldRadius, worldRadius)) {
            if (entity.equals(owner)) {
                continue;
            }
            boolean isPlayer = entity instanceof Player;
            if (isPlayer && !worldAffectsPlayers) {
                continue;
            }
            if (!isPlayer && !worldAffectsMobs) {
                continue;
            }
            // Frozen targets can never deal damage; whether they can take it is the config above.
            plugin.freeze().freeze(entity, ticks, worldBlocksDamage);
            caught++;
            if (entity instanceof Player frozenPlayer) {
                frozenPlayer.showTitle(Title.title(
                        Text.mm("<dark_purple><bold>ZA WARUDO</bold></dark_purple>"),
                        Text.mm("<gray>Time has stopped.</gray>"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
            }
        }

        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 0.5f);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0),
                60, worldRadius / 3.0d, 1.0d, worldRadius / 3.0d, 0.02d);
        Text.msg(owner, "<dark_purple><bold>THE WORLD</bold></dark_purple> <gray>-- "
                + caught + " target(s) stopped for " + worldDuration + "s.</gray>");
        return true;
    }

    /**
     * Made In Heaven, minus the impossible bit.
     *
     * <p>The spec asks to accelerate time for everything except the caster. There is no per-entity
     * tick rate: {@code /tick rate} is a single global server clock, so speeding it up would speed
     * the caster up too, and slowing it down would slow the whole server for everyone -- including
     * players nowhere near the fight. This does the same thing from the other end: the caster gets
     * faster, everything in range gets slower. The relative speed difference, which is the part that
     * is actually felt, is preserved.
     */
    private boolean madeInHeaven(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.MADE_IN_HEAVEN)) {
            return plugin.unlocks().denyLocked(owner, Power.MADE_IN_HEAVEN);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_MADE_IN_HEAVEN, mihCooldown)) {
            return false;
        }
        int ticks = mihDuration * 20;

        Effects.apply(owner, PotionEffectType.SPEED, ticks, mihSelfSpeed);
        Effects.apply(owner, PotionEffectType.HASTE, ticks, mihSelfHaste);
        Attributes.set(owner, Attributes.ATTACK_SPEED, Keys.MIH_ATTACK_SPEED, mihSelfAttackSpeed);
        if (mihNoFall) {
            noFallDamage.put(owner.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
        }

        int slowed = applySlow(owner, ticks);

        // Potions cannot touch arrows, fireballs, TNT or minecarts, so anything non-living in range
        // gets its velocity scaled down every tick instead. This also re-slows anyone who walks into
        // the radius after the cast, rather than only whoever happened to be standing there.
        startSlowField(owner, ticks);

        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.7f);
        Text.msg(owner, "<gold><bold>MADE IN HEAVEN</bold></gold> <gray>-- "
                + slowed + " slowed for " + mihDuration + "s.</gray>");

        // Attribute modifiers do not expire on their own; take the attack speed back afterwards.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.MIH_ATTACK_SPEED), ticks);
        return true;
    }

    /** Applies the Slowness/Mining Fatigue pair to every living thing in range except the caster. */
    private int applySlow(Player owner, int ticks) {
        int slowed = 0;
        for (Entity entity : owner.getNearbyEntities(mihRadius, mihRadius, mihRadius)) {
            if (entity.equals(owner) || !(entity instanceof LivingEntity target)) {
                continue;
            }
            Effects.apply(target, PotionEffectType.SLOWNESS, ticks, mihOthersSlowness);
            Effects.apply(target, PotionEffectType.MINING_FATIGUE, ticks, mihOthersFatigue);
            slowed++;
        }
        return slowed;
    }

    /**
     * Drags everything non-living in range toward a standstill for the duration, and keeps re-slowing
     * living things that wander in. This is the part that sells "the world slowed down" for objects
     * a potion effect can never reach.
     */
    private void startSlowField(Player owner, int ticks) {
        if (mihOthersVelocityMultiplier >= 1.0d) {
            return;
        }
        final int[] elapsed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, taskHandle -> {
            if (elapsed[0] >= ticks || !owner.isOnline()) {
                taskHandle.cancel();
                return;
            }
            elapsed[0]++;
            for (Entity entity : owner.getNearbyEntities(mihRadius, mihRadius, mihRadius)) {
                if (entity.equals(owner)) {
                    continue;
                }
                if (entity instanceof LivingEntity) {
                    // Re-slow on a one-second cadence; every tick would be wasted packets.
                    if (elapsed[0] % 20 == 0) {
                        applySlow(owner, Math.max(40, ticks - elapsed[0]));
                    }
                } else {
                    entity.setVelocity(entity.getVelocity().multiply(mihOthersVelocityMultiplier));
                }
            }
        }, 1L, 1L);
    }

    private boolean requiem(Player owner) {
        if (!requiemEnabled) {
            Text.msg(owner, "<red>Requiem is disabled. It is gated behind marb's approval -- "
                    + "set <white>arhiahn.requiem.enabled</white> to true in kits.yml to turn it on.");
            return false;
        }
        if (!plugin.unlocks().isUnlocked(owner, Power.REQUIEM)) {
            return plugin.unlocks().denyLocked(owner, Power.REQUIEM);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_REQUIEM, requiemCooldown)) {
            return false;
        }
        invulnerable.put(owner.getUniqueId(), System.currentTimeMillis() + requiemDuration * 1000L);
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.4f);
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, owner.getLocation().add(0, 1, 0),
                40, 0.5d, 1.0d, 0.5d, 0.4d);
        Text.msg(owner, "<light_purple><bold>REQUIEM</bold></light_purple> <gray>-- untouchable for "
                + requiemDuration + "s.</gray>");
        return true;
    }

    // ---- damage windows -------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long until = invulnerable.get(id);
        if (until != null) {
            if (until > now) {
                event.setCancelled(true);
                return;
            }
            invulnerable.remove(id);
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Long fallUntil = noFallDamage.get(id);
            if (fallUntil != null) {
                if (fallUntil > now) {
                    event.setCancelled(true);
                } else {
                    noFallDamage.remove(id);
                }
            }
        }
    }

    @Override
    public void onQuit(Player owner) {
        noFallDamage.remove(owner.getUniqueId());
        invulnerable.remove(owner.getUniqueId());
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.MIH_ATTACK_SPEED);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.MIH_ATTACK_SPEED);
            }
        }
        noFallDamage.clear();
        invulnerable.clear();
    }

    public boolean requiemEnabled() {
        return requiemEnabled;
    }
}
