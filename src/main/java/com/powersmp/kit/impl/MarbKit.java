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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
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

    /**
     * Turns the held item into a permanent shadow of itself, in place -- no new item is created and
     * nothing is added to the inventory, so there is nothing here to duplicate or launder. Earlier
     * this cloned the held item into a second, separate stack that expired after a timer, precisely
     * because a permanent free copy of anything is a duplication exploit; converting the original
     * one-for-one instead of copying it removes that problem at the source, so the timer is gone too.
     */
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
            Text.msg(owner, "<red>That is already a shadow.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_SHADOW, shadowCooldown)) {
            return false;
        }

        ItemMeta meta = held.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_gray><italic>Shadow "
                    + Text.plain(Text.prettify(held.getType().name().toLowerCase(Locale.ROOT)))
                    + "</italic></dark_gray>"));
            meta.lore(List.of(Text.mm("<dark_gray>A permanent shadow.</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.SHADOW_MARK, PersistentDataType.BYTE, (byte) 1);
            held.setItemMeta(meta);
        }
        owner.getInventory().setItemInMainHand(held);
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.6f);
        owner.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.02);
        Text.msg(owner, "<dark_gray>The item takes on a shadowy form, permanently.</dark_gray>");
        return true;
    }

    public static boolean isShadow(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SHADOW_MARK, PersistentDataType.BYTE);
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_ENDERCHEST, "Ender Chest", "Open your ender chest anywhere."),
                new Ability(ABILITY_PORTAL, "Portal", "Open a nether portal where you stand."),
                new Ability(ABILITY_SHADOW, "Shadow Item",
                        "Turn the held item into a permanent shadow of itself."));
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
