package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.BloodlustItem;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * domanthegamer: permanent Resistance/Regeneration, exclusive Locator Bar tracking with
 * replenishing emeralds, and the kill-scaling Altar SMP Bloodlust Sword.
 */
public class DomanKit implements PowerKit, Listener {

    public static final String ID = "domanthegamer";

    private static final String ABILITY_BLOOD_TRAIL = "blood_trail";
    private static final String ABILITY_BLOOD_CHAIN = "blood_chain";

    private final PowerSMP plugin;
    private final Set<UUID> bleeding = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BloodTrailSession> bloodTrails = new ConcurrentHashMap<>();
    private final Set<UUID> dealingBloodTrailDamage = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastBloodSense = new ConcurrentHashMap<>();
    private final Map<UUID, LocatorSnapshot> locatorSnapshots = new HashMap<>();
    private final Map<UUID, Boolean> locatorRuleSnapshots = new HashMap<>();

    private int resistanceAmplifier;
    private int regenerationAmplifier;
    private int minimumEmeralds = 64;
    private double locatorRange = 1024.0d;

    private double bleedChance = 0.15d;
    private double bleedDamage = 1.0d;
    private int bleedPulses = 20;
    private int bleedIntervalTicks = 10;
    private int speedKills = 1;
    private int bloodSenseKills = 2;
    private double bloodSenseRange = 30.0d;
    private int bloodTrailKills = 3;
    private int bloodTrailDurationTicks = 200;
    private double bloodTrailCooldown = 60.0d;
    private int bloodTrailLingerTicks = 100;
    private int bloodTrailPulseTicks = 10;
    private double bloodTrailPointSpacing = 0.75d;
    private double bloodTrailRadius = 1.5d;
    private double bloodTrailDamage = 1.0d;
    private int bloodTrailSlownessTicks = 40;
    private int bloodTrailSlownessAmplifier = 1;
    private int strengthKills = 4;
    private int bloodChainKills = 5;
    private double bloodChainRange = 20.0d;
    private double bloodChainPull = 3.0d;
    private double bloodChainCooldown = 30.0d;

