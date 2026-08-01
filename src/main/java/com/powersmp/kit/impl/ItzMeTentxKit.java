package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.TridentItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Attributes;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.attribute.AttributeModifier;

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
 * behaviour to intercept or cancel. Dry use is driven from right-click directly, with a fixed
 * vanilla-matching charge delay and a proximity watch that stops the launch on contact.
 */
public class ItzMeTentxKit implements PowerKit, Listener {

    public static final String ID = "itzmetentx";
    private static final String ABILITY_RIPTIDE = "poseidon_riptide";

    /** Vanilla charges a riptide throw for 10 ticks before it will fire. */
    private static final int RIPTIDE_CHARGE_TICKS = 10;
    private static final long MANUAL_RIPTIDE_MILLIS = 1500L;
    private static final int COLLISION_WATCH_MAX_TICKS = 30;

    private final PowerSMP plugin;
    /** Players inside a manual (dry) riptide, since {@code isRiptiding()} stays false for those. */
    private final Map<UUID, Long> manualRiptide = new ConcurrentHashMap<>();
    /** Players mid-charge on a dry riptide -- one click, one scheduled launch. */
    private final Map<UUID, BukkitTask> chargingRiptide = new ConcurrentHashMap<>();
    /** Tick-by-tick proximity checks that stop a dry riptide on contact. */
    private final Map<UUID, BukkitTask> riptideCollisionWatch = new ConcurrentHashMap<>();
    /** Last attack-speed value written, so the attribute is not rewritten every tick. */
    private final Map<UUID, Double> appliedAttackSpeed = new ConcurrentHashMap<>();
    /** Tridents pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();
    /** Opponent -> current consecutive melee-hit chain. */
    private final Map<UUID, HitCombo> hitCombos = new ConcurrentHashMap<>();
    /** Opponent -> time at which Tentx's attack-speed penalty expires. */
    private final Map<UUID, Long> comboSlowedUntil = new ConcurrentHashMap<>();

