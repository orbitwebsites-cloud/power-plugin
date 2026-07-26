package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.item.MaceItem;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * TechKnightGaming: Mace Massacre.
 *
 * <p>Three powers: a soulbound mace that levels with kills, a long-cooldown utility restock, and
 * on-demand XP bottles.
 *
 * <p><b>The levelling ladder.</b> "1 kill density 1 and so on" runs out of vanilla at 5 kills --
 * Density caps at V. Two readings are built:
 * <ul>
 *   <li>{@code LADDER} (default): Density I-V over the first five kills, then Breach I-IV, then Wind
 *       Burst I-III. Stays inside vanilla enchantment levels and keeps escalating to kill 12.</li>
 *   <li>{@code LITERAL}: Density really does equal the kill count, past the vanilla cap, up to
 *       {@code literal-max-density}. Density scales with fall distance, so this gets absurd fast --
 *       that is the point, but it is worth knowing before switching it on.</li>
 * </ul>
 */
public class TechKnightKit implements PowerKit, Listener {

    public static final String ID = "techknight";

    private static final String ABILITY_RESTOCK = "restock";
    private static final String ABILITY_XP = "xp";

    private final PowerSMP plugin;
    /** Maces pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    // Tuning
    private boolean ladderMode = true;
    private int literalMaxDensity = 10;
    private boolean countPlayerKills = true;
    private boolean countMobKills = true;
    private boolean requireMaceInHand = true;
    private boolean maceUnbreakable = true;

    private double restockCooldown = 18000.0d;
    private final List<ItemStack> restockItems = new ArrayList<>();

    private boolean xpFillInventory = true;
    private int xpMaxStacks = 36;

    public TechKnightKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mace Massacre";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection mace = section.getConfigurationSection("mace");
        if (mace != null) {
            ladderMode = !"LITERAL".equalsIgnoreCase(mace.getString("mode", "LADDER"));
            literalMaxDensity = Math.max(1, mace.getInt("literal-max-density", literalMaxDensity));
            countPlayerKills = mace.getBoolean("count-player-kills", true);
            countMobKills = mace.getBoolean("count-mob-kills", true);
            requireMaceInHand = mace.getBoolean("require-mace-in-hand", true);
            maceUnbreakable = mace.getBoolean("unbreakable", true);
        }

        ConfigurationSection restock = section.getConfigurationSection("restock");
        restockItems.clear();
        if (restock != null) {
            restockCooldown = restock.getDouble("cooldown-seconds", restockCooldown);
            for (String entry : restock.getStringList("items")) {
                ItemStack parsed = parseItem(entry);
                if (parsed != null) {
                    restockItems.add(parsed);
                }
            }
        }

        ConfigurationSection xp = section.getConfigurationSection("xp-bottles");
        if (xp != null) {
            xpFillInventory = xp.getBoolean("fill-inventory", true);
            xpMaxStacks = Math.max(1, xp.getInt("max-stacks", xpMaxStacks));
        }

        plugin.cooldowns().registerLabel(ABILITY_RESTOCK, "Restock");
        // Five hours is far longer than a server uptime; without this a restart is a free use.
        plugin.cooldowns().registerPersistent(ABILITY_RESTOCK);
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

    // ---- the mace -------------------------------------------------------

    private MaceItem.Levels levelsFor(int kills) {
        if (!ladderMode) {
            return new MaceItem.Levels(Math.min(kills, literalMaxDensity), 0, 0);
        }
        int density = Math.min(kills, 5);
        int breach = clamp(kills - 5, 0, 4);
        int windBurst = clamp(kills - 9, 0, 3);
        return new MaceItem.Levels(density, breach, windBurst);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Gives the mace if it is missing, or re-syncs its level if it has drifted from player data. */
    public void ensureMace(Player owner) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        int kills = data.maceKills();

