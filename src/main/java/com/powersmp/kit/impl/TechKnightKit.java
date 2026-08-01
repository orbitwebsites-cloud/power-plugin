package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.BoundItemListener;
import com.powersmp.item.TitanBladeItem;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.menu.RestockMenu;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * TechKnightGaming: Tech Knight.
 *
 * <p>The legacy soulbound mace, Fortify, Shockwave, and Overload have been retired. Restock, XP
 * bottles, Grapple Shot, the upgraded Bone Blade, and the shield-ignore passive remain available.
 */
public class TechKnightKit implements PowerKit, Listener {

    public static final String ID = "techknight";

    private static final String ABILITY_RESTOCK = "restock";
    private static final String ABILITY_LOADOUT = "loadout";
    private static final String ABILITY_XP = "xp";
    private static final String ABILITY_GRAPPLE = "grapple_shot";
    private static final String ABILITY_SKELETAL_LEAP = "skeletal_leap";
    private static final String ABILITY_BONE_CAGE = "bone_cage";

    private final PowerSMP plugin;
    private final RestockMenu menu;
    private final Map<UUID, BukkitTask> grapples = new ConcurrentHashMap<>();
    private final NamespacedKey boneCageProjectileKey;

    // Tuning
    private double restockCooldown = 18000.0d;
    private final List<ItemStack> restockItems = new ArrayList<>();

    private boolean xpFillInventory = true;
    private int xpMaxStacks = 36;

    private int titanTierTwoKills = 2;
    private int titanTierThreeKills = 10;
    private double titanTierOneDamageBonus = 2.0d;
    private double titanTierTwoDamageBonus = 5.0d;
    private double titanTierThreeDamageBonus = 9.0d;

    private boolean shieldBreakEnabled = true;
    private double shieldBreakChance = 0.5d;

    private double grapplePower = 1.35d;
    private int grapplePulseTicks = 30;
    private double grappleRange = 100.0d;
    private double grappleCooldown = 10.0d;
    private double skeletalLeapCooldown = 30.0d;
    private double skeletalLeapPower = 2.0d;
    private int skeletalLeapSpeedTicks = 80;
    private double boneCageCooldown = 60.0d;
    private double boneCageProjectileSpeed = 4.0d;
    private double boneCageStunSeconds = 5.0d;

    public TechKnightKit(PowerSMP plugin) {
        this.plugin = plugin;
        this.menu = new RestockMenu(plugin);
        this.boneCageProjectileKey = new NamespacedKey(plugin, "bone_cage_projectile");
    }

