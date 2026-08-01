package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * crazyTNT2cool: a deliberately overpowered Limitless/Six Eyes kit.
 *
 * <p>Infinity is the defensive passive. Six Eyes supplies the permanent perception and physical
 * buffs. The activated half of the kit exposes the complete combat loop: Blue gathers enemies,
 * Red throws them away, Hollow Purple erases a lane, Warp handles movement, Reverse Cursed
 * Technique resets the owner, and Unlimited Void disables everyone nearby.
 */
public class CrazyTNT2CoolKit implements PowerKit, Listener {

    public static final String ID = "crazytnt2cool";

    private static final String ABILITY_BLUE = "limitless_blue";
    private static final String ABILITY_RED = "limitless_red";
    private static final String ABILITY_PURPLE = "hollow_purple";
    private static final String ABILITY_WARP = "limitless_warp";
    private static final String ABILITY_REVERSE = "reverse_cursed_technique";
    private static final String ABILITY_DOMAIN = "unlimited_void";

    private static final DustOptions BLUE_DUST = new DustOptions(Color.fromRGB(35, 145, 255), 1.5f);
    private static final DustOptions RED_DUST = new DustOptions(Color.fromRGB(255, 35, 65), 1.5f);
    private static final DustOptions PURPLE_DUST = new DustOptions(Color.fromRGB(175, 45, 255), 2.0f);

    private final PowerSMP plugin;

    private int sixEyesSpeedAmplifier = 1;
    private int sixEyesHasteAmplifier = 1;
    private int sixEyesRegenerationAmplifier = 0;
    private double infinityRepelPower = 1.2d;

    private double blueRange = 32.0d;
    private double blueRadius = 8.0d;
    private double bluePullPower = 1.8d;
    private double blueDamage = 8.0d;
    private double blueCooldown = 12.0d;

    private double redRange = 28.0d;
    private double redHalfAngleDegrees = 35.0d;
    private double redDamage = 12.0d;
    private double redKnockback = 2.4d;
    private double redCooldown = 18.0d;

    private double purpleRange = 100.0d;
    private double purpleRadius = 4.0d;
    private double purpleDamage = 60.0d;
    private double purpleKnockback = 3.0d;
    private double purpleCooldown = 90.0d;

    private double warpRange = 48.0d;
    private double warpCooldown = 4.0d;

    private double reverseCooldown = 45.0d;

    private double domainRadius = 24.0d;
    private double domainDurationSeconds = 12.0d;
    /** Sure-hit overload damage dealt once per second while trapped. */
    private double domainDamage = 4.0d;
    private double domainCooldown = 180.0d;