        for (ItemStack item : owner.getInventory().getContents()) {
            if (MaceItem.isSoulbound(item)) {
                if (MaceItem.killsOf(item) != kills) {
                    MaceItem.apply(item, kills, levelsFor(kills));
                }
                return;
            }
        }
        ItemStack mace = MaceItem.create(owner.getUniqueId(), kills, levelsFor(kills), maceUnbreakable);
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(mace);
        if (!leftover.isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), mace);
            Text.msg(owner, "<yellow>Your mace was dropped at your feet -- your inventory is full.");
        }
    }

    @Override
    public void onJoin(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.MACE_MASSACRE)) {
            ensureMace(owner);
        }
    }

    /** Every kill levels the mace. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.MACE_MASSACRE)) {
            return;
        }
        boolean victimIsPlayer = event.getEntity() instanceof Player;
        if (victimIsPlayer ? !countPlayerKills : !countMobKills) {
            return;
        }
        ItemStack held = killer.getInventory().getItemInMainHand();
        if (requireMaceInHand && !MaceItem.isSoulbound(held)) {
            return;
        }

        PlayerData data = plugin.data().get(killer.getUniqueId());
        int before = data.maceKills();
        data.maceKills(before + 1);
        plugin.data().markDirty();

        MaceItem.Levels was = levelsFor(before);
        MaceItem.Levels now = levelsFor(data.maceKills());

        ItemStack mace = MaceItem.isSoulbound(held) ? held : findMace(killer);
        if (mace != null) {
            MaceItem.apply(mace, data.maceKills(), now);
        }
        if (!was.equals(now)) {
            Text.msg(killer, "<gold>Massacre</gold> <gray>-- " + describe(now) + "</gray>");
            killer.playSound(killer.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.5f);
        } else {
            Text.actionBar(killer, "<gray>Massacre: " + data.maceKills() + " kills</gray>");
        }
    }

    private String describe(MaceItem.Levels levels) {
        List<String> parts = new ArrayList<>();
        if (levels.density() > 0) {
            parts.add("Density " + MaceItem.numeral(levels.density()));
        }
        if (levels.breach() > 0) {
            parts.add("Breach " + MaceItem.numeral(levels.breach()));
        }
        if (levels.windBurst() > 0) {
            parts.add("Wind Burst " + MaceItem.numeral(levels.windBurst()));
        }
        return parts.isEmpty() ? "no enchantments yet" : String.join(", ", parts);
    }

    private ItemStack findMace(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (MaceItem.isSoulbound(item)) {
                return item;
            }
        }
        return null;
    }

    // ---- "can't be taken away, even if I die" ---------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        // With keepInventory on, drops are empty and the mace never leaves -- nothing to do.
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (MaceItem.isSoulbound(drop)) {
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
        if (stashed == null) {
            return;
        }
        // Respawn inventory is not populated until after this event resolves.
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stashed);
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stashed);
                }
                Text.msg(player, "<gray>Your mace came back with you.</gray>");
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (MaceItem.isSoulbound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your mace will not leave you.</red>");
        }
    }

    /** Stops the mace being stashed in a chest, given away, or dropped into another inventory. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return; // The player's own inventory screen -- rearranging is fine.
        }
        if (MaceItem.isSoulbound(event.getCurrentItem()) || MaceItem.isSoulbound(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING
                && MaceItem.isSoulbound(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_RESTOCK, "Restock",
                        "Refill your utility kit. " + (int) (restockCooldown / 3600) + "h cooldown."),
                new Ability(ABILITY_XP, "XP Bottles",
                        "Fill your inventory with experience bottles. No cooldown."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_RESTOCK;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_RESTOCK -> restock(owner);
            case ABILITY_XP -> xpBottles(owner);
            default -> false;
        };
    }

    private boolean restock(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RESTOCK)) {
            return plugin.unlocks().denyLocked(owner, Power.RESTOCK);
        }
        if (restockItems.isEmpty()) {
            Text.msg(owner, "<red>No restock kit is configured. "
                    + "Set <white>techknight.restock.items</white> in kits.yml.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_RESTOCK, restockCooldown)) {
            return false;
        }
        int delivered = 0;
        int dropped = 0;
        for (ItemStack template : restockItems) {
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(template.clone());
            delivered++;
            for (ItemStack overflow : leftover.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), overflow);
                dropped++;
            }
        }
        owner.playSound(owner.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.2f);
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
        Text.msg(owner, "<green>+" + stacks + "</green> <gray>stack(s) of experience bottles.</gray>");
        return true;
    }
}
