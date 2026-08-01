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
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.Listener;
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
                String match = miner.getString("deepslate-name-contains", "DEEPSLATE");
                deepslateMatch = (match == null ? "DEEPSLATE" : match).toUpperCase(Locale.ROOT);
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

        // Put the bottom of the frame in the floor so the 2x3 interior starts at the player's
        // feet. The old y=0 base put the first portal block one block above their feet, producing a
        // floating/incomplete frame on normal ground.
        Block base = owner.getLocation().getBlock().getRelative(0, -1, 0);
        float yaw = owner.getLocation().getYaw();
        // Face the portal across his line of sight so he can walk straight into it.
        boolean alongX = Math.abs(Math.cos(Math.toRadians(yaw))) < 0.5d;

        Orientable portalData = (Orientable) Bukkit.createBlockData(Material.NETHER_PORTAL);
        portalData.setAxis(alongX ? org.bukkit.Axis.X : org.bukkit.Axis.Z);

        // Validate the whole placement before spending the cooldown or changing a single block.
        // The four foundation blocks necessarily replace the ground; every other solid block is
        // protected unless the admin explicitly enables replacement.
        for (int across = 0; across < 4; across++) {
            for (int up = 0; up < 5; up++) {
                int dx = alongX ? across - 1 : 0;
                int dz = alongX ? 0 : across - 1;
                Block block = base.getRelative(dx, up, dz);
                boolean edge = across == 0 || across == 3 || up == 0 || up == 4;
                boolean foundation = up == 0;
                boolean alreadyCompatible = edge
                        ? block.getType() == Material.OBSIDIAN
                        : block.getType() == Material.NETHER_PORTAL;
                if (!alreadyCompatible && block.getState() instanceof TileState && !portalReplaceSolid) {
                    Text.msg(owner, "<red>A protected block is in the portal's path.");
                    return false;
                }
                if (!foundation && !alreadyCompatible && !block.isPassable() && !portalReplaceSolid) {
                    Text.msg(owner, "<red>There is not enough clear space here for a portal.");
                    return false;
                }
            }
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_PORTAL, portalCooldown)) {
            return false;
        }

        int placed = 0;
        for (int across = 0; across < 4; across++) {
            for (int up = 0; up < 5; up++) {
                int dx = alongX ? across - 1 : 0;
                int dz = alongX ? 0 : across - 1;
                Block block = base.getRelative(dx, up, dz);
                boolean edge = across == 0 || across == 3 || up == 0 || up == 4;
                if (edge) {
                    block.setType(Material.OBSIDIAN, false);
                    placed++;
                } else {
                    block.setType(Material.NETHER_PORTAL, false);
                    block.setBlockData(portalData, false);
                    placed++;
                }
            }
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.4f);
        owner.getWorld().spawnParticle(Particle.PORTAL, owner.getLocation().add(0, 1.5, 0), 100, 1.0, 1.5, 1.0, 1.0);
        owner.getWorld().spawnParticle(Particle.REVERSE_PORTAL, owner.getLocation().add(0, 1, 0), 60, 0.8, 1.2, 0.8, 0.5);
        Text.msg(owner, "<dark_purple>Portal opened</dark_purple> <gray>(" + placed
                + " blocks placed).</gray>");
        return true;
    }

    // ---- high tier: shadow items ----------------------------------------

    /** Creates one permanent shadow copy tethered to the original item's lifetime. */
    private boolean makeShadowItem(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SHADOW_MASTER)) {
            return plugin.unlocks().denyLocked(owner, Power.SHADOW_MASTER);
        }
        ItemStack held = owner.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            Text.msg(owner, "<red>Hold the item you want to shadow.");
            return false;
        }
        if (isShadow(held)) {
            Text.msg(owner, "<red>You cannot make a shadow of a shadow.</red>");
            return false;
        }
        if (originalId(held) != null) {
            Text.msg(owner, "<red>That item already has a shadow copy.</red>");
            return false;
        }
        if (owner.getInventory().firstEmpty() < 0) {
            Text.msg(owner, "<red>Free an inventory slot for the shadow copy.</red>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_SHADOW, shadowCooldown)) {
            return false;
        }

        String linkId = UUID.randomUUID().toString();
        ItemMeta originalMeta = held.getItemMeta();
        if (originalMeta == null) {
            return false;
        }
        originalMeta.getPersistentDataContainer()
                .set(Keys.SHADOW_ORIGINAL_ID, PersistentDataType.STRING, linkId);
        held.setItemMeta(originalMeta);

        ItemStack shadow = held.clone();
        ItemMeta meta = shadow.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(Keys.SHADOW_ORIGINAL_ID);
            meta.displayName(Text.mm("<dark_gray><italic>Shadow "
                    + Text.plain(Text.prettify(held.getType().name().toLowerCase(Locale.ROOT)))
                    + "</italic></dark_gray>"));
            meta.lore(List.of(
                    Text.mm("<dark_gray>A permanent copy tethered to its original.</dark_gray>"),
                    Text.mm("<gray>If the original is destroyed, this shadow disappears.</gray>")));
            meta.getPersistentDataContainer()
                    .set(Keys.SHADOW_MARK, PersistentDataType.STRING, linkId);
            meta.getPersistentDataContainer().set(
                    Keys.SHADOW_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
            shadow.setItemMeta(meta);
        }
        owner.getInventory().setItemInMainHand(held);
        owner.getInventory().addItem(shadow);
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.6f);
        owner.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.02);
        Text.msg(owner, "<dark_gray>A permanent shadow copy forms beside the original.</dark_gray>");
        return true;
    }

    public static boolean isShadow(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (meta.getPersistentDataContainer()
                .has(Keys.SHADOW_MARK, PersistentDataType.STRING)
                || meta.getPersistentDataContainer().has(Keys.SHADOW_MARK, PersistentDataType.BYTE));
    }

    private static String originalId(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer()
                .get(Keys.SHADOW_ORIGINAL_ID, PersistentDataType.STRING);
    }

    private static String shadowId(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer()
                .get(Keys.SHADOW_MARK, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOriginalBreak(PlayerItemBreakEvent event) {
        removeLinkedShadow(originalId(event.getBrokenItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOriginalDespawn(ItemDespawnEvent event) {
        removeLinkedShadow(originalId(event.getEntity().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDroppedOriginalDestroyed(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item dropped)) {
            return;
        }
        String id = originalId(dropped.getItemStack());
        if (id == null) {
            return;
        }
        switch (event.getCause()) {
            case LAVA, FIRE, FIRE_TICK, BLOCK_EXPLOSION, ENTITY_EXPLOSION, CONTACT ->
                    removeLinkedShadow(id);
            default -> { }
        }
    }

    private void removeLinkedShadow(String linkId) {
        if (linkId == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeLinkedShadow(player.getInventory().getContents(), player, linkId);
            for (int slot = 0; slot < player.getEnderChest().getSize(); slot++) {
                if (linkId.equals(shadowId(player.getEnderChest().getItem(slot)))) {
                    player.getEnderChest().setItem(slot, null);
                }
            }
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (linkId.equals(shadowId(item.getItemStack()))) {
                    item.remove();
                }
            }
        }
    }

    private void removeLinkedShadow(ItemStack[] contents, Player player, String linkId) {
        for (int slot = 0; slot < contents.length; slot++) {
            if (linkId.equals(shadowId(contents[slot]))) {
                player.getInventory().setItem(slot, null);
                Text.actionBar(player, "<dark_gray>Your shadow vanished with its original.</dark_gray>");
            }
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_ENDERCHEST, "Ender Chest", "Open your ender chest anywhere."),
                new Ability(ABILITY_PORTAL, "Portal", "Open a nether portal where you stand."),
                new Ability(ABILITY_SHADOW, "Shadow Item",
                        "Create a permanent copy that vanishes when its original is destroyed."));
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
