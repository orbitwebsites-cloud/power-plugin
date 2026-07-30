package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.TridentItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Crits;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * ItzMeTentx: the water kit.
 *
 * <p>Two passives and one rule change. Breathing and Dolphin's Grace are simply permanent effects.
 * The attack-speed bonus is an attribute modifier applied whenever he is wet, rain included.
 *
 * <p>Trident God is the only part that fights vanilla. Nothing used to actually give him a trident --
 * the dry-riptide rework only ever enhanced whatever one he happened to be holding -- so he now spawns
 * bound to a {@link TridentItem}, Riptide III baked in, the same way every other signature weapon in
 * this plugin is bound.
 *
 * <p>Riptide is hard-gated on the player being in water or rain -- when dry, releasing a Riptide
 * trident does nothing at all, and the trident cannot be thrown either, so there is no vanilla
 * behaviour to intercept or cancel. The launch is therefore re-implemented from scratch on top of
 * {@link PlayerInteractEvent} rather than depending on vanilla's own charge/release lifecycle:
 * right-clicking with the bound trident while dry cancels the interaction outright (vanilla's own
 * attempt to start "using" a dry Riptide trident is inconsistent -- it would only ever engage while
 * a block was in the crosshair, never on a bare right-click into open air) and schedules the launch
 * a fixed, vanilla-matching charge delay later. A separate proximity watch during the flight brings
 * the player to a dead stop the moment they reach a target, instead of sailing straight through --
 * the stun on its own was never the problem, only the fact that momentum kept carrying him past
 * whoever he had just hit.
 */
public class ItzMeTentxKit implements PowerKit, Listener {

    public static final String ID = "itzmetentx";

    /** Vanilla charges a riptide throw for 10 ticks before it will fire. */
    private static final int RIPTIDE_CHARGE_TICKS = 10;

    /** Vanilla's own flight window for a spin-attack riptide; the manual one matches it. */
    private static final long MANUAL_RIPTIDE_MILLIS = 1500L;
    /** Safety cap on the collision watch so a missed target does not poll forever. */
    private static final int COLLISION_WATCH_MAX_TICKS = 30;

