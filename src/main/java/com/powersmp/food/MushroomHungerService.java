package com.powersmp.food;

import com.powersmp.PowerSMP;
import com.powersmp.util.Keys;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Mavricc's Mushroom Hunger food rework.
 *
 * <p><b>Why this is a stamping service and not a config switch.</b> There is no global "all stew
 * now heals like steak" setting. Nutrition and stack size are per-{@code ItemStack} data
 * components, so every individual stack has to be rewritten. This class stamps a stack the first
 * time it sees one, tagging it in its PDC with a hash of the current profile so that stacks are not
 * reprocessed every tick -- and so that changing kits.yml and reloading re-stamps everything on
 * next contact instead of leaving stale values around.
 *
 * <p>Two guardrails on top of the spec: a stack limit is only ever raised (so tagging something
 * that already stacks to 64 cannot nerf it), and food values are only written to materials that are
 * actually edible (a food component on a non-food item behaves inconsistently across the 1.21 line).
 *
 * <p><b>Scope.</b> {@code OWNER_ONLY} stamps only what the kit owner touches, which matches "this
 * is Mavricc's power". The honest caveat: components travel with the stack, so a stamped bowl of
 * stew handed to someone else keeps its buffed values. {@code GLOBAL} additionally hooks hopper and
 * dispenser movement and item spawns -- that is the "obscure vectors" edge case the spec flagged.
 */
public class MushroomHungerService implements Listener {

    private final PowerSMP plugin;

    private boolean enabled = true;
    private boolean globalScope;
    private Set<Material> mushroomFoods = EnumSet.of(Material.MUSHROOM_STEW);
    private Set<Material> exempt = EnumSet.of(Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE);
    private FoodProfile mushroomProfile = new FoodProfile(8, 12.8f, 32);
    private FoodProfile stewProfile = new FoodProfile(6, 14.4f, 32);
    private FoodProfile otherProfile = new FoodProfile(5, 6.0f, 0);
    /** Bumped whenever the profiles change, so old stamps are recognised as stale. */
    private int profileVersion = 1;

    public MushroomHungerService(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection mavricc) {
        ConfigurationSection section =
                mavricc == null ? null : mavricc.getConfigurationSection("mushroom-hunger");
        if (section == null) {
            return;
        }
        enabled = section.getBoolean("enabled", true);
        globalScope = "GLOBAL".equalsIgnoreCase(section.getString("scope", "OWNER_ONLY"));

        mushroomFoods = materials(section.getStringList("mushroom-foods.items"), mushroomFoods);
        mushroomProfile = new FoodProfile(
                section.getInt("mushroom-foods.nutrition", 8),
                (float) section.getDouble("mushroom-foods.saturation", 12.8d),
                section.getInt("mushroom-foods.max-stack-size", 32));
        stewProfile = new FoodProfile(
                section.getInt("suspicious-stew.nutrition", 6),
                (float) section.getDouble("suspicious-stew.saturation", 14.4d),
                section.getInt("suspicious-stew.max-stack-size", 32));
        otherProfile = new FoodProfile(
                section.getInt("other-food.nutrition", 5),
                (float) section.getDouble("other-food.saturation", 6.0d),
                0);
        exempt = materials(section.getStringList("other-food.exempt"), exempt);

        profileVersion = Objects.hash(mushroomFoods, mushroomProfile, stewProfile, otherProfile, exempt);
        if (profileVersion == 0) {
            profileVersion = 1;
        }
    }

    private Set<Material> materials(List<String> names, Set<Material> fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("Unknown material '" + name + "' in mushroom-hunger config");
            } else {
                out.add(material);
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    // ---- stamping -------------------------------------------------------

    private FoodProfile profileFor(Material material) {
        if (material == Material.SUSPICIOUS_STEW) {
            return stewProfile;
        }
        if (mushroomFoods.contains(material)) {
            return mushroomProfile;
        }
        if (exempt.contains(material)) {
            return null;
        }
        return material.isEdible() ? otherProfile : null;
    }

    /**
     * Rewrites a stack's food values in place if it qualifies and is not already stamped.
     *
     * @return true if the stack was modified.
     */
    public boolean stamp(ItemStack item) {
        if (!enabled || item == null || item.getType().isAir()) {
            return false;
        }
        Material material = item.getType();
        FoodProfile profile = profileFor(material);
        if (profile == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer stamped = meta.getPersistentDataContainer()
                .get(Keys.FOOD_STAMP, PersistentDataType.INTEGER);
        if (stamped != null && stamped == profileVersion) {
            return false;
        }

        boolean changed = false;
        if (material.isEdible()) {
            FoodComponent food = meta.getFood();
            food.setNutrition(profile.nutrition());
            food.setSaturation(profile.saturation());
            meta.setFood(food);
            changed = true;
        }
        // Only ever raise a stack limit -- never nerf something that already stacks higher.
        if (profile.maxStackSize() > 0 && profile.maxStackSize() > material.getMaxStackSize()) {
            meta.setMaxStackSize(profile.maxStackSize());
            changed = true;
        }
        if (!changed) {
            return false;
        }
        meta.getPersistentDataContainer().set(Keys.FOOD_STAMP, PersistentDataType.INTEGER, profileVersion);
        item.setItemMeta(meta);
        return true;
    }

    /** Stamps every slot of an inventory, writing back only the slots that changed. */
    public void stampInventory(Inventory inventory) {
        if (!enabled) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && stamp(item)) {
                inventory.setItem(slot, item);
            }
        }
    }

    private boolean inScope(Player player) {
        return enabled && (globalScope || plugin.kits().isOwner(player, "mavricc"));
    }

    /** Full inventory sweep -- run on join, so pre-existing stock gets converted. */
    public void scanPlayer(Player player) {
        if (inScope(player)) {
            stampInventory(player.getInventory());
            stampInventory(player.getEnderChest());
        }
    }

    // ---- hooks ----------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && inScope(player)) {
            stampInventory(event.getInventory());
            stampInventory(player.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !inScope(player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (stamp(clicked)) {
            event.setCurrentItem(clicked);
        }
        stamp(event.getCursor());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && inScope(player)) {
            Item entity = event.getItem();
            ItemStack stack = entity.getItemStack();
            if (stamp(stack)) {
                entity.setItemStack(stack);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !inScope(player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (stamp(result)) {
            event.setCurrentItem(result);
        }
    }

    /** Last line of defence: stamp the stack actually being eaten. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!inScope(event.getPlayer())) {
            return;
        }
        ItemStack item = event.getItem();
        if (stamp(item)) {
            event.setItem(item);
        }
    }

    // ---- GLOBAL scope only ----------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!enabled || !globalScope) {
            return;
        }
        stamp(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled || !globalScope) {
            return;
        }
        Item entity = event.getEntity();
        ItemStack stack = entity.getItemStack();
        if (stamp(stack)) {
            entity.setItemStack(stack);
        }
    }

    public boolean isGlobalScope() {
        return globalScope;
    }

    /** Lowercase description used by {@code /powersmp info}. */
    public String scopeName() {
        return globalScope ? "global" : "owner-only";
    }
}
