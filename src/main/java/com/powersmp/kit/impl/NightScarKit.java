package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
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
import org.bukkit.util.Vector;

/**
 * Night_Scar3: Mace Master.
 *
 * <p>Permanent Strength, three rechargeable dashes, and a mace that gains a level of Density per
 * kill up to VIII -- after which each further kill is worth a permanent extra heart instead.
 *
 * <p>Note this is a <em>different</em> kit to TechKnightGaming's Mace Massacre, which caps far
 * higher and has no health overflow. Two players with mace kits is unusual but they were specified
 * separately, so they are built separately and share no state.
 */
public class NightScarKit implements PowerKit, Listener {

    public static final String ID = "night_scar3";

    private static final String ABILITY_DASH = "dash";

    private final PowerSMP plugin;
    /** Dash charges currently spent, and when each will come back. */
    private final Map<UUID, Integer> dashesUsed = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDashRecharge = new ConcurrentHashMap<>();

    private int strengthAmplifier;
    private int dashCharges = 3;
    private double dashPower = 1.5d;
    private double dashRechargeSeconds = 10.0d;
    private int windBurstLevel = 1;
    private int maxDensity = 8;
    private double heartsPerKillAfterMax = 2.0d;
    private double maxBonusHealth = 40.0d;
    private boolean maceUnbreakable = true;
    private boolean countPlayerKills = true;
    private boolean countMobKills = true;
    /** Maces pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

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
        if (section != null) {
            strengthAmplifier = section.getInt("strength.amplifier", 0);
            ConfigurationSection dash = section.getConfigurationSection("dash");
            if (dash != null) {
                dashCharges = dash.getInt("charges", dashCharges);
                dashPower = dash.getDouble("power", dashPower);
                dashRechargeSeconds = dash.getDouble("recharge-seconds", dashRechargeSeconds);
                windBurstLevel = dash.getInt("wind-burst-level", windBurstLevel);
            }
            ConfigurationSection mace = section.getConfigurationSection("mace");
            if (mace != null) {
                maxDensity = mace.getInt("max-density", maxDensity);
                heartsPerKillAfterMax = mace.getDouble("health-per-kill-after-max", heartsPerKillAfterMax);
                maxBonusHealth = mace.getDouble("max-bonus-health", maxBonusHealth);
                maceUnbreakable = mace.getBoolean("unbreakable", true);
                countPlayerKills = mace.getBoolean("count-player-kills", true);
                // Was always true with no way to turn it off -- mob kills were silently raising
                // density. Defaults to false now; techknight's mace makes the same choice.
                countMobKills = mace.getBoolean("count-mob-kills", false);
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_DASH, "Dash");
    }

    // ---- low tier: permanent Strength -----------------------------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.PERMANENT_STRENGTH)) {
            Effects.applyInfinite(owner, PotionEffectType.STRENGTH, strengthAmplifier);
        }
        rechargeDashes(owner);
        if (plugin.unlocks().isUnlocked(owner, Power.DENSITY_MACE)) {
            applyBonusHealth(owner);
            ensureMace(owner);
        }
    }

    // ---- mid tier: dashes -----------------------------------------------

    /** Charges come back one at a time rather than all at once, so spending all three still stings. */
    private void rechargeDashes(Player owner) {
        UUID id = owner.getUniqueId();
        int used = dashesUsed.getOrDefault(id, 0);
        if (used <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastDashRecharge.getOrDefault(id, now);
        if (now - last >= (long) (dashRechargeSeconds * 1000.0d)) {
            dashesUsed.put(id, used - 1);
            lastDashRecharge.put(id, now);
            Text.actionBar(owner, "<gray>Dash recharged (" + (dashCharges - (used - 1)) + "/"
                    + dashCharges + ")</gray>");
        }
    }

    private boolean dash(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.DASH)) {
            return plugin.unlocks().denyLocked(owner, Power.DASH);
        }
        UUID id = owner.getUniqueId();
        int used = dashesUsed.getOrDefault(id, 0);
        if (used >= dashCharges) {
            Text.msg(owner, "<red>No dashes left -- one comes back every "
                    + (int) dashRechargeSeconds + "s.");
            return false;
        }
        if (used == 0) {
            lastDashRecharge.put(id, System.currentTimeMillis());
        }
        dashesUsed.put(id, used + 1);

        // Vanilla's own movement check does not know a dash is deliberate and will snap him back
        // mid-flight without this -- the same fix the web shooter's grapple needed.
        MovementExemption.begin(owner);
        Bukkit.getScheduler().runTaskLater(plugin, () -> MovementExemption.end(owner), 20L);

