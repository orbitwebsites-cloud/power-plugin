package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.combat.ComboTracker;
import com.powersmp.kit.PowerKit;
import com.powersmp.item.ResourcePackItems;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

/**
 * Night_Scar3: redesigned after the server-wide mace ban.
 *
 * <p>Density Mace no longer made sense once maces were banned outright, so all three tiers were
 * rebuilt from scratch rather than patched: a passive that gives fire immunity and a scaling health
 * boost, a combo-triggered blind, and a bound Cutlass sword replacing the mace. The health boost
 * climbs with the tiers themselves (12 -> 15 -> 20 hearts) rather than with kills -- there being
 * three numbered tiers already does the pacing a kill counter would otherwise do.
 */
public class NightScarKit implements PowerKit, Listener {

    public static final String ID = "night_scar3";

    private final PowerSMP plugin;
    private final ComboTracker combos = new ComboTracker(3.0d);
    /** Cutlasses pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    private int fireVigorBonusHearts = 2;
    private int shadowBombBonusHearts = 5;
    private int cutlassBonusHearts = 10;

    private int shadowBombComboHits = 3;
    private int shadowBombDarknessAmplifier = 0;
    private int shadowBombSlownessAmplifier = 3;
    private double shadowBombSeconds = 5.0d;

    private boolean cutlassUnbreakable = true;

    public NightScarKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mace Master";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection passive = section.getConfigurationSection("passive");
        if (passive != null) {
            fireVigorBonusHearts = passive.getInt("fire-and-vigor-bonus-hearts", fireVigorBonusHearts);
            shadowBombBonusHearts = passive.getInt("shadow-bomb-bonus-hearts", shadowBombBonusHearts);
            cutlassBonusHearts = passive.getInt("cutlass-bonus-hearts", cutlassBonusHearts);
        }
        ConfigurationSection shadowBomb = section.getConfigurationSection("shadow-bomb");
        if (shadowBomb != null) {
            shadowBombComboHits = Math.max(1, shadowBomb.getInt("combo-hits", shadowBombComboHits));
            combos.windowSeconds(shadowBomb.getDouble("combo-window-seconds", 3.0d));
            shadowBombDarknessAmplifier = shadowBomb.getInt("darkness-amplifier", shadowBombDarknessAmplifier);
            shadowBombSlownessAmplifier = shadowBomb.getInt("slowness-amplifier", shadowBombSlownessAmplifier);
            shadowBombSeconds = shadowBomb.getDouble("duration-seconds", shadowBombSeconds);
        }
        ConfigurationSection cutlass = section.getConfigurationSection("cutlass");
        if (cutlass != null) {
            cutlassUnbreakable = cutlass.getBoolean("unbreakable", true);
        }
    }

    // ---- passive: fire resistance + scaling health boost -----------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.FIRE_AND_VIGOR)) {
            Effects.applyInfinite(owner, PotionEffectType.FIRE_RESISTANCE, 0);
            applyBonusHealth(owner);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.CUTLASS_MASTER)) {
            ensureCutlass(owner);
        }
    }

    /** Bonus hearts climb with the highest tier unlocked, not with kills. */
    private void applyBonusHealth(Player owner) {
        int bonusHearts = fireVigorBonusHearts;
        if (plugin.unlocks().isUnlocked(owner, Power.CUTLASS_MASTER)) {
            bonusHearts = cutlassBonusHearts;
        } else if (plugin.unlocks().isUnlocked(owner, Power.SHADOW_BOMB)) {
            bonusHearts = shadowBombBonusHearts;
        }
        Attributes.set(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH, bonusHearts * 2.0d);
    }

    // ---- mid tier: Shadow Bomb --------------------------------------------