    private int waterBreathingAmplifier;
    private int dolphinsGraceAmplifier;
    private boolean cancelDrowningDamage = true;
    private double wetAttackSpeedBonus = 2.0d;
    private boolean rainCounts = true;
    private double riptidePowerBase = 3.0d;
    private double riptidePowerPerLevel = 1.5d;
    private double riptideStunSeconds = 3.0d;
    private boolean riptideStunPlayersOnly;
    private double riptideCollisionRange = 0.6d;
    private double spearDamageMultiplier = 5.0d;
    private boolean poseidonLightningEnabled = true;
    private double riptideCooldownSeconds = 30.0d;
    private double comboWindowSeconds = 5.0d;
    private double comboDebuffSeconds = 5.0d;
    private double comboAttackSpeedReduction = 0.15d;

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
        }
        ConfigurationSection trident = section.getConfigurationSection("trident-god");
        if (trident != null) {
            riptidePowerBase = trident.getDouble("dry-riptide-power-base", riptidePowerBase);
            riptidePowerPerLevel =
                    trident.getDouble("dry-riptide-power-per-level", riptidePowerPerLevel);
            riptideStunSeconds = trident.getDouble("hit-stun-seconds", riptideStunSeconds);
            riptideStunPlayersOnly = trident.getBoolean("stun-players-only", false);
            riptideCollisionRange = Math.max(0.1d,
                    trident.getDouble("collision-range", riptideCollisionRange));
            spearDamageMultiplier = Math.max(0.0d,
                    trident.getDouble("spear-speed-damage-multiplier", spearDamageMultiplier));
            poseidonLightningEnabled =
                    trident.getBoolean("lightning-enabled", poseidonLightningEnabled);
            riptideCooldownSeconds = Math.max(0.0d,
                    trident.getDouble("riptide-cooldown-seconds", riptideCooldownSeconds));
            comboWindowSeconds = Math.max(0.1d,
                    trident.getDouble("combo-window-seconds", comboWindowSeconds));
            comboDebuffSeconds = Math.max(0.1d,
                    trident.getDouble("combo-debuff-seconds", comboDebuffSeconds));
            comboAttackSpeedReduction = Math.max(0.0d, Math.min(0.95d,
                    trident.getDouble("combo-attack-speed-reduction", comboAttackSpeedReduction)));
        }
        plugin.cooldowns().registerLabel(ABILITY_RIPTIDE, "Poseidon's Riptide");
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

    // ---- Trident God ----------------------------------------------------

    /**
     * Vanilla only starts Riptide reliably while wet. Dry use is handled from the click itself so
     * open-air clicks and block-targeted clicks behave identically.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
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
                || !player.getUniqueId().equals(TridentItem.ownerOf(item))
                || Enchants.RIPTIDE == null) {
            return;
        }
        int level = item.getEnchantmentLevel(Enchants.RIPTIDE);
        if (level <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        if (chargingRiptide.containsKey(id)) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.cooldowns().tryUse(player, ABILITY_RIPTIDE, riptideCooldownSeconds)) {
            return;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 0.8f);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                (Plugin) plugin, () -> launchDryRiptide(player, level), RIPTIDE_CHARGE_TICKS);
        chargingRiptide.put(id, task);
    }

    private void launchDryRiptide(Player player, int level) {
        UUID id = player.getUniqueId();
        chargingRiptide.remove(id);
        if (!player.isOnline()) {
            plugin.cooldowns().clear(id, ABILITY_RIPTIDE);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.TRIDENT || !id.equals(TridentItem.ownerOf(held))) {
            plugin.cooldowns().clear(id, ABILITY_RIPTIDE);
            return;
        }
        double power = riptidePowerBase + riptidePowerPerLevel * level;
        player.setVelocity(player.getLocation().getDirection().multiply(power / 3.0d));
        player.setFallDistance(0.0f);
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

    /** Stops a dry Riptide on contact instead of carrying the player through the target. */
    private void startCollisionWatch(Player player) {
        UUID id = player.getUniqueId();
        cancelTask(riptideCollisionWatch, id);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer((Plugin) plugin, new Runnable() {
            private int ticks;

            @Override
            public void run() {
                if (++ticks > COLLISION_WATCH_MAX_TICKS
                        || !player.isOnline() || !isRiptiding(player)) {
                    cancelTask(riptideCollisionWatch, id);
                    return;
                }
                for (Entity nearby : player.getNearbyEntities(
                        riptideCollisionRange, riptideCollisionRange, riptideCollisionRange)) {
                    if (nearby.equals(player) || !(nearby instanceof LivingEntity target)
                            || (riptideStunPlayersOnly && !(target instanceof Player))
                            || !TeamRules.canAffect(player, target)) {
                        continue;
                    }
                    manualRiptide.remove(id);
                    Vector velocity = player.getVelocity();
                    double spearDamage = velocity.length() * spearDamageMultiplier;
                    if (spearDamage > 0.0d) {
                        target.damage(spearDamage, player);
                    }
                    player.setVelocity(new Vector(0.0d, Math.min(0.0d, velocity.getY()), 0.0d));
                    plugin.freeze().stunSeconds(target, riptideStunSeconds);
                    target.getWorld().playSound(
                            target.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 0.8f);
                    target.getWorld().spawnParticle(Particle.SPLASH,
                            target.getLocation().add(0, 1, 0), 40,
                            0.5, 0.5, 0.5, 0.25);
                    player.getWorld().playSound(
                            player.getLocation(), Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.0f);
                    cancelTask(riptideCollisionWatch, id);
                    return;
                }
            }
        }, 1L, 1L);
        riptideCollisionWatch.put(id, task);
    }

    /** Anything caught by vanilla's spin attack is stunned but stays hittable. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
        if (!TeamRules.canAffect(player, target)) {
            return;
        }
        if (!isRiptiding(player)) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (poseidonLightningEnabled
                    && player.getUniqueId().equals(TridentItem.ownerOf(held))) {
                TeamRules.runProtected(player,
                        () -> target.getWorld().strikeLightning(target.getLocation()));
            }
            return;
        }
        event.setDamage(player.getVelocity().length() * spearDamageMultiplier);
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
                TridentItem.refresh(item);
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
        // Repairs stale combat modifiers if the server stopped while the five-second timer ran.
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED);
        comboSlowedUntil.remove(owner.getUniqueId());
        Effects.remove(owner, PotionEffectType.WATER_BREATHING);
        Effects.remove(owner, PotionEffectType.DOLPHINS_GRACE);
        appliedAttackSpeed.remove(owner.getUniqueId());
        ensureTrident(owner);
    }

    /** Three consecutive melee hits debuff that opponent, independent of Riptide contact. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThreeHitCombo(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player owner)
                || !(event.getEntity() instanceof Player target)
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.TRIDENT_GOD)
                || !TeamRules.canAffect(owner, target)) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMillis = Math.round(comboWindowSeconds * 1000.0d);
        HitCombo previous = hitCombos.get(target.getUniqueId());
        int hits = previous == null || now - previous.lastHitAt > windowMillis
                ? 1 : previous.hits + 1;
        if (hits < 3) {
            hitCombos.put(target.getUniqueId(), new HitCombo(hits, now));
            Text.actionBar(owner, "<aqua>Tidal combo: <white>" + hits + "/3</white></aqua>");
            return;
        }
        hitCombos.remove(target.getUniqueId());
        int durationTicks = Math.max(1, (int) Math.round(comboDebuffSeconds * 20.0d));
        Effects.apply(target, PotionEffectType.MINING_FATIGUE, durationTicks, 0);
        Attributes.set(target, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED,
                -comboAttackSpeedReduction, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        long expires = now + Math.round(comboDebuffSeconds * 1000.0d);
        comboSlowedUntil.put(target.getUniqueId(), expires);
        Bukkit.getScheduler().runTaskLater((Plugin) plugin, () -> {
            Long current = comboSlowedUntil.get(target.getUniqueId());
            if (current != null && current == expires) {
                comboSlowedUntil.remove(target.getUniqueId());
                Attributes.clear(target, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED);
            }
        }, durationTicks);
        Text.actionBar(owner, "<aqua><bold>3-HIT TIDAL COMBO</bold></aqua>");
        Text.actionBar(target, "<blue>Mining Fatigue + 15% slower attacks</blue>");
        target.getWorld().spawnParticle(Particle.SPLASH,
                target.getLocation().add(0, 1, 0), 45, 0.5, 0.7, 0.5, 0.2);
        target.playSound(target.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.4f);
    }

    @Override
    public void onQuit(Player owner) {
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
        for (UUID targetId : comboSlowedUntil.keySet()) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                Attributes.clear(target, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED);
            }
        }
        comboSlowedUntil.clear();
        hitCombos.clear();
        Effects.remove(owner, PotionEffectType.WATER_BREATHING);
        Effects.remove(owner, PotionEffectType.DOLPHINS_GRACE);
        appliedAttackSpeed.remove(owner.getUniqueId());
        manualRiptide.remove(owner.getUniqueId());
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
        chargingRiptide.values().forEach(BukkitTask::cancel);
        chargingRiptide.clear();
        riptideCollisionWatch.values().forEach(BukkitTask::cancel);
        riptideCollisionWatch.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED);
        }
        hitCombos.clear();
        comboSlowedUntil.clear();
    }

    private record HitCombo(int hits, long lastHitAt) { }
}