    public CrazyTNT2CoolKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "The Honored One";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection sixEyes = section.getConfigurationSection("six-eyes");
            if (sixEyes != null) {
                sixEyesSpeedAmplifier =
                        sixEyes.getInt("speed-amplifier", sixEyesSpeedAmplifier);
                sixEyesHasteAmplifier =
                        sixEyes.getInt("haste-amplifier", sixEyesHasteAmplifier);
                sixEyesRegenerationAmplifier =
                        sixEyes.getInt("regeneration-amplifier", sixEyesRegenerationAmplifier);
            }
            ConfigurationSection infinity = section.getConfigurationSection("infinity");
            if (infinity != null) {
                infinityRepelPower =
                        infinity.getDouble("repel-power", infinityRepelPower);
            }
            ConfigurationSection blue = section.getConfigurationSection("blue");
            if (blue != null) {
                blueRange = blue.getDouble("range", blueRange);
                blueRadius = blue.getDouble("radius", blueRadius);
                bluePullPower = blue.getDouble("pull-power", bluePullPower);
                blueDamage = blue.getDouble("damage", blueDamage);
                blueCooldown = blue.getDouble("cooldown-seconds", blueCooldown);
            }
            ConfigurationSection red = section.getConfigurationSection("red");
            if (red != null) {
                redRange = red.getDouble("range", redRange);
                redHalfAngleDegrees =
                        red.getDouble("half-angle-degrees", redHalfAngleDegrees);
                redDamage = red.getDouble("damage", redDamage);
                redKnockback = red.getDouble("knockback", redKnockback);
                redCooldown = red.getDouble("cooldown-seconds", redCooldown);
            }
            ConfigurationSection purple = section.getConfigurationSection("hollow-purple");
            if (purple != null) {
                // Preserve the overhaul's minimum power even when an existing server still has
                // the older, much weaker values in plugins/PowerSMP/kits.yml.
                purpleRange = Math.max(100.0d, purple.getDouble("range", purpleRange));
                purpleRadius = Math.max(4.0d, purple.getDouble("radius", purpleRadius));
                purpleDamage = Math.max(60.0d, purple.getDouble("damage", purpleDamage));
                purpleKnockback = purple.getDouble("knockback", purpleKnockback);
                purpleCooldown = purple.getDouble("cooldown-seconds", purpleCooldown);
            }
            ConfigurationSection warp = section.getConfigurationSection("warp");
            if (warp != null) {
                warpRange = warp.getDouble("range", warpRange);
                warpCooldown = warp.getDouble("cooldown-seconds", warpCooldown);
            }
            ConfigurationSection reverse = section.getConfigurationSection("reverse-technique");
            if (reverse != null) {
                reverseCooldown = reverse.getDouble("cooldown-seconds", reverseCooldown);
            }
            ConfigurationSection domain = section.getConfigurationSection("unlimited-void");
            if (domain != null) {
                domainRadius = Math.max(24.0d, domain.getDouble("radius", domainRadius));
                domainDurationSeconds = Math.max(12.0d,
                        domain.getDouble("duration-seconds", domainDurationSeconds));
                domainDamage = Math.max(4.0d, domain.getDouble("damage", domainDamage));
                domainCooldown = domain.getDouble("cooldown-seconds", domainCooldown);
            }
        }

        plugin.cooldowns().registerLabel(ABILITY_BLUE, "Cursed Technique Lapse: Blue");
        plugin.cooldowns().registerLabel(ABILITY_RED, "Cursed Technique Reversal: Red");
        plugin.cooldowns().registerLabel(ABILITY_PURPLE, "Hollow Purple");
        plugin.cooldowns().registerLabel(ABILITY_WARP, "Limitless Warp");
        plugin.cooldowns().registerLabel(ABILITY_REVERSE, "Reverse Cursed Technique");
        plugin.cooldowns().registerLabel(ABILITY_DOMAIN, "Domain Expansion: Unlimited Void");
    }

    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SIX_EYES)) {
            return;
        }
        Effects.refresh(owner, PotionEffectType.NIGHT_VISION, 0);
        Effects.refresh(owner, PotionEffectType.SPEED, sixEyesSpeedAmplifier);
        Effects.refresh(owner, PotionEffectType.HASTE, sixEyesHasteAmplifier);
        Effects.refresh(owner, PotionEffectType.REGENERATION, sixEyesRegenerationAmplifier);
    }

    /**
     * Infinity cancels incoming damage. Void damage remains lethal so the passive cannot strand its
     * owner below the world forever.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInfinityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player owner)
                || !plugin.unlocks().isUnlocked(owner, Power.INFINITY)
                || event instanceof EntityDamageByEntityEvent
                || event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        event.setCancelled(true);
        showInfinity(owner);
    }

    /** Physically throws melee attackers away and deletes projectiles stopped by Infinity. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInfinityHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner)
                || !plugin.unlocks().isUnlocked(owner, Power.INFINITY)) {
            return;
        }
        Entity source = event.getDamager();
        event.setCancelled(true);
        Player attacker = TeamRules.playerSource(source);
        if (attacker != null && TeamRules.areTeammates(owner, attacker)) {
            return;
        }
        showInfinity(owner);
        if (source instanceof Projectile projectile) {
            projectile.remove();
            return;
        }
        Vector away = source.getLocation().toVector().subtract(owner.getLocation().toVector());
        if (away.lengthSquared() > 0.0001d) {
            source.setVelocity(away.normalize().multiply(infinityRepelPower).setY(0.35d));
        }
    }

    private void showInfinity(Player owner) {
        Location center = owner.getLocation().add(0.0d, 1.0d, 0.0d);
        owner.getWorld().spawnParticle(
                Particle.DUST, center, 18, 0.7d, 1.0d, 0.7d, 0.0d, BLUE_DUST);
        owner.getWorld().playSound(
                owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.35f, 1.8f);
    }

    private boolean blue(Player owner) {
        if (!unlocked(owner, Power.CURSED_TECHNIQUE_BLUE)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_BLUE, blueCooldown)) {
            return false;
        }

        Location center = aimedPoint(owner, blueRange);
        int caught = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(
                center, blueRadius, blueRadius, blueRadius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner)
                    || !TeamRules.canAffect(owner, target)) {
                continue;
            }
            Vector pull = center.toVector().subtract(target.getLocation().toVector());
            if (pull.lengthSquared() > 0.0001d) {
                target.setVelocity(pull.normalize().multiply(bluePullPower).setY(0.25d));
            }
            target.damage(blueDamage, owner);
            caught++;
        }

        World world = center.getWorld();
        world.spawnParticle(Particle.DUST, center, 120, 1.2d, 1.2d, 1.2d, 0.0d, BLUE_DUST);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 90, 1.4d, 1.4d, 1.4d, 0.15d);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.55f);
        Text.actionBar(owner, "<aqua><bold>CURSED TECHNIQUE LAPSE: BLUE</bold></aqua>"
                + " <gray>-- " + caught + " caught</gray>");
        return true;
    }

    private boolean red(Player owner) {
        if (!unlocked(owner, Power.CURSED_TECHNIQUE_RED)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_RED, redCooldown)) {
            return false;
        }

        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double minimumDot = Math.cos(Math.toRadians(redHalfAngleDegrees));
        int hit = 0;
        for (Entity entity : owner.getNearbyEntities(redRange, redRange, redRange)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner)
                    || !TeamRules.canAffect(owner, target)) {
                continue;
            }
            Vector offset = target.getEyeLocation().toVector().subtract(eye.toVector());
            double distance = offset.length();
            if (distance <= 0.0001d || distance > redRange
                    || offset.normalize().dot(direction) < minimumDot
                    || !owner.hasLineOfSight(target)) {
                continue;
            }
            Vector knockback = target.getLocation().toVector().subtract(owner.getLocation().toVector());
            if (knockback.lengthSquared() > 0.0001d) {
                target.setVelocity(knockback.normalize().multiply(redKnockback).setY(0.55d));
            }
            target.damage(redDamage, owner);
            hit++;
        }

        Location burst = eye.clone().add(direction.multiply(Math.min(6.0d, redRange)));
        World world = owner.getWorld();
        world.spawnParticle(Particle.DUST, burst, 130, 1.0d, 1.0d, 1.0d, 0.0d, RED_DUST);
        world.spawnParticle(Particle.EXPLOSION, burst, 4, 0.6d, 0.6d, 0.6d, 0.0d);
        world.playSound(burst, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.45f);
        Text.actionBar(owner, "<red><bold>CURSED TECHNIQUE REVERSAL: RED</bold></red>"
                + " <gray>-- " + hit + " hit</gray>");
        return true;
    }

    private boolean hollowPurple(Player owner) {
        if (!unlocked(owner, Power.HOLLOW_PURPLE)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_PURPLE, purpleCooldown)) {
            return false;
        }

        Location start = owner.getEyeLocation().clone();
        Vector direction = start.getDirection().normalize();
        Set<UUID> hit = new HashSet<>();
        World world = owner.getWorld();
        world.spawnParticle(Particle.DUST, start, 180, 1.0d, 1.0d, 1.0d, 0.0d, PURPLE_DUST);
        world.spawnParticle(Particle.REVERSE_PORTAL, start, 100, 1.0d, 1.0d, 1.0d, 0.3d);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.65f);
        owner.showTitle(Title.title(
                Text.mm("<gradient:#338cff:#e229ff><bold>HOLLOW PURPLE</bold></gradient>"),
                Text.mm("<dark_purple>Imaginary Technique</dark_purple>")));

        // A fast, visible annihilation sphere. It deliberately passes through blocks: terrain is
        // preserved, but hiding behind a wall does not negate an ultimate technique.
        new BukkitRunnable() {
            private double distance = 1.5d;

            @Override
            public void run() {
                if (!owner.isOnline() || distance > purpleRange) {
                    Location end = start.clone().add(direction.clone()
                            .multiply(Math.min(distance, purpleRange)));
                    world.spawnParticle(Particle.FLASH, end, 1);
                    world.spawnParticle(Particle.EXPLOSION, end, 12,
                            1.5d, 1.5d, 1.5d, 0.0d);
                    world.playSound(end, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.45f);
                    Text.actionBar(owner,
                            "<gradient:#3d8bff:#db35ff><bold>HOLLOW PURPLE</bold></gradient>"
                                    + " <gray>-- " + hit.size() + " erased</gray>");
                    cancel();
                    return;
                }

                // Sweep several points per tick so high projectile speed never leaves collision
                // gaps between server ticks.
                for (int step = 0; step < 3 && distance <= purpleRange; step++, distance += 1.5d) {
                    Location point = start.clone().add(direction.clone().multiply(distance));
                    world.spawnParticle(Particle.DUST, point, 30,
                            purpleRadius * 0.35d, purpleRadius * 0.35d,
                            purpleRadius * 0.35d, 0.0d, PURPLE_DUST);
                    world.spawnParticle(Particle.REVERSE_PORTAL, point, 12,
                            purpleRadius * 0.3d, purpleRadius * 0.3d,
                            purpleRadius * 0.3d, 0.12d);
                    world.spawnParticle(Particle.END_ROD, point, 5,
                            purpleRadius * 0.25d, purpleRadius * 0.25d,
                            purpleRadius * 0.25d, 0.02d);
                    for (Entity entity : world.getNearbyEntities(
                            point, purpleRadius, purpleRadius, purpleRadius)) {
                        if (!(entity instanceof LivingEntity target)
                                || target.equals(owner)
                                || !TeamRules.canAffect(owner, target)
                                || !hit.add(target.getUniqueId())) {
                            continue;
                        }
                        target.setNoDamageTicks(0);
                        target.damage(purpleDamage, owner);
                        target.setVelocity(direction.clone().multiply(purpleKnockback).setY(0.45d));
                        target.getWorld().spawnParticle(Particle.EXPLOSION,
                                target.getLocation().add(0.0d, 1.0d, 0.0d),
                                4, 0.5d, 0.7d, 0.5d, 0.0d);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean warp(Player owner) {
        if (!unlocked(owner, Power.LIMITLESS_WARP)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_WARP, warpCooldown)) {
            return false;
        }

        Location from = owner.getLocation().clone();
        Location destination = safeWarpDestination(owner);
        if (destination == null) {
            plugin.cooldowns().clear(owner.getUniqueId(), ABILITY_WARP);
            Text.msg(owner, "<red>Limitless could not find a safe destination.");
            return false;
        }
        from.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                from.clone().add(0.0d, 1.0d, 0.0d), 60, 0.5d, 0.8d, 0.5d, 0.2d);
        owner.teleport(destination);
        destination.getWorld().spawnParticle(Particle.PORTAL,
                destination.clone().add(0.0d, 1.0d, 0.0d), 70, 0.5d, 0.8d, 0.5d, 0.4d);
        destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.5f);
        Text.actionBar(owner, "<aqua>Limitless Warp</aqua>");
        return true;
    }

    private boolean reverseTechnique(Player owner) {
        if (!unlocked(owner, Power.REVERSE_CURSED_TECHNIQUE)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_REVERSE, reverseCooldown)) {
            return false;
        }

        owner.setHealth(owner.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        owner.setAbsorptionAmount(Math.max(owner.getAbsorptionAmount(), 8.0d));
        owner.setFireTicks(0);
        owner.setFreezeTicks(0);
        for (PotionEffect effect : List.copyOf(owner.getActivePotionEffects())) {
            if (Effects.isHarmful(effect.getType())) {
                owner.removePotionEffect(effect.getType());
            }
        }
        owner.getWorld().spawnParticle(Particle.HEART,
                owner.getLocation().add(0.0d, 1.2d, 0.0d), 30, 0.7d, 0.9d, 0.7d, 0.1d);
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                owner.getLocation().add(0.0d, 1.0d, 0.0d), 60, 0.6d, 0.8d, 0.6d, 0.3d);
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.25f);
        Text.actionBar(owner, "<green><bold>REVERSE CURSED TECHNIQUE</bold></green>");
        return true;
    }

    private boolean unlimitedVoid(Player owner) {
        if (!unlocked(owner, Power.UNLIMITED_VOID)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_DOMAIN, domainCooldown)) {
            return false;
        }

        Set<UUID> caught = new HashSet<>();
        int durationTicks = Math.max(1, (int) Math.round(domainDurationSeconds * 20.0d));
        Location center = owner.getLocation().clone();
        for (Entity entity : owner.getNearbyEntities(domainRadius, domainRadius, domainRadius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner)
                    || !TeamRules.canAffect(owner, target)) {
                continue;
            }
            caught.add(target.getUniqueId());
            if (target instanceof Player player) {
                player.showTitle(Title.title(
                        Text.mm("<dark_aqua><bold>UNLIMITED VOID</bold></dark_aqua>"),
                        Text.mm("<gray>Everything. Everywhere. All at once.</gray>")));
            }
        }

        World world = owner.getWorld();
        world.spawnParticle(Particle.SCULK_SOUL, center.clone().add(0.0d, 1.0d, 0.0d), 400,
                domainRadius / 2.0d, 2.5d, domainRadius / 2.0d, 0.08d);
        world.spawnParticle(Particle.END_ROD, center.clone().add(0.0d, 1.0d, 0.0d), 300,
                domainRadius / 2.0d, 2.5d, domainRadius / 2.0d, 0.04d);
        world.playSound(owner.getLocation(), Sound.ENTITY_WARDEN_ROAR, 2.0f, 0.55f);
        owner.showTitle(Title.title(
                Text.mm("<gradient:#52d7ff:#7c4dff><bold>DOMAIN EXPANSION</bold></gradient>"),
                Text.mm("<white>UNLIMITED VOID</white>")));
        Text.actionBar(owner, "<dark_aqua><bold>UNLIMITED VOID</bold></dark_aqua>"
                + " <gray>-- " + caught.size() + " minds trapped</gray>");

        // Unlimited Void is a sustained sure-hit domain. Victims are held inside its boundary,
        // continuously disabled, and overloaded once per second until the barrier collapses.
        new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                if (!owner.isOnline() || elapsedTicks >= durationTicks) {
                    world.spawnParticle(Particle.FLASH,
                            center.clone().add(0.0d, 1.0d, 0.0d), 2);
                    world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.8f, 0.55f);
                    cancel();
                    return;
                }

                drawDomainRing(world, center, domainRadius, elapsedTicks);
                for (UUID targetId : Set.copyOf(caught)) {
                    Entity entity = plugin.getServer().getEntity(targetId);
                    if (!(entity instanceof LivingEntity target) || !target.isValid()
                            || !target.getWorld().equals(world)) {
                        caught.remove(targetId);
                        continue;
                    }
                    Vector offset = target.getLocation().toVector().subtract(center.toVector());
                    if (offset.lengthSquared() > domainRadius * domainRadius) {
                        Vector inside = offset.lengthSquared() < 0.0001d
                                ? new Vector() : offset.normalize().multiply(domainRadius - 2.0d);
                        Location contained = center.clone().add(inside);
                        contained.setYaw(target.getYaw());
                        contained.setPitch(target.getPitch());
                        target.teleport(contained);
                    }
                    plugin.freeze().stunSeconds(target, 0.75d);
                    Effects.apply(target, PotionEffectType.DARKNESS, 30, 0);
                    Effects.apply(target, PotionEffectType.BLINDNESS, 30, 0);
                    Effects.apply(target, PotionEffectType.NAUSEA, 30, 2);
                    Effects.apply(target, PotionEffectType.WEAKNESS, 30, 4);
                    Effects.apply(target, PotionEffectType.MINING_FATIGUE, 30, 4);
                    if (elapsedTicks % 20 == 0) {
                        target.setNoDamageTicks(0);
                        target.damage(domainDamage, owner);
                        world.spawnParticle(Particle.SCULK_SOUL,
                                target.getLocation().add(0.0d, 1.0d, 0.0d),
                                20, 0.35d, 0.6d, 0.35d, 0.05d);
                    }
                }
                elapsedTicks += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);
        return true;
    }

    private static void drawDomainRing(World world, Location center, double radius, int tick) {
        double rotation = tick * 0.035d;
        for (int i = 0; i < 48; i++) {
            double angle = rotation + (Math.PI * 2.0d * i / 48.0d);
            Location edge = center.clone().add(
                    Math.cos(angle) * radius, 0.25d + (i % 4) * 0.65d,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, edge, 2,
                    0.08d, 0.18d, 0.08d, 0.0d, PURPLE_DUST);
            if (i % 4 == 0) {
                world.spawnParticle(Particle.END_ROD, edge, 1,
                        0.05d, 0.1d, 0.05d, 0.0d);
            }
        }
    }

    private boolean unlocked(Player owner, Power power) {
        return plugin.unlocks().isUnlocked(owner, power)
                || plugin.unlocks().denyLocked(owner, power);
    }

    private Location aimedPoint(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        RayTraceResult trace = owner.getWorld().rayTraceBlocks(
                eye, direction, range, FluidCollisionMode.NEVER, true);
        if (trace != null && trace.getHitPosition() != null) {
            return trace.getHitPosition().toLocation(owner.getWorld());
        }
        return eye.clone().add(direction.multiply(range));
    }

    private Location safeWarpDestination(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        RayTraceResult trace = owner.getWorld().rayTraceBlocks(
                eye, direction, warpRange, FluidCollisionMode.NEVER, true);
        Location requested;
        if (trace != null && trace.getHitPosition() != null) {
            requested = trace.getHitPosition().toLocation(owner.getWorld())
                    .subtract(direction.clone().multiply(1.2d));
        } else {
            requested = eye.clone().add(direction.multiply(warpRange));
        }
        requested.setYaw(owner.getYaw());
        requested.setPitch(owner.getPitch());
        for (int yOffset : new int[]{0, 1, -1, 2, -2}) {
            Location candidate = requested.clone().add(0.0d, yOffset, 0.0d);
            if (candidate.getBlock().isPassable()
                    && candidate.clone().add(0.0d, 1.0d, 0.0d).getBlock().isPassable()
                    && !candidate.clone().add(0.0d, -1.0d, 0.0d).getBlock().isPassable()) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_BLUE, "Cursed Technique Lapse: Blue",
                        "Create a singularity where you are looking; pull and crush nearby enemies."),
                new Ability(ABILITY_RED, "Cursed Technique Reversal: Red",
                        "Detonate repulsive force through the cone in front of you."),
                new Ability(ABILITY_PURPLE, "Hollow Purple",
                        "Erase everything in a devastating purple beam."),
                new Ability(ABILITY_WARP, "Limitless Warp",
                        "Instantly teleport to the safe point you are looking at."),
                new Ability(ABILITY_REVERSE, "Reverse Cursed Technique",
                        "Fully heal, cleanse debuffs, extinguish yourself, and gain absorption."),
                new Ability(ABILITY_DOMAIN, "Domain Expansion: Unlimited Void",
                        "Overload and immobilize every mind nearby."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_BLUE;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_BLUE -> blue(owner);
            case ABILITY_RED -> red(owner);
            case ABILITY_PURPLE -> hollowPurple(owner);
            case ABILITY_WARP -> warp(owner);
            case ABILITY_REVERSE -> reverseTechnique(owner);
            case ABILITY_DOMAIN -> unlimitedVoid(owner);
            default -> false;
        };
    }
}
