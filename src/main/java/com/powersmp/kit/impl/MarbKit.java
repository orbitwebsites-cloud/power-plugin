package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/**
 * Marb13_: Miner's Haven, Ender Magic, and the Portal and Shadow Master.
 *
 * <p>The deepslate bonus is driven off what he is currently looking at rather than off a break
 * event, so the higher Haste is already applied while the block is being mined rather than arriving
 * after it breaks -- a break-triggered version would always be one block late and do nothing.
 */
public class MarbKit implements PowerKit, Listener {

    public static final String ID = "marb13";

    private static final String ABILITY_ENDERCHEST = "ec";
    private static final String ABILITY_PORTAL = "portal";
    private static final String ABILITY_SHADOW = "shadow";

    private final PowerSMP plugin;

    // Miner's Haven
    private int baseHaste = 1;          // Haste II
    private int deepslateHaste = 4;     // Haste V
    private String deepslateMatch = "DEEPSLATE";
    private int lookRange = 6;
    // Ender Magic
    private int pearlStack = 16;
    private double enderChestCooldown;
    // Portal and Shadow Master
    private double portalCooldown = 120.0d;
    private boolean portalReplaceSolid;
    private int shadowSeconds = 120;
    private double shadowCooldown = 60.0d;

    public MarbKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Portal and Shadow Master";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection miner = section.getConfigurationSection("miners-haven");
            if (miner != null) {
                baseHaste = miner.getInt("haste-amplifier", baseHaste);
                deepslateHaste = miner.getInt("deepslate-haste-amplifier", deepslateHaste);
                deepslateMatch = miner.getString("deepslate-name-contains", "DEEPSLATE")
                        .toUpperCase(Locale.ROOT);
                lookRange = miner.getInt("look-range", lookRange);
            }
            ConfigurationSection ender = section.getConfigurationSection("ender-magic");
            if (ender != null) {
                pearlStack = Math.max(1, ender.getInt("pearl-stack-size", pearlStack));
                enderChestCooldown = ender.getDouble("enderchest-cooldown-seconds", 0.0d);
            }
            ConfigurationSection shadow = section.getConfigurationSection("portal-and-shadow");
            if (shadow != null) {
                portalCooldown = shadow.getDouble("portal-cooldown-seconds", portalCooldown);
                portalReplaceSolid = shadow.getBoolean("portal-replaces-solid-blocks", false);
                shadowSeconds = shadow.getInt("shadow-item-seconds", shadowSeconds);
                shadowCooldown = shadow.getDouble("shadow-cooldown-seconds", shadowCooldown);
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_PORTAL, "Portal");
        plugin.cooldowns().registerLabel(ABILITY_SHADOW, "Shadow Item");
        plugin.cooldowns().registerLabel(ABILITY_ENDERCHEST, "Ender Chest");
    }

    // ---- low tier: Miner's Haven ----------------------------------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.MINERS_HAVEN)) {
            Effects.refresh(owner, PotionEffectType.HASTE,
                    lookingAtDeepslate(owner) ? deepslateHaste : baseHaste);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.ENDER_MAGIC)) {
            ensurePearls(owner);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.SHADOW_MASTER)) {
            expireShadowItems(owner);
        }
    }

    private boolean lookingAtDeepslate(Player owner) {
        Block target = owner.getTargetBlockExact(lookRange);
        return target != null && target.getType().name().contains(deepslateMatch);
    }

    // ---- mid tier: Ender Magic ------------------------------------------

    /** Same trick as the wind charges: the stack is topped back up rather than made unconsumable. */
    private void ensurePearls(Player owner) {
        int held = 0;
        for (ItemStack item : owner.getInventory().getContents()) {
            if (item != null && item.getType() == Material.ENDER_PEARL) {
                held += item.getAmount();
            }
        }
        if (held < pearlStack) {
            owner.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, pearlStack - held));
        }
    }

    private boolean openEnderChest(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.ENDER_MAGIC)) {
            return plugin.unlocks().denyLocked(owner, Power.ENDER_MAGIC);
        }
        if (enderChestCooldown > 0.0d
                && !plugin.cooldowns().tryUse(owner, ABILITY_ENDERCHEST, enderChestCooldown)) {
            return false;
        }
        owner.openInventory(owner.getEnderChest());
        owner.playSound(owner.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.2f);
        return true;
    }

    // ---- high tier: portals ---------------------------------------------

    /**
     * Builds a lit nether portal around the caster: a 4x5 obsidian frame with a 2x3 portal interior,
     * aligned to whichever axis he is facing.
     *
     * <p>Air and replaceable blocks only, by default. Letting this overwrite solid blocks would turn
     * a movement power into a building-demolition tool, so that is opt-in.
     */
    private boolean spawnPortal(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SHADOW_MASTER)) {
            return plugin.unlocks().denyLocked(owner, Power.SHADOW_MASTER);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_PORTAL, portalCooldown)) {
            return false;
        }

        Block base = owner.getLocation().getBlock();
        float yaw = owner.getLocation().getYaw();
        // Face the portal across his line of sight so he can walk straight into it.
        boolean alongX = Math.abs(Math.cos(Math.toRadians(yaw))) < 0.5d;

        Orientable portalData = (Orientable) Bukkit.createBlockData(Material.NETHER_PORTAL);
        portalData.setAxis(alongX ? org.bukkit.Axis.X : org.bukkit.Axis.Z);

        int placed = 0;
        for (int across = 0; across < 4; across++) {
            for (int up = 0; up < 5; up++) {
                int dx = alongX ? across - 1 : 0;
                int dz = alongX ? 0 : across - 1;
                Block block = base.getRelative(dx, up, dz);
                boolean edge = across == 0 || across == 3 || up == 0 || up == 4;
                if (!edge) {
                    block.setType(Material.NETHER_PORTAL, false);
                    block.setBlockData(portalData, false);
                    placed++;
                } else if (portalReplaceSolid || block.getType().isAir()) {
                    block.setType(Material.OBSIDIAN, false);
                    placed++;
                }
            }
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.4f);
        Text.msg(owner, "<dark_purple>Portal opened</dark_purple> <gray>(" + placed
                + " blocks placed).</gray>");
        return true;
    }

    // ---- high tier: shadow items ----------------------------------------

    /**
     * A shadow item is a temporary copy of whatever he is holding: fully usable, and gone when its
     * timer runs out. Copying rather than conjuring means the power scales with what he has earned,
     * and stamping the expiry on the item itself means a shadow cannot be laundered into a
     * permanent duplicate by stashing it -- wherever it ends up, it still dissolves.
     */
    private boolean makeShadowItem(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SHADOW_MASTER)) {
            return plugin.unlocks().denyLocked(owner, Power.SHADOW_MASTER);
        }
        ItemStack held = owner.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            Text.msg(owner, "<red>Hold the item you want a shadow of.");
            return false;
        }
        if (isShadow(held)) {
            Text.msg(owner, "<red>You cannot cast a shadow of a shadow.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_SHADOW, shadowCooldown)) {
            return false;
        }

        ItemStack shadow = held.clone();
        ItemMeta meta = shadow.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_gray><italic>Shadow "
                    + Text.plain(Text.prettify(held.getType().name().toLowerCase(Locale.ROOT)))
                    + "</italic></dark_gray>"));
            meta.lore(List.of(
                    Text.mm("<dark_gray>Fades in " + shadowSeconds + "s.</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.SHADOW_EXPIRY, PersistentDataType.LONG,
                    System.currentTimeMillis() + shadowSeconds * 1000L);
            shadow.setItemMeta(meta);
        }
        if (!owner.getInventory().addItem(shadow).isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), shadow);
        }
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.6f);
        Text.msg(owner, "<dark_gray>A shadow forms.</dark_gray> <gray>It fades in "
                + shadowSeconds + "s.</gray>");
        return true;
    }

    public static boolean isShadow(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SHADOW_EXPIRY, PersistentDataType.LONG);
    }

    /** Sweeps expired shadows out of the inventory. Checked on the shared tick and on join. */
    private void expireShadowItems(Player owner) {
        ItemStack[] contents = owner.getInventory().getContents();
        long now = System.currentTimeMillis();
        int faded = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isShadow(item)) {
                continue;
            }
            Long expiry = item.getItemMeta().getPersistentDataContainer()
                    .get(Keys.SHADOW_EXPIRY, PersistentDataType.LONG);
            if (expiry != null && expiry <= now) {
                owner.getInventory().setItem(slot, null);
                faded++;
            }
        }
        if (faded > 0) {
            owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.6f, 0.5f);
            Text.actionBar(owner, "<dark_gray>" + faded + " shadow(s) faded</dark_gray>");
        }
    }

    /** A shadow that outlived the server's uptime should not survive the restart either. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.kits().isOwner(player, ID)) {
            expireShadowItems(player);
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_ENDERCHEST, "Ender Chest", "Open your ender chest anywhere."),
                new Ability(ABILITY_PORTAL, "Portal", "Open a nether portal where you stand."),
                new Ability(ABILITY_SHADOW, "Shadow Item",
                        "Copy the held item as a shadow that fades after " + shadowSeconds + "s."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_ENDERCHEST;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_ENDERCHEST -> openEnderChest(owner);
            case ABILITY_PORTAL -> spawnPortal(owner);
            case ABILITY_SHADOW -> makeShadowItem(owner);
            default -> false;
        };
    }
}