    /** Passive, no manual activation: a 3-hit combo blinds and slows whoever is on the other end. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.SHADOW_BOMB)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        int hits = combos.hit(player.getUniqueId(), target.getUniqueId());
        if (hits < shadowBombComboHits) {
            return;
        }
        combos.reset(player.getUniqueId());

        int ticks = (int) (shadowBombSeconds * 20.0d);
        Effects.apply(target, PotionEffectType.DARKNESS, ticks, shadowBombDarknessAmplifier);
        Effects.apply(target, PotionEffectType.SLOWNESS, ticks, shadowBombSlownessAmplifier);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1.6f);
        target.getWorld().spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        target.getWorld().spawnParticle(Particle.SQUID_INK, target.getLocation().add(0, 1, 0), 15, 0.3, 0.4, 0.3, 0.05);
        Text.actionBar(player, "<dark_purple><bold>SHADOW BOMB</bold></dark_purple>");
        if (target instanceof Player targetPlayer) {
            Text.actionBar(targetPlayer, "<dark_purple>Blinded by the shadows...</dark_purple>");
        }
    }

    // ---- high tier: the Cutlass sword --------------------------------------

    private ItemStack buildCutlass() {
        ItemStack cutlass = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = cutlass.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_aqua><bold>Cutlass</bold></dark_aqua>"));
            meta.setUnbreakable(cutlassUnbreakable);
            addEnchant(meta, Enchantment.SHARPNESS, 5);
            addEnchant(meta, Enchantment.FIRE_ASPECT, 2);
            addEnchant(meta, Enchantment.SWEEPING_EDGE, 3);
            meta.lore(List.of(
                    Text.mm("<gray>Forged in the Altar's fire.</gray>"),
                    Text.mm("<dark_gray>Max health: 20 hearts.</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.SCAR_CUTLASS, PersistentDataType.BYTE, (byte) 1);
            Enchants.applyVanishing(meta);
            cutlass.setItemMeta(meta);
        }
        ResourcePackItems.apply(cutlass, ResourcePackItems.CUTLASS_SWORD);
        return cutlass;
    }

    private void addEnchant(ItemMeta meta, Enchantment enchantment, int level) {
        if (enchantment == null) {
            return;
        }
        meta.addEnchant(enchantment, level, true);
    }

    private boolean isCutlass(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SCAR_CUTLASS, PersistentDataType.BYTE);
    }

    /** Hands out the Cutlass if it is missing -- nothing to re-sync, it does not level up. */
    private void ensureCutlass(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isCutlass(item)) {
                return;
            }
        }
        owner.getInventory().addItem(buildCutlass());
    }

    @Override
    public void onJoin(Player owner) {
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
        if (plugin.unlocks().isUnlocked(owner, Power.FIRE_AND_VIGOR)) {
            applyBonusHealth(owner);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.CUTLASS_MASTER)) {
            ensureCutlass(owner);
        }
    }

    // ---- "can't be taken away, even if I die" ---------------------------
    // Mirrors techknight's mace protection: the cutlass never had its own drop/death/container
    // guards, which is exactly how a duplicate happens -- the original ends up on the ground or
    // in a chest while ensureCutlass(), seeing no cutlass in inventory, hands out a second one.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (isCutlass(drop)) {
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
        // Curse of Vanishing means there is usually nothing to restore -- tick() would eventually
        // notice and rebuild it anyway, but ensureCutlass() here does it immediately instead of after
        // up to a second's delay.
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                if (plugin.unlocks().isUnlocked(player, Power.CUTLASS_MASTER)) {
                    ensureCutlass(player);
                }
                return;
            }
            HashMap<Integer, ItemStack> leftover = new HashMap<>(player.getInventory().addItem(stashed));
            if (!leftover.isEmpty()) {
                Text.msg(player, "<yellow>Your cutlass is waiting -- free an inventory slot.");
            }
            Text.msg(player, "<gray>Your cutlass came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isCutlass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your cutlass will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (isCutlass(event.getCurrentItem()) || isCutlass(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING && isCutlass(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- cleanup --------------------------------------------------------

    @Override
    public void onQuit(Player owner) {
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
        combos.reset(owner.getUniqueId());
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.FIRE_AND_VIGOR) {
            Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
            Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
        } else if (power == Power.SHADOW_BOMB || power == Power.CUTLASS_MASTER) {
            // Bonus health may need to drop back a tier; tick() re-derives it on the next pass, but
            // re-apply immediately so it does not read as stale for up to a second.
            if (plugin.unlocks().isUnlocked(owner, Power.FIRE_AND_VIGOR)) {
                applyBonusHealth(owner);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
                Effects.remove(player, PotionEffectType.FIRE_RESISTANCE);
            }
        }
        combos.clear();
    }
}