    private final PowerSMP plugin;
    /** Players inside a manual (dry) riptide, since {@code isRiptiding()} stays false for those. */
    private final Map<UUID, Long> manualRiptide = new ConcurrentHashMap<>();
    /** Players mid-charge on a dry riptide -- one click, one scheduled launch. */
    private final Map<UUID, BukkitTask> chargingRiptide = new ConcurrentHashMap<>();
    /** The tick-by-tick proximity watch that stops a dry riptide dead on contact. */
    private final Map<UUID, BukkitTask> riptideCollisionWatch = new ConcurrentHashMap<>();
    /** Last attack-speed value written, so the attribute is not rewritten every tick. */
    private final Map<UUID, Double> appliedAttackSpeed = new ConcurrentHashMap<>();
    /** Consecutive crits landed; any non-crit hit resets it back to zero. */
    private final Map<UUID, Integer> critStreak = new ConcurrentHashMap<>();
    /** Tridents pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    private int waterBreathingAmplifier;
    private int dolphinsGraceAmplifier;
    private boolean cancelDrowningDamage = true;
    private double wetAttackSpeedBonus = 2.0d;
    private boolean rainCounts = true;
    private int critStreakHits = 3;
    private int critStreakSlownessAmplifier = 2;
    private double critStreakSeconds = 5.0d;
    private double riptidePowerBase = 3.0d;
    private double riptidePowerPerLevel = 1.5d;
    private double riptideStunSeconds = 3.0d;
    private boolean riptideStunPlayersOnly;
    private double riptideCollisionRange = 0.6d;

    public ItzMeTentxKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tidebound";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection aquatic = section.getConfigurationSection("aquatic-grace");
        if (aquatic != null) {
            waterBreathingAmplifier = aquatic.getInt("water-breathing-amplifier", 0);
            dolphinsGraceAmplifier = aquatic.getInt("dolphins-grace-amplifier", 0);
            cancelDrowningDamage = aquatic.getBoolean("cancel-drowning-damage", true);
        }
        ConfigurationSection tidal = section.getConfigurationSection("tidal-speed");
        if (tidal != null) {
            wetAttackSpeedBonus = tidal.getDouble("attack-speed-bonus", wetAttackSpeedBonus);
            rainCounts = tidal.getBoolean("rain-counts", true);
            ConfigurationSection critStreakSection = tidal.getConfigurationSection("crit-streak-slowness");
            if (critStreakSection != null) {
                critStreakHits = Math.max(1, critStreakSection.getInt("hits", critStreakHits));
                critStreakSlownessAmplifier =
                        critStreakSection.getInt("slowness-amplifier", critStreakSlownessAmplifier);
                critStreakSeconds = critStreakSection.getDouble("duration-seconds", critStreakSeconds);
            }
        }
        ConfigurationSection trident = section.getConfigurationSection("trident-god");
        if (trident != null) {
            riptidePowerBase = trident.getDouble("dry-riptide-power-base", riptidePowerBase);
            riptidePowerPerLevel =
                    trident.getDouble("dry-riptide-power-per-level", riptidePowerPerLevel);
            riptideStunSeconds = trident.getDouble("hit-stun-seconds", riptideStunSeconds);
            riptideStunPlayersOnly = trident.getBoolean("stun-players-only", false);
            riptideCollisionRange = trident.getDouble("collision-range", riptideCollisionRange);
        }
    }

    // ---- passives -------------------------------------------------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.TRIDENT_GOD)) {
            ensureTrident(owner);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.AQUATIC_GRACE)) {
            // Water Breathing stops the air bar draining outright, so this alone means never
            // drowning; the damage cancel below is belt and braces.
            Effects.applyInfinite(owner, PotionEffectType.WATER_BREATHING, waterBreathingAmplifier);
            // Dolphin's Grace only does anything while swimming, so leaving it on permanently is
            // harmless and avoids flickering it on and off at the waterline.
            Effects.applyInfinite(owner, PotionEffectType.DOLPHINS_GRACE, dolphinsGraceAmplifier);
        }

        if (plugin.unlocks().isUnlocked(owner, Power.TIDAL_SPEED)) {
            setAttackSpeed(owner, isWet(owner) ? wetAttackSpeedBonus : 0.0d);
        }
    }

    /** In water, or being rained on if rain counts. */
    private boolean isWet(Player player) {
        if (player.isInWater()) {
            return true;
        }
        if (!rainCounts) {
            return false;
        }
        try {
            return player.isInRain();
        } catch (Throwable ignored) {
            // Older API: approximate it. Misses the biome check isInRain() does for free.
            World world = player.getWorld();
            if (!world.hasStorm()) {
                return false;
            }
            Location at = player.getLocation();
            return world.getHighestBlockYAt(at) <= at.getBlockY();
        }
    }