        // "boost in any direction" -- wherever he is looking, including straight up.
        Vector direction = owner.getLocation().getDirection().normalize().multiply(dashPower);
        owner.setVelocity(direction);
        owner.setFallDistance(0.0f);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.2f);
        owner.getWorld().spawnParticle(Particle.CLOUD, owner.getLocation(), 25, 0.3, 0.3, 0.3, 0.08);
        owner.getWorld().spawnParticle(Particle.GUST, owner.getLocation(), 1);
        Text.actionBar(owner, "<aqua>Dash</aqua> <gray>(" + (dashCharges - used - 1) + "/"
                + dashCharges + " left)</gray>");
        return true;
    }

    // ---- high tier: the mace --------------------------------------------

    private ItemStack buildMace(int density) {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_aqua><bold>Mace Master</bold></dark_aqua>"));
            meta.setUnbreakable(maceUnbreakable);
            if (Enchants.DENSITY != null && density > 0) {
                meta.addEnchant(Enchants.DENSITY, density, true);
            }
            if (Enchants.WIND_BURST != null && windBurstLevel > 0) {
                meta.addEnchant(Enchants.WIND_BURST, windBurstLevel, true);
            }
            meta.lore(List.of(
                    Text.mm("<gray>Density " + density + " / " + maxDensity + "</gray>"),
                    Text.mm("<dark_gray>Kills past the cap become hearts.</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.SCAR_MACE, PersistentDataType.INTEGER, density);
            Enchants.applyVanishing(meta);
            mace.setItemMeta(meta);
        }
        return mace;
    }

    private boolean isScarMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SCAR_MACE, PersistentDataType.INTEGER);
    }

    /** Re-issues the mace, or re-syncs its Density if player data has moved on without it. */
    private void ensureMace(Player owner) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        int density = Math.min(maxDensity, data.maceKills());
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isScarMace(item)) {
                Integer current = item.getItemMeta().getPersistentDataContainer()
                        .get(Keys.SCAR_MACE, PersistentDataType.INTEGER);
                if (current == null || current != density) {
                    ItemMeta meta = buildMace(density).getItemMeta();
                    item.setItemMeta(meta);
                }
                return;
            }
        }
        owner.getInventory().addItem(buildMace(density));
    }

    @Override
    public void onJoin(Player owner) {
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        if (plugin.unlocks().isUnlocked(owner, Power.DENSITY_MACE)) {
            applyBonusHealth(owner);
            ensureMace(owner);
        }
    }

    /** Kills past the Density cap are banked as permanent extra hearts. */
    private void applyBonusHealth(Player owner) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        int overflow = Math.max(0, data.maceKills() - maxDensity);
        double bonus = Math.min(maxBonusHealth, overflow * heartsPerKillAfterMax);
        Attributes.set(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH, bonus);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.DENSITY_MACE)) {
            return;
        }
        if (!isScarMace(killer.getInventory().getItemInMainHand())) {
            return;
        }
        boolean victimIsPlayer = event.getEntity() instanceof Player;
        if (victimIsPlayer ? !countPlayerKills : !countMobKills) {
            return;
        }
        PlayerData data = plugin.data().get(killer.getUniqueId());
        int before = data.maceKills();
        data.maceKills(before + 1);
        plugin.data().markDirty();

        if (before < maxDensity) {
            ensureMace(killer);
            Text.msg(killer, "<dark_aqua>Density " + Math.min(maxDensity, before + 1)
                    + "</dark_aqua><gray>/" + maxDensity + "</gray>");
            killer.playSound(killer.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
            killer.getWorld().spawnParticle(Particle.CRIT, killer.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.2);
        } else {
            applyBonusHealth(killer);
            double max = Attributes.valueOf(killer, Attributes.MAX_HEALTH, 20.0d);
            Text.msg(killer, "<red>+" + (heartsPerKillAfterMax / 2.0d) + " heart</red> <gray>("
                    + (max / 2.0d) + " total)</gray>");
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
            killer.getWorld().spawnParticle(Particle.HEART, killer.getLocation().add(0, 1.6, 0), 8, 0.3, 0.3, 0.3, 0.0);
        }
    }

    // ---- "can't be taken away, even if I die" ---------------------------
    // Mirrors techknight's mace protection: the mace never had its own drop/death/container
    // guards, which is exactly how a duplicate happens -- the original ends up on the ground or
    // in a chest while ensureMace(), seeing no mace in inventory, hands out a second one.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (isScarMace(drop)) {
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
        // notice and rebuild it anyway, but ensureMace() here does it immediately instead of after
        // up to a second's delay.
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                if (plugin.unlocks().isUnlocked(player, Power.DENSITY_MACE)) {
                    ensureMace(player);
                }
                return;
            }
            HashMap<Integer, ItemStack> leftover = new HashMap<>(player.getInventory().addItem(stashed));
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stashed);
            }
            Text.msg(player, "<gray>Your mace came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isScarMace(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your mace will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (isScarMace(event.getCurrentItem()) || isScarMace(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING && isScarMace(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY_DASH, "Dash",
                dashCharges + " charges, one back every " + (int) dashRechargeSeconds + "s."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_DASH;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return ABILITY_DASH.equalsIgnoreCase(abilityId.toLowerCase(Locale.ROOT)) && dash(owner);
    }

    @Override
    public void onQuit(Player owner) {
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        dashesUsed.remove(owner.getUniqueId());
        lastDashRecharge.remove(owner.getUniqueId());
    }

    @Override
    public void onDisable() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
            }
        }
    }
}
