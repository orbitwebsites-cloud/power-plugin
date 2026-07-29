package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * domanthegamer: Limit Break.
 *
 * <p>Combat fills an energy meter used by Kamehameha. Ascended Flight is a short flight window
 * with a reusable midair dash, while Final Burst trades a long charge and cooldown for a large
 * point-blank blast.
 */
public class DomanKit implements PowerKit, Listener {

    public static final String ID = "domanthegamer";

    private static final String ABILITY_KAMEHAMEHA = "kamehameha";
    private static final String ABILITY_ASCENDED_FLIGHT = "ascended_flight";
    private static final String ABILITY_FINAL_BURST = "final_burst";

    private final PowerSMP plugin;
    private final Map<UUID, Double> energy = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> kamehamehaCharges = new ConcurrentHashMap<>();
    private final Map<UUID, FlightSession> flightSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlightDash = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> finalBurstCharges = new ConcurrentHashMap<>();
    private final Map<UUID, PotionEffect> previousResistance = new ConcurrentHashMap<>();
    private final Set<UUID> hadNoResistance = ConcurrentHashMap.newKeySet();

    private int strengthAmplifier;
    private int speedAmplifier;
    private double energyMaximum = 100.0d;
    private double energyPerPlayerHit = 20.0d;

    private int kamehamehaChargeTicks = 40;
    private double kamehamehaRange = 35.0d;
    private double kamehamehaDamage = 16.0d;
    private double kamehamehaKnockback = 2.4d;
    private double kamehamehaHitRadius = 0.9d;
    private double kamehamehaCooldown = 35.0d;

    private int flightDurationTicks = 200;
    private int flightSpeedAmplifier = 2;
    private float flightSpeed = 0.18f;
    private double flightDashPower = 2.0d;
    private double flightDashCooldown = 1.0d;
    private double flightCooldown = 25.0d;

    private int finalBurstChargeTicks = 60;
    private double finalBurstRadius = 9.0d;
    private double finalBurstDamage = 18.0d;
    private double finalBurstKnockback = 2.8d;
    private int finalBurstResistanceAmplifier = 2;
    private double finalBurstCooldown = 90.0d;