    private void setAttackSpeed(Player player, double bonus) {
        Double previous = appliedAttackSpeed.get(player.getUniqueId());
        if (previous != null && previous == bonus) {
            return;
        }
        Attributes.set(player, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED, bonus);
        appliedAttackSpeed.put(player.getUniqueId(), bonus);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrowning(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.DROWNING) {
            return;
        }
        if (event.getEntity() instanceof Player player
                && cancelDrowningDamage
                && plugin.kits().isOwner(player, ID)
                && plugin.unlocks().isUnlocked(player, Power.AQUATIC_GRACE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Tier 2 add-on alongside the wet attack-speed bonus: three critical hits in a row slow the
     * target. Any non-crit hit in between resets the streak back to zero -- it has to be three
     * straight, not three out of some larger window.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCritStreak(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.TIDAL_SPEED)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!Crits.isCriticalMelee(player)) {
            critStreak.remove(id);
            return;
        }
        int streak = critStreak.merge(id, 1, Integer::sum);
        if (streak < critStreakHits) {
            return;
        }
        critStreak.remove(id);

        int ticks = (int) (critStreakSeconds * 20.0d);
        Effects.apply(target, PotionEffectType.SLOWNESS, ticks, critStreakSlownessAmplifier);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1.0f, 0.8f);
        target.getWorld().spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.1);
        Text.actionBar(player, "<aqua><bold>CRITICAL STREAK</bold></aqua>");
        if (target instanceof Player targetPlayer) {
            Text.actionBar(targetPlayer, "<aqua>Slowed by three straight crits.</aqua>");
        }
    }

    // ---- Trident God ----------------------------------------------------

    /**
     * Dry riptide, take two. Vanilla will not fire one out of water or rain, and its own attempt to
     * even start "using" a dry Riptide trident turned out to depend on whatever the crosshair
     * happened to be resting on -- reliable with a block in view, silently dead in open air. Rather
     * than chase that quirk, the whole interaction is driven by hand: a right-click with the bound
     * trident while dry is cancelled outright (so vanilla never gets a say either way) and the
     * launch is scheduled a fixed, vanilla-matching charge delay later.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.TRIDENT_GOD)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.TRIDENT
                || !player.getUniqueId().equals(TridentItem.ownerOf(item))) {
            return;
        }
        if (Enchants.RIPTIDE == null) {
            return;
        }
        int level = item.getEnchantmentLevel(Enchants.RIPTIDE);
        if (level <= 0 || isWet(player)) {
            return; // Wet: vanilla's own hold-and-release handles this one.
        }
        UUID id = player.getUniqueId();
        if (chargingRiptide.containsKey(id)) {
            return; // Already mid-charge -- one click, one launch.
        }

        event.setCancelled(true);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 0.8f);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                (Plugin) plugin, () -> launchDryRiptide(player, level), RIPTIDE_CHARGE_TICKS);
        chargingRiptide.put(id, task);
    }

    private void launchDryRiptide(Player player, int level) {
        UUID id = player.getUniqueId();
        chargingRiptide.remove(id);
        if (!player.isOnline() || isWet(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.TRIDENT || !id.equals(TridentItem.ownerOf(held))) {
            return; // Swapped items mid-charge.
        }

        double power = riptidePowerBase + riptidePowerPerLevel * level;
        player.setVelocity(player.getLocation().getDirection().multiply(power / 3.0d));
        player.setFallDistance(0.0f);
        // Mark the window by hand: isRiptiding() only reports vanilla's own spin attack.
        manualRiptide.put(id, System.currentTimeMillis() + MANUAL_RIPTIDE_MILLIS);
        player.getWorld().playSound(player.getLocation(), soundFor(level), 1.0f, 1.0f);
        player.getWorld().spawnParticle(Particle.SPLASH, player.getLocation(), 60, 0.4, 0.3, 0.4, 0.2);
        player.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, player.getLocation(), 20, 0.3, 0.2, 0.3, 0.05);
        startCollisionWatch(player);
    }

    private Sound soundFor(int level) {
        return switch (level) {
            case 1 -> Sound.ITEM_TRIDENT_RIPTIDE_1;
            case 2 -> Sound.ITEM_TRIDENT_RIPTIDE_2;
            default -> Sound.ITEM_TRIDENT_RIPTIDE_3;
        };
    }

    /**
     * Polls once a tick for the flight's duration. A plain "who is nearby right now" check missed
     * fast riptides -- at typical launch speeds the player covers more than a collision-range's worth
     * of ground in a single tick, so a target could sit squarely on the path between last tick's
     * position and this tick's without ever being "nearby" at either sampled instant. A ray trace
     * along that whole travelled segment (widened by the collision range) catches it instead.
     */
    private void startCollisionWatch(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask previous = riptideCollisionWatch.remove(id);
        if (previous != null) {
            previous.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer((Plugin) plugin, new Runnable() {
            private int ticks = 0;
            private Location lastLocation = player.getLocation();

            @Override
            public void run() {
                ticks++;
                if (!player.isOnline() || !isRiptiding(player) || ticks > COLLISION_WATCH_MAX_TICKS) {
                    stop();
                    return;
                }
                Location from = lastLocation;
                Location now = player.getLocation();
                lastLocation = now;
                Vector traveled = now.toVector().subtract(from.toVector());
                double distance = traveled.length();
                if (distance < 1.0e-4) {
                    return;
                }
                RayTraceResult hit = player.getWorld().rayTraceEntities(
                        from, traveled, distance, riptideCollisionRange,
                        candidate -> !candidate.equals(player) && candidate instanceof LivingEntity target
                                && (!riptideStunPlayersOnly || target instanceof Player));
                if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) {
                    return;
                }
                onRiptideCollision(player, target);
                stop();
            }

            private void stop() {
                BukkitTask self = riptideCollisionWatch.remove(id);
                if (self != null) {
                    self.cancel();
                }
            }
        }, 1L, 1L);
        riptideCollisionWatch.put(id, task);
    }

    /** Stops the player dead and stuns whoever they just collided with. */
    private void onRiptideCollision(Player player, LivingEntity target) {
        manualRiptide.remove(player.getUniqueId());
        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(0.0d, Math.min(0.0d, velocity.getY()), 0.0d));
        plugin.freeze().stunSeconds(target, riptideStunSeconds);
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 0.8f);
        target.getWorld().spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.25);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.0f);
    }

    /** Anything caught by vanilla's own spin attack is stunned -- but stays hittable, an opening. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptideHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.TRIDENT_GOD)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (riptideStunPlayersOnly && !(target instanceof Player)) {
            return;
        }
        if (!isRiptiding(player)) {
            return;
        }
        plugin.freeze().stunSeconds(target, riptideStunSeconds);
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 0.8f);
        target.getWorld().spawnParticle(Particle.SPLASH, target.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.25);
    }

    /** True during vanilla's spin attack, or inside a manually launched dry riptide. */
    private boolean isRiptiding(Player player) {
        if (player.isRiptiding()) {
            return true;
        }
        Long until = manualRiptide.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            manualRiptide.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    // ---- the trident: bound, always present ------------------------------
    // "On spawn he should have a trident with Riptide III already applied" -- Trident God's dry
    // riptide only ever worked on whatever trident he happened to be holding; nothing actually gave
    // him one. Bound the same way every other signature weapon in this plugin is now: unbreakable,
    // Curse of Vanishing, drop-cancelled, stashed through death and handed back on respawn.

    private void ensureTrident(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.TRIDENT_GOD)) {
            return;
        }
        for (ItemStack item : owner.getInventory().getContents()) {
            if (owner.getUniqueId().equals(TridentItem.ownerOf(item))) {
                return;
            }
        }
        ItemStack trident = TridentItem.create(owner.getUniqueId());
        // Never drop a soulbound replacement: the next shared tick retries once room exists.
        owner.getInventory().addItem(trident);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (TridentItem.isBoundTrident(drop)) {
                deathStash.put(player.getUniqueId(), drop.clone());
                it.remove();
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ItemStack stashed = deathStash.remove(player.getUniqueId());
        // Curse of Vanishing means there is usually nothing to restore -- ensureTrident() is the
        // fallback that actually hands it back in that case.
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                ensureTrident(player);
                return;
            }
            HashMap<Integer, ItemStack> leftover = new HashMap<>(player.getInventory().addItem(stashed));
            if (!leftover.isEmpty()) {
                Text.msg(player, "<yellow>Your trident is waiting -- free an inventory slot.");
            }
            Text.msg(player, "<gray>Your trident came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (TridentItem.isBoundTrident(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your trident will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (TridentItem.isBoundTrident(event.getCurrentItem()) || TridentItem.isBoundTrident(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING
                && TridentItem.isBoundTrident(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- cleanup --------------------------------------------------------

    @Override
    public void onJoin(Player owner) {
        // Attribute modifiers survive in player NBT; clear ours before re-deriving.
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
        Effects.remove(owner, PotionEffectType.WATER_BREATHING);
        Effects.remove(owner, PotionEffectType.DOLPHINS_GRACE);
        appliedAttackSpeed.remove(owner.getUniqueId());
        ensureTrident(owner);
    }

    @Override
    public void onQuit(Player owner) {
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
        Effects.remove(owner, PotionEffectType.WATER_BREATHING);
        Effects.remove(owner, PotionEffectType.DOLPHINS_GRACE);
        appliedAttackSpeed.remove(owner.getUniqueId());
        manualRiptide.remove(owner.getUniqueId());
        critStreak.remove(owner.getUniqueId());
        cancelTask(chargingRiptide, owner.getUniqueId());
        cancelTask(riptideCollisionWatch, owner.getUniqueId());
    }

    private void cancelTask(Map<UUID, BukkitTask> tasks, UUID id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.AQUATIC_GRACE) {
            Effects.remove(owner, PotionEffectType.WATER_BREATHING);
            Effects.remove(owner, PotionEffectType.DOLPHINS_GRACE);
        } else if (power == Power.TIDAL_SPEED) {
            Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
            appliedAttackSpeed.remove(owner.getUniqueId());
            critStreak.remove(owner.getUniqueId());
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
                Effects.remove(player, PotionEffectType.WATER_BREATHING);
                Effects.remove(player, PotionEffectType.DOLPHINS_GRACE);
            }
        }
        appliedAttackSpeed.clear();
        manualRiptide.clear();
        critStreak.clear();
        chargingRiptide.values().forEach(BukkitTask::cancel);
        chargingRiptide.clear();
        riptideCollisionWatch.values().forEach(BukkitTask::cancel);
        riptideCollisionWatch.clear();
    }
}