    /** Registered as a listener by the plugin's main class. */
    public RestockMenu menu() {
        return menu;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tech Knight";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection restock = section.getConfigurationSection("restock");
        restockItems.clear();
        if (restock != null) {
            restockCooldown = restock.getDouble("cooldown-seconds", restockCooldown);
            menu.slots(restock.getInt("slots", 7));
            for (String entry : restock.getStringList("default-items")) {
                ItemStack parsed = parseItem(entry);
                if (parsed != null && !BoundItemListener.isMace(parsed)) {
                    restockItems.add(parsed);
                }
            }
        }

        ConfigurationSection xp = section.getConfigurationSection("xp-bottles");
        if (xp != null) {
            xpFillInventory = xp.getBoolean("fill-inventory", true);
            xpMaxStacks = Math.max(1, xp.getInt("max-stacks", xpMaxStacks));
        }

        ConfigurationSection shieldBreak = section.getConfigurationSection("shield-break");
        if (shieldBreak != null) {
            shieldBreakEnabled = shieldBreak.getBoolean("enabled", true);
            shieldBreakChance = Math.max(0.0d, Math.min(1.0d,
                    shieldBreak.getDouble("chance", shieldBreakChance)));
        }

        ConfigurationSection titan = section.getConfigurationSection("titan-protocol");
        if (titan != null) {
            titanTierTwoKills = Math.max(1,
                    titan.getInt("tier-2-player-kills", titanTierTwoKills));
            titanTierThreeKills = Math.max(titanTierTwoKills + 1,
                    titan.getInt("tier-3-player-kills", titanTierThreeKills));
            titanTierOneDamageBonus = Math.max(0.0d,
                    titan.getDouble("tier-1-damage-bonus", titanTierOneDamageBonus));
            titanTierTwoDamageBonus = Math.max(0.0d,
                    titan.getDouble("tier-2-damage-bonus", titanTierTwoDamageBonus));
            titanTierThreeDamageBonus = Math.max(titanTierTwoDamageBonus,
                    titan.getDouble("tier-3-damage-bonus", titanTierThreeDamageBonus));
        }


        ConfigurationSection grapple = section.getConfigurationSection("grapple-shot");
        if (grapple != null) {
            grapplePower = Math.max(0.0d,
                    grapple.getDouble("pull-power", grapplePower));
            grapplePulseTicks = Math.max(1,
                    grapple.getInt("pulse-ticks", grapplePulseTicks));
            grappleRange = Math.max(1.0d,
                    grapple.getDouble("range", grappleRange));
            grappleCooldown = Math.max(0.0d,
                    grapple.getDouble("cooldown-seconds", grappleCooldown));
        }

        ConfigurationSection boneBlade = section.getConfigurationSection("bone-blade");
        if (boneBlade != null) {
            skeletalLeapCooldown = Math.max(0.0d,
                    boneBlade.getDouble("skeletal-leap-cooldown-seconds", skeletalLeapCooldown));
            skeletalLeapPower = Math.max(0.0d,
                    boneBlade.getDouble("skeletal-leap-power", skeletalLeapPower));
            skeletalLeapSpeedTicks = Math.max(1, (int) Math.round(
                    boneBlade.getDouble("skeletal-leap-speed-seconds",
                            skeletalLeapSpeedTicks / 20.0d) * 20.0d));
            boneCageCooldown = Math.max(0.0d,
                    boneBlade.getDouble("bone-cage-cooldown-seconds", boneCageCooldown));
            boneCageProjectileSpeed = Math.max(0.1d,
                    boneBlade.getDouble("bone-cage-projectile-speed", boneCageProjectileSpeed));
            boneCageStunSeconds = Math.max(0.0d,
                    boneBlade.getDouble("bone-cage-stun-seconds", boneCageStunSeconds));
        }

        plugin.cooldowns().registerLabel(ABILITY_RESTOCK, "Restock");
        // Five hours is far longer than a server uptime; without this a restart is a free use.
        plugin.cooldowns().registerPersistent(ABILITY_RESTOCK);
        plugin.cooldowns().registerLabel(ABILITY_SKELETAL_LEAP, "Skeletal Leap");
        plugin.cooldowns().registerLabel(ABILITY_BONE_CAGE, "Bone Cage");
        plugin.cooldowns().registerLabel(ABILITY_GRAPPLE, "Grapple Shot");
    }

    // ---- Titan Protocol -------------------------------------------------

    @Override
    public void tick(Player owner) {
        applyTitanProtocol(owner);
    }

    @Override
    public void onJoin(Player owner) {
        applyTitanProtocol(owner);
        removeLegacyVulcansCrossbows(owner);
    }

    private void applyTitanProtocol(Player owner) {
        ItemStack blade = ensureTitanBlade(owner);
        if (blade == null) {
            // Inventory passives use short refreshed effects, so they lapse on their own without
            // accidentally deleting a potion or another ability's longer effect.
            return;
        }
        int tier = TitanBladeItem.tierOf(blade);
        if (tier == 2) {
            Effects.refresh(owner, PotionEffectType.STRENGTH, 1);
            Effects.refresh(owner, PotionEffectType.SPEED, 0);
            Effects.refresh(owner, PotionEffectType.FIRE_RESISTANCE, 0);
        }
        if (tier >= 3) {
            Effects.refresh(owner, PotionEffectType.STRENGTH, 2);
            Effects.refresh(owner, PotionEffectType.SPEED, 1);
            Effects.refresh(owner, PotionEffectType.FIRE_RESISTANCE, 0);
            Effects.refresh(owner, PotionEffectType.RESISTANCE, 1);
        }
    }