    public DomanKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Limit Break";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection passive = section.getConfigurationSection("limit-break");
            if (passive != null) {
                strengthAmplifier = Math.max(0,
                        passive.getInt("strength-amplifier", strengthAmplifier));
                speedAmplifier = Math.max(0, passive.getInt("speed-amplifier", speedAmplifier));
                energyMaximum = Math.max(1.0d,
                        passive.getDouble("energy-maximum", energyMaximum));
                energyPerPlayerHit = Math.max(0.0d,
                        passive.getDouble("energy-per-player-hit", energyPerPlayerHit));
            }
            ConfigurationSection beam = section.getConfigurationSection("kamehameha");
            if (beam != null) {
                kamehamehaChargeTicks = Math.max(1,
                        beam.getInt("charge-ticks", kamehamehaChargeTicks));
                kamehamehaRange = Math.max(1.0d, beam.getDouble("range", kamehamehaRange));
                kamehamehaDamage = Math.max(0.0d, beam.getDouble("damage", kamehamehaDamage));
                kamehamehaKnockback = Math.max(0.0d,
                        beam.getDouble("knockback", kamehamehaKnockback));
                kamehamehaHitRadius = Math.max(0.2d,
                        beam.getDouble("hit-radius", kamehamehaHitRadius));
                kamehamehaCooldown = Math.max(0.0d,
                        beam.getDouble("cooldown-seconds", kamehamehaCooldown));
            }
            ConfigurationSection flight = section.getConfigurationSection("ascended-flight");
            if (flight != null) {
                flightDurationTicks = Math.max(1,
                        flight.getInt("duration-seconds", flightDurationTicks / 20) * 20);
                flightSpeedAmplifier = Math.max(0,
                        flight.getInt("speed-amplifier", flightSpeedAmplifier));
                flightSpeed = (float) Math.max(0.0d, Math.min(1.0d,
                        flight.getDouble("fly-speed", flightSpeed)));
                flightDashPower = Math.max(0.0d,
                        flight.getDouble("dash-power", flightDashPower));
                flightDashCooldown = Math.max(0.0d,
                        flight.getDouble("dash-cooldown-seconds", flightDashCooldown));
                flightCooldown = Math.max(0.0d,
                        flight.getDouble("cooldown-seconds", flightCooldown));
            }
            ConfigurationSection burst = section.getConfigurationSection("final-burst");
            if (burst != null) {
                finalBurstChargeTicks = Math.max(1,
                        burst.getInt("charge-ticks", finalBurstChargeTicks));
                finalBurstRadius = Math.max(1.0d,
                        burst.getDouble("radius", finalBurstRadius));
                finalBurstDamage = Math.max(0.0d,
                        burst.getDouble("damage", finalBurstDamage));
                finalBurstKnockback = Math.max(0.0d,
                        burst.getDouble("knockback", finalBurstKnockback));
                finalBurstResistanceAmplifier = Math.max(0,
                        burst.getInt("resistance-amplifier", finalBurstResistanceAmplifier));
                finalBurstCooldown = Math.max(0.0d,
                        burst.getDouble("cooldown-seconds", finalBurstCooldown));
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_KAMEHAMEHA, "Kamehameha");
        plugin.cooldowns().registerLabel(ABILITY_ASCENDED_FLIGHT, "Ascended Flight");
        plugin.cooldowns().registerLabel(ABILITY_FINAL_BURST, "Final Burst");
    }

    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.LIMIT_BREAK)) {
            Effects.remove(owner, PotionEffectType.STRENGTH);
            Effects.remove(owner, PotionEffectType.SPEED);
            return;
        }
        Effects.applyInfinite(owner, PotionEffectType.STRENGTH, strengthAmplifier);
        Effects.applyInfinite(owner, PotionEffectType.SPEED, speedAmplifier);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.getEntity() instanceof Player player
                && plugin.kits().isOwner(player, ID)
                && plugin.unlocks().isUnlocked(player, Power.LIMIT_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker == null || !(event.getEntity() instanceof Player)
                || !plugin.kits().isOwner(attacker, ID)
                || !plugin.unlocks().isUnlocked(attacker, Power.LIMIT_BREAK)) {
            return;
        }
        UUID id = attacker.getUniqueId();
        double filled = Math.min(energyMaximum,
                energy.getOrDefault(id, 0.0d) + energyPerPlayerHit);
        energy.put(id, filled);
        int percent = (int) Math.round(filled / energyMaximum * 100.0d);
        Text.actionBar(attacker, "<aqua>Energy Charge</aqua> <white>" + meter(percent)
                + "</white> <aqua>" + percent + "%</aqua>");
        if (filled >= energyMaximum) {
            attacker.playSound(attacker.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.8f);
        }
    }

    private Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private String meter(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        return "▰".repeat(filled) + "<dark_gray>" + "▱".repeat(10 - filled) + "</dark_gray>";
    }

    private boolean kamehameha(Player owner) {
        UUID id = owner.getUniqueId();
        if (!plugin.unlocks().isUnlocked(owner, Power.LIMIT_BREAK)) {
            return plugin.unlocks().denyLocked(owner, Power.LIMIT_BREAK);
        }
        if (kamehamehaCharges.containsKey(id)) {
            Text.actionBar(owner, "<aqua>Kamehameha is already charging.</aqua>");
            return false;
        }
        double currentEnergy = energy.getOrDefault(id, 0.0d);
        if (currentEnergy < energyMaximum) {
            int percent = (int) Math.round(currentEnergy / energyMaximum * 100.0d);
            Text.msg(owner, "<red>Your Energy Charge is only " + percent
                    + "%. Fill it by hitting players.");
            return false;
        }
        if (!plugin.cooldowns().isReady(id, ABILITY_KAMEHAMEHA)) {
            Text.msg(owner, "<red>Kamehameha is on cooldown for another <white>"
                    + Text.duration(plugin.cooldowns().remainingMillis(id, ABILITY_KAMEHAMEHA))
                    + "</white>.");
            return false;
        }

        Text.msg(owner, "<aqua><bold>KAMEHAMEHA</bold></aqua> <gray>-- charging...</gray>");
        owner.playSound(owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.6f);
        BukkitTask task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (!owner.isOnline() || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.LIMIT_BREAK)) {
                    cancel();
                    kamehamehaCharges.remove(id);
                    return;
                }
                chargeParticles(owner, elapsed, kamehamehaChargeTicks, Color.AQUA);
                if (++elapsed < kamehamehaChargeTicks) {
                    return;
                }
                cancel();
                kamehamehaCharges.remove(id);
                fireKamehameha(owner);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        kamehamehaCharges.put(id, task);
        return true;
    }

    private void fireKamehameha(Player owner) {
        UUID id = owner.getUniqueId();
        energy.put(id, 0.0d);
        plugin.cooldowns().setSeconds(id, ABILITY_KAMEHAMEHA, kamehamehaCooldown);
        Location origin = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.8d));
        Vector direction = origin.getDirection().normalize();
        World world = owner.getWorld();
        Particle.DustOptions blue = new Particle.DustOptions(Color.fromRGB(40, 155, 255), 2.2f);
        Set<UUID> hit = new HashSet<>();

        for (double distance = 0.0d; distance <= kamehamehaRange; distance += 0.45d) {
            Location point = origin.clone().add(direction.clone().multiply(distance));
            if (!point.getBlock().isPassable()) {
                world.spawnParticle(Particle.EXPLOSION, point, 1);
                break;
            }
            world.spawnParticle(Particle.DUST, point, 3,
                    0.14d, 0.14d, 0.14d, 0.0d, blue);
            world.spawnParticle(Particle.END_ROD, point, 1,
                    0.04d, 0.04d, 0.04d, 0.0d);
            for (Entity nearby : world.getNearbyEntities(point, kamehamehaHitRadius,
                    kamehamehaHitRadius, kamehamehaHitRadius)) {
                if (!(nearby instanceof Player target) || target.equals(owner)
                        || !hit.add(target.getUniqueId())) {
                    continue;
                }
                target.damage(kamehamehaDamage, owner);
                Vector launch = direction.clone().multiply(kamehamehaKnockback);
                launch.setY(Math.max(0.45d, launch.getY()));
                target.setVelocity(launch);
                MovementExemption.begin(target);
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> MovementExemption.end(target), 15L);
            }
        }
        world.playSound(owner.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.2f);
        world.playSound(owner.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.5f);
        Text.actionBar(owner, "<aqua><bold>KAMEHAMEHA!</bold></aqua> <gray>"
                + hit.size() + " hit</gray>");
    }

    private boolean ascendedFlight(Player owner) {
        UUID id = owner.getUniqueId();
        if (!plugin.unlocks().isUnlocked(owner, Power.ASCENDED_FLIGHT)) {
            return plugin.unlocks().denyLocked(owner, Power.ASCENDED_FLIGHT);
        }
        if (flightSessions.containsKey(id)) {
            return flightDash(owner);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_ASCENDED_FLIGHT, flightCooldown)) {
            return false;
        }

        FlightSession session = new FlightSession(
                owner.getAllowFlight(), owner.isFlying(), owner.getFlySpeed());
        flightSessions.put(id, session);
        owner.setFallDistance(0.0f);
        Vector stopped = owner.getVelocity();
        if (stopped.getY() < 0.0d) {
            stopped.setY(0.0d);
            owner.setVelocity(stopped);
        }
        owner.setAllowFlight(true);
        owner.setFlying(true);
        owner.setFlySpeed(flightSpeed);
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);
        Text.msg(owner, "<yellow><bold>ASCENDED FLIGHT</bold></yellow> <gray>-- break your limits. "
                + "Activate again to dash.</gray>");

        session.task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (++elapsed > flightDurationTicks || !owner.isOnline()
                        || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.ASCENDED_FLIGHT)) {
                    cancel();
                    endFlight(owner);
                    return;
                }
                // Limit Break already grants infinite Speed I, so a transient refresh would
                // deliberately preserve that infinite effect and never apply Speed III.
                Effects.applyInfinite(owner, PotionEffectType.SPEED, flightSpeedAmplifier);
                owner.setFallDistance(0.0f);
                Location trail = owner.getLocation().add(0.0d, 0.8d, 0.0d);
                owner.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, trail,
                        3, 0.18d, 0.25d, 0.18d, 0.02d);
                owner.getWorld().spawnParticle(Particle.END_ROD, trail,
                        1, 0.1d, 0.15d, 0.1d, 0.0d);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean flightDash(Player owner) {
        UUID id = owner.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMillis = Math.round(flightDashCooldown * 1000.0d);
        long last = lastFlightDash.getOrDefault(id, 0L);
        if (now - last < cooldownMillis) {
            Text.actionBar(owner, "<gray>Midair dash is recharging.</gray>");
            return false;
        }
        lastFlightDash.put(id, now);
        Vector dash = owner.getEyeLocation().getDirection().normalize().multiply(flightDashPower);
        dash.setY(Math.max(-0.2d, dash.getY()));
        owner.setVelocity(dash);
        owner.setFallDistance(0.0f);
        MovementExemption.begin(owner);
        Bukkit.getScheduler().runTaskLater(plugin, () -> MovementExemption.end(owner), 12L);
        owner.getWorld().spawnParticle(Particle.FLASH, owner.getLocation(), 1);
        owner.getWorld().playSound(owner.getLocation(),
                Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.7f);
        return true;
    }

    private void endFlight(Player owner) {
        UUID id = owner.getUniqueId();
        FlightSession session = flightSessions.remove(id);
        lastFlightDash.remove(id);
        if (session == null) {
            return;
        }
        if (session.task != null && !session.task.isCancelled()) {
            session.task.cancel();
        }
        if (session.previousAllowFlight && !owner.getAllowFlight()) {
            owner.setAllowFlight(true);
        }
        if (owner.isFlying() != session.previousFlying) {
            owner.setFlying(session.previousFlying);
        }
        if (!session.previousAllowFlight && owner.getAllowFlight()) {
            owner.setAllowFlight(false);
        }
        owner.setFlySpeed(session.previousFlySpeed);
        Effects.remove(owner, PotionEffectType.SPEED);
        if (plugin.unlocks().isUnlocked(owner, Power.LIMIT_BREAK)) {
            Effects.applyInfinite(owner, PotionEffectType.SPEED, speedAmplifier);
        }
        owner.setFallDistance(0.0f);
        Text.actionBar(owner, "<gray>Ascended Flight ended.</gray>");
    }

    private boolean finalBurst(Player owner) {
        UUID id = owner.getUniqueId();
        if (!plugin.unlocks().isUnlocked(owner, Power.FINAL_BURST)) {
            return plugin.unlocks().denyLocked(owner, Power.FINAL_BURST);
        }
        if (finalBurstCharges.containsKey(id)) {
            Text.actionBar(owner, "<gold>Final Burst is already charging.</gold>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_FINAL_BURST, finalBurstCooldown)) {
            return false;
        }

        PotionEffect resistance = owner.getPotionEffect(PotionEffectType.RESISTANCE);
        if (resistance == null) {
            hadNoResistance.add(id);
        } else {
            previousResistance.put(id, resistance);
        }
        Effects.apply(owner, PotionEffectType.RESISTANCE,
                finalBurstChargeTicks + 5, finalBurstResistanceAmplifier);
        Text.msg(owner, "<gold><bold>FINAL BURST</bold></gold> <gray>-- releasing everything...</gray>");
        owner.playSound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.5f);

        BukkitTask task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (!owner.isOnline() || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.FINAL_BURST)) {
                    cancel();
                    finalBurstCharges.remove(id);
                    restoreResistance(owner);
                    return;
                }
                chargeParticles(owner, elapsed, finalBurstChargeTicks, Color.YELLOW);
                if (++elapsed < finalBurstChargeTicks) {
                    return;
                }
                cancel();
                finalBurstCharges.remove(id);
                releaseFinalBurst(owner);
                restoreResistance(owner);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        finalBurstCharges.put(id, task);
        return true;
    }

    private void releaseFinalBurst(Player owner) {
        Location center = owner.getLocation().add(0.0d, 1.0d, 0.0d);
        List<Player> hit = new ArrayList<>();
        for (Entity entity : owner.getNearbyEntities(
                finalBurstRadius, finalBurstRadius, finalBurstRadius)) {
            if (!(entity instanceof Player target) || target.equals(owner)) {
                continue;
            }
            Vector away = target.getLocation().toVector().subtract(owner.getLocation().toVector());
            if (away.lengthSquared() < 1.0e-4) {
                away = new Vector(0.0d, 1.0d, 0.0d);
            }
            target.damage(finalBurstDamage, owner);
            away.normalize().multiply(finalBurstKnockback);
            away.setY(Math.max(0.8d, away.getY()));
            target.setVelocity(away);
            MovementExemption.begin(target);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> MovementExemption.end(target), 18L);
            hit.add(target);
        }
        World world = owner.getWorld();
        world.spawnParticle(Particle.EXPLOSION, center, 18,
                finalBurstRadius / 3.0d, 1.5d, finalBurstRadius / 3.0d, 0.0d);
        world.spawnParticle(Particle.FLASH, center, 1);
        world.spawnParticle(Particle.END_ROD, center, 180,
                finalBurstRadius / 2.0d, 2.0d, finalBurstRadius / 2.0d, 0.35d);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        world.playSound(center, Sound.ENTITY_WARDEN_ROAR, 1.0f, 1.5f);
        Text.actionBar(owner, "<gold><bold>FINAL BURST!</bold></gold> <gray>"
                + hit.size() + " hit</gray>");
    }

    private void chargeParticles(Player owner, int elapsed, int total, Color color) {
        double progress = Math.min(1.0d, elapsed / (double) total);
        double radius = 1.8d - progress * 1.4d;
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.4f);
        for (int i = 0; i < 6; i++) {
            double angle = elapsed * 0.22d + i * Math.PI * 2.0d / 6.0d;
            Location point = owner.getLocation().add(
                    Math.cos(angle) * radius,
                    0.5d + i * 0.25d,
                    Math.sin(angle) * radius);
            owner.getWorld().spawnParticle(Particle.DUST, point, 1,
                    0.0d, 0.0d, 0.0d, 0.0d, dust);
        }
    }

    private void restoreResistance(Player owner) {
        UUID id = owner.getUniqueId();
        PotionEffect previous = previousResistance.remove(id);
        boolean remove = hadNoResistance.remove(id);
        if (!owner.isOnline()) {
            return;
        }
        if (remove) {
            owner.removePotionEffect(PotionEffectType.RESISTANCE);
        } else if (previous != null) {
            owner.addPotionEffect(previous);
        }
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_KAMEHAMEHA, "Kamehameha",
                        "Spend a full combat-energy meter on a 35-block piercing beam."),
                new Ability(ABILITY_ASCENDED_FLIGHT, "Ascended Flight",
                        "Fly for " + flightDurationTicks / 20
                                + "s. Activate again while flying to dash."),
                new Ability(ABILITY_FINAL_BURST, "Final Burst",
                        "Charge for " + finalBurstChargeTicks / 20
                                + "s, then blast everyone nearby."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_KAMEHAMEHA;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_KAMEHAMEHA -> kamehameha(owner);
            case ABILITY_ASCENDED_FLIGHT -> ascendedFlight(owner);
            case ABILITY_FINAL_BURST -> finalBurst(owner);
            default -> false;
        };
    }

    @Override
    public void onJoin(Player owner) {
        // One-time migration from the replaced spider kit: old web shooters are inert now and
        // should not occupy inventory space forever after an upgrade.
        ItemStack[] contents = owner.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemMeta meta = contents[slot] == null ? null : contents[slot].getItemMeta();
            if (meta != null && meta.getPersistentDataContainer()
                    .has(Keys.WEB_SHOOTER, PersistentDataType.BYTE)) {
                owner.getInventory().setItem(slot, null);
            }
        }
        Effects.remove(owner, PotionEffectType.STRENGTH);
        Effects.remove(owner, PotionEffectType.SPEED);
    }

    @Override
    public void onQuit(Player owner) {
        UUID id = owner.getUniqueId();
        cancel(kamehamehaCharges.remove(id));
        cancel(finalBurstCharges.remove(id));
        endFlight(owner);
        restoreResistance(owner);
        energy.remove(id);
        Effects.remove(owner, PotionEffectType.STRENGTH);
        Effects.remove(owner, PotionEffectType.SPEED);
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        UUID id = owner.getUniqueId();
        if (power == Power.LIMIT_BREAK) {
            cancel(kamehamehaCharges.remove(id));
            energy.remove(id);
            Effects.remove(owner, PotionEffectType.STRENGTH);
            Effects.remove(owner, PotionEffectType.SPEED);
        } else if (power == Power.ASCENDED_FLIGHT) {
            endFlight(owner);
        } else if (power == Power.FINAL_BURST) {
            cancel(finalBurstCharges.remove(id));
            restoreResistance(owner);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                onQuit(player);
            }
        }
        kamehamehaCharges.values().forEach(BukkitTask::cancel);
        finalBurstCharges.values().forEach(BukkitTask::cancel);
        kamehamehaCharges.clear();
        finalBurstCharges.clear();
        flightSessions.clear();
        energy.clear();
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static final class FlightSession {
        private final boolean previousAllowFlight;
        private final boolean previousFlying;
        private final float previousFlySpeed;
        private BukkitTask task;

        private FlightSession(boolean previousAllowFlight, boolean previousFlying, float previousFlySpeed) {
            this.previousAllowFlight = previousAllowFlight;
            this.previousFlying = previousFlying;
            this.previousFlySpeed = previousFlySpeed;
        }
    }
}