    public DomanKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Blood Tracker";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection passive = section.getConfigurationSection("passive");
            if (passive != null) {
                resistanceAmplifier = levelToAmplifier(
                        passive.getInt("resistance-level", resistanceAmplifier + 1));
                regenerationAmplifier = levelToAmplifier(
                        passive.getInt("regeneration-level", regenerationAmplifier + 1));
            }
            ConfigurationSection tracking = section.getConfigurationSection("tracking");
            if (tracking != null) {
                minimumEmeralds = Math.max(1,
                        tracking.getInt("minimum-emeralds", minimumEmeralds));
                locatorRange = Math.max(1.0d,
                        tracking.getDouble("locator-range", locatorRange));
            }
            ConfigurationSection bloodlust = section.getConfigurationSection("bloodlust");
            if (bloodlust != null) {
                bleedChance = Math.max(0.0d, Math.min(1.0d,
                        bloodlust.getDouble("bleed-chance", bleedChance)));
                bleedDamage = Math.max(0.0d,
                        bloodlust.getDouble("bleed-damage", bleedDamage));
                bleedPulses = Math.max(1,
                        bloodlust.getInt("bleed-pulses", bleedPulses));
                bleedIntervalTicks = Math.max(1,
                        bloodlust.getInt("bleed-interval-ticks", bleedIntervalTicks));
                speedKills = Math.max(0, bloodlust.getInt("speed-kills", speedKills));
                bloodSenseKills = Math.max(0,
                        bloodlust.getInt("blood-sense-kills", bloodSenseKills));
                bloodSenseRange = Math.max(1.0d,
                        bloodlust.getDouble("blood-sense-range", bloodSenseRange));
                bloodTrailKills = Math.max(0,
                        bloodlust.getInt("blood-trail-kills", bloodTrailKills));
                bloodTrailDurationTicks = Math.max(1,
                        (int) Math.round(bloodlust.getDouble(
                                "blood-trail-duration-seconds",
                                bloodTrailDurationTicks / 20.0d) * 20.0d));
                bloodTrailCooldown = Math.max(0.0d,
                        bloodlust.getDouble("blood-trail-cooldown-seconds",
                                bloodTrailCooldown));
                bloodTrailLingerTicks = Math.max(1,
                        (int) Math.round(bloodlust.getDouble(
                                "blood-trail-linger-seconds",
                                bloodTrailLingerTicks / 20.0d) * 20.0d));
                bloodTrailPulseTicks = Math.max(1,
                        bloodlust.getInt("blood-trail-pulse-ticks", bloodTrailPulseTicks));
                bloodTrailPointSpacing = Math.max(0.1d,
                        bloodlust.getDouble("blood-trail-point-spacing",
                                bloodTrailPointSpacing));
                bloodTrailRadius = Math.max(0.1d,
                        bloodlust.getDouble("blood-trail-radius", bloodTrailRadius));
                bloodTrailDamage = Math.max(0.0d,
                        bloodlust.getDouble("blood-trail-damage", bloodTrailDamage));
                bloodTrailSlownessTicks = Math.max(1,
                        (int) Math.round(bloodlust.getDouble(
                                "blood-trail-slowness-seconds",
                                bloodTrailSlownessTicks / 20.0d) * 20.0d));
                bloodTrailSlownessAmplifier = levelToAmplifier(
                        bloodlust.getInt("blood-trail-slowness-level",
                                bloodTrailSlownessAmplifier + 1));
                strengthKills = Math.max(0,
                        bloodlust.getInt("strength-kills", strengthKills));
                bloodChainKills = Math.max(0,
                        bloodlust.getInt("blood-chain-kills", bloodChainKills));
                bloodChainRange = Math.max(1.0d,
                        bloodlust.getDouble("blood-chain-range", bloodChainRange));
                bloodChainPull = Math.max(0.0d,
                        bloodlust.getDouble("blood-chain-pull", bloodChainPull));
                bloodChainCooldown = Math.max(0.0d,
                        bloodlust.getDouble("blood-chain-cooldown-seconds",
                                bloodChainCooldown));
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_BLOOD_TRAIL, "Blood Trail");
        plugin.cooldowns().registerLabel(ABILITY_BLOOD_CHAIN, "Blood Chain");
        for (World world : Bukkit.getWorlds()) {
            enableLocatorBar(world);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyLocatorAccess(player);
        }
    }

    private int levelToAmplifier(int level) {
        return Math.max(0, level - 1);
    }

    @Override
    public void onEnable() {
        if (Attributes.WAYPOINT_RECEIVE_RANGE == null
                || Attributes.WAYPOINT_TRANSMIT_RANGE == null) {
            plugin.getLogger().warning("Locator Bar attributes are unavailable; Doman's Tracking "
                    + "power needs Paper/Minecraft 1.21.6 or newer.");
        }
        for (World world : Bukkit.getWorlds()) {
            enableLocatorBar(world);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyLocatorAccess(player);
        }
    }

    @Override
    public void tick(Player owner) {
        applyPassive(owner);
        applyLocatorAccess(owner);

        if (plugin.unlocks().isUnlocked(owner, Power.TRACKING)) {
            replenishEmeralds(owner);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.BLOODLUST)) {
            ensureBloodlust(owner);
            refreshBloodlust(owner);
        } else {
            clearWeaponEffects(owner);
        }

        if (isBloodTrailCloaked(owner)) {
            owner.getWorld().spawnParticle(Particle.BLOCK,
                    owner.getLocation().add(0.0d, 0.1d, 0.0d), 12,
                    0.35d, 0.05d, 0.35d, Material.REDSTONE_BLOCK.createBlockData());
        }
    }

    private void applyPassive(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.BLOOD_BOUND)) {
            Effects.applyInfinite(owner, PotionEffectType.RESISTANCE, resistanceAmplifier);
            Effects.applyInfinite(owner, PotionEffectType.REGENERATION, regenerationAmplifier);
        } else {
            Effects.remove(owner, PotionEffectType.RESISTANCE);
            Effects.remove(owner, PotionEffectType.REGENERATION);
        }
    }

    private void replenishEmeralds(Player owner) {
        int emeralds = 0;
        for (ItemStack item : owner.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.EMERALD) {
                emeralds += item.getAmount();
            }
        }
        int missing = minimumEmeralds - emeralds;
        while (missing > 0) {
            int amount = Math.min(64, missing);
            Map<Integer, ItemStack> overflow =
                    owner.getInventory().addItem(new ItemStack(Material.EMERALD, amount));
            if (!overflow.isEmpty()) {
                Text.actionBar(owner, "<yellow>Free an inventory slot for your emerald supply.</yellow>");
                return;
            }
            missing -= amount;
        }
    }

    private void ensureBloodlust(Player owner) {
        if (findBloodlust(owner) != null) {
            return;
        }
        ItemStack sword = BloodlustItem.create(
                owner.getUniqueId(), plugin.data().get(owner.getUniqueId()).bloodlustKills());
        if (!owner.getInventory().addItem(sword).isEmpty()) {
            Text.actionBar(owner, "<yellow>Free an inventory slot for Bloodlust.</yellow>");
        } else {
            Text.msg(owner, "<dark_red>The <red><bold>Bloodlust Sword</bold></red> answers your call.</dark_red>");
        }
    }

    private ItemStack findBloodlust(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (owner.getUniqueId().equals(BloodlustItem.ownerOf(item))) {
                return item;
            }
        }
        return null;
    }

    private boolean holdingBloodlust(Player owner) {
        return owner.getUniqueId().equals(
                BloodlustItem.ownerOf(owner.getInventory().getItemInMainHand()));
    }

    private void refreshBloodlust(Player owner) {
        int kills = plugin.data().get(owner.getUniqueId()).bloodlustKills();
        ItemStack sword = findBloodlust(owner);
        int itemKills = BloodlustItem.killsOf(sword);
        if (itemKills > kills) {
            kills = itemKills;
            plugin.data().get(owner.getUniqueId()).bloodlustKills(kills);
            plugin.data().markDirty();
        }
        if (sword != null && itemKills != kills) {
            BloodlustItem.update(sword, kills);
        }
        if (!holdingBloodlust(owner)) {
            clearWeaponEffects(owner);
            return;
        }
        if (kills >= speedKills) {
            Effects.applyInfinite(owner, PotionEffectType.SPEED, 1);
        } else {
            Effects.remove(owner, PotionEffectType.SPEED);
        }
        if (kills >= strengthKills) {
            Effects.applyInfinite(owner, PotionEffectType.STRENGTH, 0);
        } else {
            Effects.remove(owner, PotionEffectType.STRENGTH);
        }
        if (kills >= bloodSenseKills) {
            long now = System.currentTimeMillis();
            long last = lastBloodSense.getOrDefault(owner.getUniqueId(), 0L);
            if (now - last >= 5000L) {
                lastBloodSense.put(owner.getUniqueId(), now);
                showBloodSense(owner);
            }
        }
    }

    private void clearWeaponEffects(Player owner) {
        Effects.remove(owner, PotionEffectType.SPEED);
        Effects.remove(owner, PotionEffectType.STRENGTH);
    }

    private void showBloodSense(Player owner) {
        Player nearest = null;
        double nearestDistance = bloodSenseRange * bloodSenseRange;
        for (Player candidate : owner.getWorld().getPlayers()) {
            if (candidate.equals(owner) || candidate.isDead()
                    || !TeamRules.canAffect(owner, candidate)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(owner.getLocation());
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return;
        }
        Location start = owner.getEyeLocation();
        Vector offset = nearest.getEyeLocation().toVector().subtract(start.toVector());
        double distance = offset.length();
        Vector step = offset.normalize().multiply(0.8d);
        for (double travelled = 0.8d; travelled < distance; travelled += 0.8d) {
            owner.spawnParticle(Particle.DAMAGE_INDICATOR,
                    start.clone().add(step.clone().multiply(travelled / 0.8d)),
                    1, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBloodlustHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player owner)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (dealingBloodTrailDamage.contains(owner.getUniqueId())) {
            return;
        }
        // Any real melee hit ends the cloak. Trail pulses are marked above so their attributed
        // damage does not immediately cancel the ability that created them.
        if (plugin.kits().isOwner(owner, ID) && isBloodTrailCloaked(owner)) {
            revealBloodTrail(owner);
        }
        if (!plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.BLOODLUST)
                || !holdingBloodlust(owner)
                || !TeamRules.canAffect(owner, target)
                || bleeding.contains(target.getUniqueId())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= bleedChance) {
            return;
        }
        startBleeding(owner, target);
    }

    private void startBleeding(Player owner, LivingEntity target) {
        UUID id = target.getUniqueId();
        bleeding.add(id);
        target.getWorld().spawnParticle(Particle.BLOCK,
                target.getLocation().add(0.0d, 1.0d, 0.0d), 15,
                0.25d, 0.5d, 0.25d, Material.REDSTONE_BLOCK.createBlockData());
        new BukkitRunnable() {
            private int pulses;

            @Override
            public void run() {
                if (!target.isValid() || target.isDead() || pulses++ >= bleedPulses
                        || !TeamRules.canAffect(owner, target)) {
                    bleeding.remove(id);
                    cancel();
                    return;
                }
                target.damage(bleedDamage, owner);
                target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                        target.getLocation().add(0.0d, 1.0d, 0.0d),
                        4, 0.2d, 0.35d, 0.2d, 0.0d);
            }
        }.runTaskTimer(plugin, bleedIntervalTicks, bleedIntervalTicks);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBloodTrailDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && isBloodTrailCloaked(player)) {
            event.setCancelled(true);
        }
    }

    private boolean bloodTrail(Player owner) {
        // A reloaded/legacy Bloodlust can carry newer kill progress than the data file. Sync it
        // before checking the unlock so Blood Trail does not remain falsely locked until the
        // next passive refresh tick.
        refreshBloodlust(owner);
        int kills = plugin.data().get(owner.getUniqueId()).bloodlustKills();
        if (!canUseBloodlust(owner, bloodTrailKills, "Blood Trail", kills)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_BLOOD_TRAIL, bloodTrailCooldown)) {
            return false;
        }
        endBloodTrail(owner);
        UUID id = owner.getUniqueId();
        BloodTrailSession session = new BloodTrailSession();
        bloodTrails.put(id, session);
        cloakBloodTrail(owner);
        owner.getWorld().playSound(owner.getLocation(),
                Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.6f, 0.55f);
        Text.actionBar(owner, "<dark_red><bold>BLOOD TRAIL</bold></dark_red> <gray>Attack to emerge.</gray>");
        session.task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> tickBloodTrail(owner, session), 0L, bloodTrailPulseTicks);
        return true;
    }

    private void tickBloodTrail(Player owner, BloodTrailSession session) {
        UUID ownerId = owner.getUniqueId();
        if (bloodTrails.get(ownerId) != session) {
            cancel(session.task);
            return;
        }
        if (!owner.isOnline() || owner.isDead()) {
            endBloodTrail(owner);
            return;
        }

        if (session.cloaked && session.elapsedTicks >= bloodTrailDurationTicks) {
            revealBloodTrail(owner);
        }
        if (session.cloaked) {
            addBloodTrailPoint(session, owner.getLocation());
            // hidePlayer is persistent, but repeating the self-only equipment packet keeps Doman's
            // own third-person model armorless if equipment changes during the ability.
            hideArmorFrom(owner, owner);
        }

        session.points.removeIf(point -> point.expiresAtTick() <= session.elapsedTicks);
        affectBloodTrailTargets(owner, session);
        session.elapsedTicks += bloodTrailPulseTicks;

        if (!session.cloaked && session.points.isEmpty()) {
            finishBloodTrail(owner, session);
        }
    }

    private void addBloodTrailPoint(BloodTrailSession session, Location location) {
        Location previous = session.lastPoint;
        if (previous != null && previous.getWorld().equals(location.getWorld())
                && previous.distanceSquared(location)
                < bloodTrailPointSpacing * bloodTrailPointSpacing) {
            int lastIndex = session.points.size() - 1;
            if (lastIndex >= 0) {
                TrailPoint last = session.points.get(lastIndex);
                session.points.set(lastIndex, new TrailPoint(
                        last.location(), session.elapsedTicks + bloodTrailLingerTicks));
            }
            return;
        }
        Location point = location.clone().add(0.0d, 0.1d, 0.0d);
        session.lastPoint = location.clone();
        session.points.add(new TrailPoint(
                point, session.elapsedTicks + bloodTrailLingerTicks));
    }

    private void affectBloodTrailTargets(Player owner, BloodTrailSession session) {
        Set<UUID> affected = new HashSet<>();
        for (TrailPoint point : session.points) {
            Location location = point.location();
            location.getWorld().spawnParticle(Particle.BLOCK, location, 4,
                    0.2d, 0.04d, 0.2d, Material.REDSTONE_BLOCK.createBlockData());
            for (Entity entity : location.getWorld().getNearbyEntities(
                    location, bloodTrailRadius, 1.0d, bloodTrailRadius)) {
                if (!(entity instanceof LivingEntity target)
                        || target.equals(owner)
                        || target.isDead()
                        || !affected.add(target.getUniqueId())
                        || !TeamRules.canAffect(owner, target)) {
                    continue;
                }
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        bloodTrailSlownessTicks,
                        bloodTrailSlownessAmplifier,
                        true,
                        false,
                        true));
                if (bloodTrailDamage > 0.0d) {
                    dealingBloodTrailDamage.add(owner.getUniqueId());
                    try {
                        target.damage(bloodTrailDamage, owner);
                    } finally {
                        dealingBloodTrailDamage.remove(owner.getUniqueId());
                    }
                }
            }
        }
    }

    private boolean isBloodTrailCloaked(Player owner) {
        BloodTrailSession session = bloodTrails.get(owner.getUniqueId());
        return session != null && session.cloaked;
    }

    private void cloakBloodTrail(Player owner) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(owner)) {
                viewer.hidePlayer(plugin, owner);
            }
        }
        hideArmorFrom(owner, owner);
    }

    private void hideArmorFrom(Player viewer, Player owner) {
        Map<EquipmentSlot, ItemStack> emptyArmor = new EnumMap<>(EquipmentSlot.class);
        ItemStack air = new ItemStack(Material.AIR);
        emptyArmor.put(EquipmentSlot.HEAD, air);
        emptyArmor.put(EquipmentSlot.CHEST, air);
        emptyArmor.put(EquipmentSlot.LEGS, air);
        emptyArmor.put(EquipmentSlot.FEET, air);
        viewer.sendEquipmentChange(owner, emptyArmor);
    }

    private void restoreOwnArmor(Player owner) {
        EntityEquipment equipment = owner.getEquipment();
        if (equipment == null) {
            return;
        }
        Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
        armor.put(EquipmentSlot.HEAD, equipment.getHelmet());
        armor.put(EquipmentSlot.CHEST, equipment.getChestplate());
        armor.put(EquipmentSlot.LEGS, equipment.getLeggings());
        armor.put(EquipmentSlot.FEET, equipment.getBoots());
        owner.sendEquipmentChange(owner, armor);
    }

    private void revealBloodTrail(Player owner) {
        BloodTrailSession session = bloodTrails.get(owner.getUniqueId());
        if (session == null || !session.cloaked) {
            return;
        }
        session.cloaked = false;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(owner)) {
                viewer.showPlayer(plugin, owner);
            }
        }
        restoreOwnArmor(owner);
        owner.getWorld().spawnParticle(Particle.BLOCK,
                owner.getLocation().add(0.0d, 0.2d, 0.0d), 25,
                0.4d, 0.2d, 0.4d, Material.REDSTONE_BLOCK.createBlockData());
    }

    private void endBloodTrail(Player owner) {
        BloodTrailSession session = bloodTrails.remove(owner.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.cloaked) {
            session.cloaked = false;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(owner)) {
                    viewer.showPlayer(plugin, owner);
                }
            }
            restoreOwnArmor(owner);
        }
        cancel(session.task);
        dealingBloodTrailDamage.remove(owner.getUniqueId());
    }

    private void finishBloodTrail(Player owner, BloodTrailSession session) {
        if (bloodTrails.remove(owner.getUniqueId(), session)) {
            cancel(session.task);
        }
    }

    private void cancel(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private boolean bloodChain(Player owner) {
        int kills = plugin.data().get(owner.getUniqueId()).bloodlustKills();
        if (!canUseBloodlust(owner, bloodChainKills, "Blood Chain", kills)) {
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_BLOOD_CHAIN, bloodChainCooldown)) {
            return false;
        }

        Location origin = owner.getEyeLocation();
        Vector direction = origin.getDirection().normalize();
        LivingEntity target = null;
        Set<UUID> checked = new HashSet<>();
        for (double distance = 0.5d; distance <= bloodChainRange && target == null; distance += 0.5d) {
            Location point = origin.clone().add(direction.clone().multiply(distance));
            if (!point.getBlock().isPassable()) {
                break;
            }
            owner.getWorld().spawnParticle(Particle.DUST,
                    point, 2, 0.05d, 0.05d, 0.05d, 0.0d,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(145, 0, 0), 1.3f));
            for (Entity entity : owner.getWorld().getNearbyEntities(point, 0.75d, 0.75d, 0.75d)) {
                if (entity instanceof LivingEntity living && !living.equals(owner)
                        && TeamRules.canAffect(owner, living)
                        && checked.add(living.getUniqueId())) {
                    target = living;
                    break;
                }
            }
        }
        if (target == null) {
            Text.actionBar(owner, "<gray>Blood Chain found no target.</gray>");
            return true;
        }
        Vector pull = owner.getLocation().toVector()
                .subtract(target.getLocation().toVector()).normalize().multiply(bloodChainPull);
        pull.setY(Math.max(0.35d, pull.getY()));
        target.setVelocity(pull);
        if (target instanceof Player pulled) {
            MovementExemption.begin(pulled);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> MovementExemption.end(pulled), 15L);
        }
        target.getWorld().playSound(target.getLocation(),
                Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.65f);
        Text.actionBar(owner, "<red><bold>BLOOD CHAIN</bold></red>");
        return true;
    }

    private boolean canUseBloodlust(Player owner, int requiredKills, String ability, int kills) {
        if (!plugin.unlocks().isUnlocked(owner, Power.BLOODLUST)) {
            return plugin.unlocks().denyLocked(owner, Power.BLOODLUST);
        }
        // Keybound abilities should work while the soulbound weapon is anywhere in Doman's
        // inventory. Requiring the main hand made the key appear broken whenever the player was
        // eating, using a shield, or had just changed hotbar slots.
        if (findBloodlust(owner) == null) {
            Text.msg(owner, "<red>You need to carry Bloodlust to use " + ability + ".</red>");
            return false;
        }
        if (kills < requiredKills) {
            Text.msg(owner, "<red>" + ability + " unlocks at <white>" + requiredKills
                    + " Bloodlust kills</white>. You have " + kills + ".");
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer != null && plugin.kits().isOwner(killer, ID)
                && plugin.unlocks().isUnlocked(killer, Power.BLOODLUST)
                && holdingBloodlust(killer)) {
            int kills = plugin.data().get(killer.getUniqueId()).bloodlustKills() + 1;
            plugin.data().get(killer.getUniqueId()).bloodlustKills(kills);
            plugin.data().markDirty();
            ItemStack sword = findBloodlust(killer);
            if (sword != null) {
                BloodlustItem.update(sword, kills);
            }
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.65f);
            Text.msg(killer, "<red>Bloodlust fed: <white>" + kills + "/5 player kills</white>.</red>");
        }
        if (!plugin.kits().isOwner(dead, ID)) {
            return;
        }
        endBloodTrail(dead);
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            if (BloodlustItem.isBloodlust(it.next())) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (BloodlustItem.isBloodlust(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Bloodlust will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (player.isOnline() && plugin.kits().isOwner(player, ID)) {
                applyPassive(player);
                applyLocatorAccess(player);
                if (plugin.unlocks().isUnlocked(player, Power.BLOODLUST)) {
                    ensureBloodlust(player);
                }
            }
        });
    }

    @Override
    public void onJoin(Player owner) {
        applyPassive(owner);
        applyLocatorAccess(owner);
        if (plugin.unlocks().isUnlocked(owner, Power.BLOODLUST)) {
            ensureBloodlust(owner);
        }
    }

    @Override
    public void onQuit(Player owner) {
        endBloodTrail(owner);
        clearWeaponEffects(owner);
        Effects.remove(owner, PotionEffectType.RESISTANCE);
        Effects.remove(owner, PotionEffectType.REGENERATION);
        lastBloodSense.remove(owner.getUniqueId());
        // On assignment reload the player remains online; preserve exclusivity under the new
        // assignment. The PlayerQuitEvent listener restores the real values on an actual logout.
        applyLocatorAccess(owner);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player viewer = event.getPlayer();
            applyLocatorAccess(viewer);
            for (Map.Entry<UUID, BloodTrailSession> entry : bloodTrails.entrySet()) {
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null && !owner.equals(viewer) && entry.getValue().cloaked) {
                    viewer.hidePlayer(plugin, owner);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyQuit(PlayerQuitEvent event) {
        restoreLocatorAccess(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        enableLocatorBar(event.getWorld());
    }

    @Override
    public void onUnlock(Player owner, Power power) {
        if (power.kitId().equals(ID)) {
            applyPassive(owner);
            applyLocatorAccess(owner);
            if (power == Power.BLOODLUST) {
                ensureBloodlust(owner);
            }
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.BLOODLUST) {
            endBloodTrail(owner);
            clearWeaponEffects(owner);
            removeBloodlust(owner);
        }
        if (power.kitId().equals(ID)) {
            applyPassive(owner);
            applyLocatorAccess(owner);
        }
    }

    private void removeBloodlust(Player owner) {
        ItemStack[] contents = owner.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (BloodlustItem.isBloodlust(contents[slot])) {
                owner.getInventory().setItem(slot, null);
            }
        }
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_BLOOD_TRAIL, "Blood Trail",
                        "At 3 Bloodlust kills, vanish with your armor and leave a damaging, "
                                + "slowing trail for 10 seconds or until you attack."),
                new Ability(ABILITY_BLOOD_CHAIN, "Blood Chain",
                        "At 5 Bloodlust kills, grapple a target toward you."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_BLOOD_TRAIL;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(java.util.Locale.ROOT)) {
            case ABILITY_BLOOD_TRAIL -> bloodTrail(owner);
            case ABILITY_BLOOD_CHAIN -> bloodChain(owner);
            default -> false;
        };
    }

    private void applyLocatorAccess(Player player) {
        AttributeInstance transmit = Attributes.WAYPOINT_TRANSMIT_RANGE == null
                ? null : player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);
        AttributeInstance receive = Attributes.WAYPOINT_RECEIVE_RANGE == null
                ? null : player.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE);
        if (transmit == null || receive == null) {
            return;
        }
        locatorSnapshots.computeIfAbsent(player.getUniqueId(),
                ignored -> new LocatorSnapshot(transmit.getBaseValue(), receive.getBaseValue()));
        boolean mayTrack = plugin.kits().isOwner(player, ID)
                && plugin.unlocks().isUnlocked(player, Power.TRACKING);
        transmit.setBaseValue(locatorRange);
        receive.setBaseValue(mayTrack ? locatorRange : 0.0d);
    }

    private void restoreLocatorAccess(Player player) {
        LocatorSnapshot snapshot = locatorSnapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        if (Attributes.WAYPOINT_TRANSMIT_RANGE != null) {
            AttributeInstance transmit = player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);
            if (transmit != null) {
                transmit.setBaseValue(snapshot.transmit());
            }
        }
        if (Attributes.WAYPOINT_RECEIVE_RANGE != null) {
            AttributeInstance receive = player.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE);
            if (receive != null) {
                receive.setBaseValue(snapshot.receive());
            }
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private GameRule<Boolean> locatorRule() {
        GameRule<?> rule = null;

        // Paper 1.21.11 changed GameRule#getByName to a registry lookup. The command spelling
        // "locatorBar" is not a valid registry key (uppercase B), so it returns null there even
        // though that is still the spelling used by /gamerule. Resolve the constant first, then
        // support both the modern registry key and the legacy command name.
        try {
            Object constant = GameRule.class.getField("LOCATOR_BAR").get(null);
            if (constant instanceof GameRule<?> gameRule) {
                rule = gameRule;
            }
        } catch (ReflectiveOperationException ignored) {
            // The constant does not exist before the Locator Bar was introduced.
        }
        if (rule == null) {
            rule = GameRule.getByName("locator_bar");
        }
        if (rule == null) {
            rule = GameRule.getByName("locatorBar");
        }
        if (rule == null || rule.getType() != Boolean.class) {
            return null;
        }

        @SuppressWarnings("unchecked")
        GameRule<Boolean> booleanRule = (GameRule<Boolean>) rule;
        return booleanRule;
    }

    private void enableLocatorBar(World world) {
        GameRule<Boolean> rule = locatorRule();
        if (rule == null) {
            return;
        }
        Boolean previous = world.getGameRuleValue(rule);
        if (previous != null) {
            locatorRuleSnapshots.putIfAbsent(world.getUID(), previous);
        }
        world.setGameRule(rule, true);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            endBloodTrail(player);
            restoreLocatorAccess(player);
            if (plugin.kits().isOwner(player, ID)) {
                clearWeaponEffects(player);
                Effects.remove(player, PotionEffectType.RESISTANCE);
                Effects.remove(player, PotionEffectType.REGENERATION);
            }
        }
        GameRule<Boolean> rule = locatorRule();
        if (rule != null) {
            for (World world : Bukkit.getWorlds()) {
                Boolean previous = locatorRuleSnapshots.get(world.getUID());
                if (previous != null) {
                    world.setGameRule(rule, previous);
                }
            }
        }
        locatorRuleSnapshots.clear();
        for (BloodTrailSession session : bloodTrails.values()) {
            cancel(session.task);
        }
        bloodTrails.clear();
        dealingBloodTrailDamage.clear();
        bleeding.clear();
        lastBloodSense.clear();
    }

    private static final class BloodTrailSession {
        private final List<TrailPoint> points = new ArrayList<>();
        private BukkitTask task;
        private Location lastPoint;
        private int elapsedTicks;
        private boolean cloaked = true;
    }

    private record TrailPoint(Location location, int expiresAtTick) {
    }

    private record LocatorSnapshot(double transmit, double receive) {
    }
}