    private ItemStack ensureTitanBlade(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.TITAN_PROTOCOL)) {
            removeTitanBlades(owner);
            return null;
        }
        int kills = plugin.data().get(owner.getUniqueId()).kills();
        int tier = titanTier(kills);
        ItemStack found = null;
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            ItemStack item = owner.getInventory().getItem(slot);
            if (!owner.getUniqueId().equals(TitanBladeItem.ownerOf(item))) {
                continue;
            }
            if (found != null) {
                owner.getInventory().setItem(slot, null);
                continue;
            }
            found = item;
            int previousTier = TitanBladeItem.tierOf(item);
            TitanBladeItem.update(item, tier, kills, titanTierTwoKills, titanTierThreeKills);
            owner.getInventory().setItem(slot, item);
            if (tier > previousTier) {
                announceTitanUpgrade(owner, tier);
            }
        }
        if (found != null) {
            return found;
        }
        ItemStack blade = TitanBladeItem.create(
                owner.getUniqueId(), tier, kills, titanTierTwoKills, titanTierThreeKills);
        if (!owner.getInventory().addItem(blade).isEmpty()) {
            Text.actionBar(owner, "<yellow>Free an inventory slot for your Bone Blade.</yellow>");
            return null;
        }
        Text.msg(owner, "<gray>Titan Protocol issues your <white><bold>Bone Blade</bold></white>.</gray>");
        owner.playSound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8f, 0.8f);
        return blade;
    }

    /** Removes crossbows issued by older builds without touching ordinary player crossbows. */
    private void removeLegacyVulcansCrossbows(Player owner) {
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            ItemStack item = owner.getInventory().getItem(slot);
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                    .has(Keys.VULCANS_CROSSBOW_OWNER, PersistentDataType.STRING)) {
                owner.getInventory().setItem(slot, null);
            }
        }
        for (int slot = 0; slot < owner.getEnderChest().getSize(); slot++) {
            ItemStack item = owner.getEnderChest().getItem(slot);
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                    .has(Keys.VULCANS_CROSSBOW_OWNER, PersistentDataType.STRING)) {
                owner.getEnderChest().setItem(slot, null);
            }
        }
    }

    private int titanTier(int playerKills) {
        if (playerKills >= titanTierThreeKills) {
            return 3;
        }
        if (playerKills >= titanTierTwoKills) {
            return 2;
        }
        return 1;
    }

    private void announceTitanUpgrade(Player owner, int tier) {
        Text.msg(owner, "<gold><bold>TITAN PROTOCOL — TIER "
                + (tier == 2 ? "II" : "III") + "</bold></gold>");
        owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.9f + tier * 0.1f);
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                owner.getLocation().add(0.0d, 1.0d, 0.0d), 35,
                0.4d, 0.7d, 0.4d, 0.08d);
    }

    private void removeTitanBlades(Player owner) {
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            if (TitanBladeItem.isBoneBlade(owner.getInventory().getItem(slot))) {
                owner.getInventory().setItem(slot, null);
            }
        }
    }

    private void clearTitanEffects(Player owner) {
        Effects.removeIfTransient(owner, PotionEffectType.STRENGTH);
        Effects.removeIfTransient(owner, PotionEffectType.SPEED);
        Effects.removeIfTransient(owner, PotionEffectType.FIRE_RESISTANCE);
        Effects.removeIfTransient(owner, PotionEffectType.RESISTANCE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTitanBladeDeath(PlayerDeathEvent event) {
        if (!plugin.kits().isOwner(event.getEntity(), ID)) {
            return;
        }
        event.getDrops().removeIf(TitanBladeItem::isBoneBlade);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTitanBladeRespawn(PlayerRespawnEvent event) {
        Player owner = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (owner.isOnline() && plugin.kits().isOwner(owner, ID)) {
                applyTitanProtocol(owner);
                removeLegacyVulcansCrossbows(owner);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTitanBladeDrop(PlayerDropItemEvent event) {
        if (TitanBladeItem.isBoneBlade(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(),
                    "<red>Tech Knight signature weapons cannot be dropped.</red>");
        }
    }

    /** Titan Protocol makes the Bone Blade itself hit harder at each unlocked kill tier. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTitanBladeDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player owner)
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.TITAN_PROTOCOL)) {
            return;
        }
        ItemStack blade = owner.getInventory().getItemInMainHand();
        if (!owner.getUniqueId().equals(TitanBladeItem.ownerOf(blade))
                || !(event.getEntity() instanceof LivingEntity target)
                || !TeamRules.canAffect(owner, target)) {
            return;
        }
        double bonus = switch (TitanBladeItem.tierOf(blade)) {
            case 2 -> titanTierTwoDamageBonus;
            case 3 -> titanTierThreeDamageBonus;
            default -> titanTierOneDamageBonus;
        };
        event.setDamage(event.getDamage() + bonus);
    }

    /** Parses {@code "ENDER_PEARL:16"}. */
    private ItemStack parseItem(String entry) {
        String[] parts = entry.split(":", 2);
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null) {
            plugin.getLogger().warning("Unknown restock item '" + entry + "' in kits.yml");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Bad amount in restock entry '" + entry + "'; using 1");
            }
        }
        return new ItemStack(material, amount);
    }

    /**
     * Passive, no cooldown: half the time, TechKnightGaming's melee hit ignores a blocking
     * player's shield.
     *
     * <p>Shield blocking is resolved by the server before this event ever reaches a plugin, so the
     * damage Bukkit hands us here is already reduced. There is no public API to recover the
     * pre-block number, so on a trigger the weapon's own attack-damage attribute is reapplied
     * directly -- a full, unblocked hit in all but name -- and {@code clearActiveItem()} drops the
     * target out of their block stance, which is what actually makes it read as "ignored" rather
     * than just doing more damage while they are still visibly guarding.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldBreak(EntityDamageByEntityEvent event) {
        if (!shieldBreakEnabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player killer) || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.SHIELD_BREAKER)) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !victim.isBlocking()
                || !TeamRules.canAffect(killer, victim)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= shieldBreakChance) {
            return;
        }

        victim.clearActiveItem();
        event.setDamage(Attributes.valueOf(killer, Attributes.ATTACK_DAMAGE, event.getDamage()));

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 0.8f);
        Text.actionBar(killer, "<gold>Shield ignored.</gold>");
        Text.actionBar(victim, "<red>Your shield did nothing.</red>");
    }

    // ---- Grapple Shot ---------------------------------------------------

    private boolean grappleShot(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.GRAPPLE_SHOT)) {
            return plugin.unlocks().denyLocked(owner, Power.GRAPPLE_SHOT);
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double targetDistance = grappleRange;
        RayTraceResult entityTrace = owner.getWorld().rayTraceEntities(
                eye, direction, targetDistance, 0.6d,
                entity -> entity instanceof LivingEntity living
                        && !entity.equals(owner) && TeamRules.canAffect(owner, living));
        Entity target = entityTrace == null ? null : entityTrace.getHitEntity();
        if (target != null && !owner.hasLineOfSight(target)) {
            target = null;
        }
        Location anchor = null;
        if (target == null) {
            org.bukkit.block.Block block = owner.getTargetBlockExact((int) Math.ceil(targetDistance));
            if (block != null) {
                anchor = block.getLocation().add(0.5d, 0.5d, 0.5d);
            }
        }
        if (target == null && anchor == null) {
            Text.msg(owner, "<red>No visible grapple target.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_GRAPPLE, grappleCooldown)) {
            return false;
        }
        UUID id = owner.getUniqueId();
        BukkitTask previous = grapples.remove(id);
        if (previous != null) {
            previous.cancel();
            MovementExemption.end(owner);
        }
        final Entity lockedTarget = target;
        final Location fixedAnchor = anchor;
        MovementExemption.begin(owner);
        owner.playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        BukkitTask task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (++elapsed > grapplePulseTicks || !owner.isOnline()
                        || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.GRAPPLE_SHOT)
                        || (lockedTarget != null && !lockedTarget.isValid())) {
                    stopGrapple(owner, this);
                    return;
                }
                Location destination = lockedTarget == null
                        ? fixedAnchor : lockedTarget.getLocation().add(0.0d, 1.0d, 0.0d);
                Vector pull = destination.toVector().subtract(owner.getLocation().toVector());
                if (pull.lengthSquared() < 2.25d) {
                    stopGrapple(owner, this);
                    return;
                }
                drawGrapple(owner.getEyeLocation(), destination);
                owner.setVelocity(pull.normalize().multiply(grapplePower));
                owner.setFallDistance(0.0f);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        grapples.put(id, task);
        return true;
    }

    private void stopGrapple(Player owner, BukkitRunnable runnable) {
        runnable.cancel();
        grapples.remove(owner.getUniqueId());
        MovementExemption.end(owner);
    }

    private void drawGrapple(Location from, Location to) {
        Vector line = to.toVector().subtract(from.toVector());
        double length = line.length();
        if (length < 1.0e-4) {
            return;
        }
        line.normalize();
        for (double distance = 0.0d; distance <= length; distance += 0.65d) {
            from.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                    from.clone().add(line.clone().multiply(distance)),
                    1, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    @Override
    public void onQuit(Player owner) {
        UUID id = owner.getUniqueId();
        clearTitanEffects(owner);
        BukkitTask grapple = grapples.remove(id);
        if (grapple != null) {
            grapple.cancel();
            MovementExemption.end(owner);
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.TITAN_PROTOCOL) {
            removeTitanBlades(owner);
            clearTitanEffects(owner);
        } else if (power == Power.GRAPPLE_SHOT) {
            BukkitTask task = grapples.remove(owner.getUniqueId());
            if (task != null) {
                task.cancel();
                MovementExemption.end(owner);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                onQuit(player);
                clearTitanEffects(player);
            }
        }
        grapples.values().forEach(BukkitTask::cancel);
        grapples.clear();
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_RESTOCK, "Restock",
                        "Refill your kit. " + (int) (restockCooldown / 3600) + "h cooldown."),
                new Ability(ABILITY_LOADOUT, "Restock Loadout",
                        "Choose what Restock gives you -- " + menu.slots() + " slots, anything you like."),
                new Ability(ABILITY_XP, "XP Bottles",
                        "Fill your inventory with experience bottles. No cooldown."),
                new Ability(ABILITY_GRAPPLE, "Grapple Shot",
                        "Pull toward a visible target up to " + (int) grappleRange
                                + " blocks away. " + (int) grappleCooldown + "s cooldown."),
                new Ability(ABILITY_SKELETAL_LEAP, "Skeletal Leap",
                        "Bone Blade: leap forward with Speed III. "
                                + (int) skeletalLeapCooldown + "s cooldown."),
                new Ability(ABILITY_BONE_CAGE, "Bone Cage",
                        "Bone Blade: fire a projectile that cages and stuns a player for 5s."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_RESTOCK;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_RESTOCK -> restock(owner);
            case ABILITY_LOADOUT -> openLoadout(owner);
            case ABILITY_XP -> xpBottles(owner);
            case ABILITY_GRAPPLE -> grappleShot(owner);
            case ABILITY_SKELETAL_LEAP -> skeletalLeap(owner);
            case ABILITY_BONE_CAGE -> boneCage(owner);
            default -> false;
        };
    }

    private boolean holdingBoneBlade(Player owner) {
        return owner.getUniqueId().equals(
                TitanBladeItem.ownerOf(owner.getInventory().getItemInMainHand()));
    }

    private boolean skeletalLeap(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.TITAN_PROTOCOL)) {
            return plugin.unlocks().denyLocked(owner, Power.TITAN_PROTOCOL);
        }
        if (!holdingBoneBlade(owner)) {
            Text.msg(owner, "<red>Hold your Bone Blade to use Skeletal Leap.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_SKELETAL_LEAP, skeletalLeapCooldown)) {
            return false;
        }
        MovementExemption.begin(owner);
        owner.setVelocity(owner.getLocation().getDirection().multiply(skeletalLeapPower));
        owner.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED, skeletalLeapSpeedTicks, 2, false, true, true));
        owner.getWorld().spawnParticle(
                Particle.CAMPFIRE_COSY_SMOKE, owner.getLocation(), 10, 0.4, 0.2, 0.4, 0.03);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_SKELETON_HURT, 1.0f, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> MovementExemption.end(owner), 20L);
        return true;
    }

    private boolean boneCage(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.TITAN_PROTOCOL)) {
            return plugin.unlocks().denyLocked(owner, Power.TITAN_PROTOCOL);
        }
        if (!holdingBoneBlade(owner)) {
            Text.msg(owner, "<red>Hold your Bone Blade to use Bone Cage.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_BONE_CAGE, boneCageCooldown)) {
            return false;
        }
        Snowball projectile = owner.launchProjectile(Snowball.class);
        projectile.setItem(new ItemStack(Material.BONE_MEAL));
        projectile.setVelocity(owner.getLocation().getDirection().multiply(boneCageProjectileSpeed));
        projectile.getPersistentDataContainer()
                .set(boneCageProjectileKey, PersistentDataType.BYTE, (byte) 1);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1.0f, 0.8f);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneCageHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)
                || !projectile.getPersistentDataContainer()
                        .has(boneCageProjectileKey, PersistentDataType.BYTE)
                || !(projectile.getShooter() instanceof Player owner)
                || !(event.getHitEntity() instanceof Player target)
                || !plugin.kits().isOwner(owner, ID)
                || !TeamRules.canAffect(owner, target)) {
            return;
        }
        plugin.freeze().stunSeconds(target, boneCageStunSeconds);
        Location center = target.getLocation().add(0.0d, 0.1d, 0.0d);
        target.getWorld().playSound(center, Sound.ENTITY_SKELETON_AMBIENT, 1.2f, 0.7f);
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks >= Math.round(boneCageStunSeconds * 20.0d)
                        || !target.isOnline() || target.isDead()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2.0d * i / 24.0d;
                    target.getWorld().spawnParticle(Particle.BLOCK,
                            center.clone().add(Math.cos(angle) * 1.15d,
                                    (ticks % 20) / 10.0d, Math.sin(angle) * 1.15d),
                            1, 0, 0, 0, 0, Material.BONE_BLOCK.createBlockData());
                }
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private boolean openLoadout(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RESTOCK)) {
            return plugin.unlocks().denyLocked(owner, Power.RESTOCK);
        }
        menu.open(owner);
        return true;
    }

    private boolean restock(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RESTOCK)) {
            return plugin.unlocks().denyLocked(owner, Power.RESTOCK);
        }
        // A loadout the player set themselves always wins over the server default.
        List<ItemStack> kit = plugin.data().get(owner.getUniqueId()).restockLoadout();
        if (kit.removeIf(BoundItemListener::isMace)) {
            plugin.data().markDirty();
        }
        if (kit.isEmpty()) {
            kit = restockItems;
        }
        if (kit.isEmpty()) {
            Text.msg(owner, "<red>Your restock kit is empty. Set it with <white>/power loadout</white>.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_RESTOCK, restockCooldown)) {
            return false;
        }
        int delivered = 0;
        int dropped = 0;
        for (ItemStack template : kit) {
            if (BoundItemListener.isMace(template)) {
                continue;
            }
            ItemStack delivery = template.clone();
            BoundItemListener.purgeMaces(delivery);
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(delivery);
            delivered++;
            for (ItemStack overflow : leftover.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), overflow);
                dropped++;
            }
        }
        owner.playSound(owner.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.2f);
        owner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, owner.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.0);
        Text.msg(owner, "<green>Restocked</green> <gray>-- " + delivered + " item type(s)"
                + (dropped > 0 ? ", " + dropped + " dropped at your feet (full inventory)" : "") + ".</gray>");
        return true;
    }

    private boolean xpBottles(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.INFINITE_XP)) {
            return plugin.unlocks().denyLocked(owner, Power.INFINITE_XP);
        }
        int stacks = 0;
        if (xpFillInventory) {
            for (int slot = 0; slot < owner.getInventory().getStorageContents().length; slot++) {
                if (stacks >= xpMaxStacks) {
                    break;
                }
                ItemStack existing = owner.getInventory().getItem(slot);
                if (existing == null || existing.getType().isAir()) {
                    owner.getInventory().setItem(slot, new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
                    stacks++;
                }
            }
        } else {
            owner.getInventory().addItem(new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
            stacks = 1;
        }
        if (stacks == 0) {
            Text.msg(owner, "<red>Your inventory is full.");
            return false;
        }
        owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        owner.getWorld().spawnParticle(Particle.ENCHANT, owner.getLocation().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 1.0);
        Text.msg(owner, "<green>+" + stacks + "</green> <gray>stack(s) of experience bottles.</gray>");
        return true;
    }

}
