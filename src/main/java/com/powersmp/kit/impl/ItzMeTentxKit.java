package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.TridentItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

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
 * behaviour to intercept or cancel. The launch is therefore re-implemented: Paper's
 * {@link PlayerStopUsingItemEvent} reports how long the trident was charged,
 * and if it was held past vanilla's charge time while dry, the player is thrown along their look
 * vector using vanilla's own power curve.
 */
public class ItzMeTentxKit implements PowerKit, Listener {

    public static final String ID = "itzmetentx";

    /** Vanilla charges a riptide throw for 10 ticks before it will fire. */
    private static final int RIPTIDE_CHARGE_TICKS = 10;

    private final PowerSMP plugin;
    /** Players inside a manual (dry) riptide, since {@code isRiptiding()} stays false for those. */
    private final Map<UUID, Long> manualRiptide = new ConcurrentHashMap<>();
    /** Last attack-speed value written, so the attribute is not rewritten every tick. */
    private final Map<UUID, Double> appliedAttackSpeed = new ConcurrentHashMap<>();
    /** Tridents pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    private int waterBreathingAmplifier;
    private int dolphinsGraceAmplifier;
    private boolean cancelDrowningDamage = true;
    private double wetAttackSpeedBonus = 2.0d;
    private boolean rainCounts = true;
    private double riptidePowerBase = 3.0d;
    private double riptidePowerPerLevel = 1.5d;
    private double riptideStunSeconds = 3.0d;
    private boolean riptideStunPlayersOnly;

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
        }
    }

    // ---- passives -------------------------------------------------------

    @Override
    public void tick(Player owner) {
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
     * Dry riptide. Vanilla will not fire one out of water or rain and will not let a Riptide trident
     * be thrown either, so nothing happens on release and there is no event to cancel -- the launch
     * has to be performed here from scratch.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onReleaseTrident(PlayerStopUsingItemEvent event) {
        Player player = event.getPlayer();
        if (!plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.TRIDENT_GOD)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.TRIDENT) {
            return;
        }
        if (Enchants.RIPTIDE == null) {
            return;
        }
        int level = item.getEnchantmentLevel(Enchants.RIPTIDE);
        if (level <= 0 || event.getTicksHeldFor() < RIPTIDE_CHARGE_TICKS) {
            return;
        }
        if (isWet(player)) {
            return; // Vanilla handles this one.
        }

        double power = riptidePowerBase + riptidePowerPerLevel * level;
        player.setVelocity(player.getLocation().getDirection().multiply(power / 3.0d));
        player.setFallDistance(0.0f);
        // Mark the window by hand: isRiptiding() only reports vanilla's own spin attack.
        manualRiptide.put(player.getUniqueId(), System.currentTimeMillis() + 1500L);
        player.getWorld().playSound(player.getLocation(), soundFor(level), 1.0f, 1.0f);
        player.getWorld().spawnParticle(Particle.SPLASH, player.getLocation(), 60, 0.4, 0.3, 0.4, 0.2);
        player.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, player.getLocation(), 20, 0.3, 0.2, 0.3, 0.05);
    }

    private Sound soundFor(int level) {
        return switch (level) {
            case 1 -> Sound.ITEM_TRIDENT_RIPTIDE_1;
            case 2 -> Sound.ITEM_TRIDENT_RIPTIDE_2;
            default -> Sound.ITEM_TRIDENT_RIPTIDE_3;
        };
    }

    /** Anything caught by the spin attack is stunned -- but stays hittable, so it is an opening. */
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
            if (TridentItem.isBoundTrident(item)) {
                return;
            }
        }
        ItemStack trident = TridentItem.create(owner.getUniqueId());
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(trident);
        if (!leftover.isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), trident);
            Text.msg(owner, "<yellow>Your trident was dropped at your feet -- your inventory is full.");
        }
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
                player.getWorld().dropItemNaturally(player.getLocation(), stashed);
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
        appliedAttackSpeed.remove(owner.getUniqueId());
        ensureTrident(owner);
    }

    @Override
    public void onQuit(Player owner) {
        Attributes.clear(owner, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
        appliedAttackSpeed.remove(owner.getUniqueId());
        manualRiptide.remove(owner.getUniqueId());
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.TIDAL_ATTACK_SPEED);
            }
        }
        appliedAttackSpeed.clear();
        manualRiptide.clear();
    }
}
