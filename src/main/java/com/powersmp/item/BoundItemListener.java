package com.powersmp.item;

import com.powersmp.PowerSMP;
import com.powersmp.util.Keys;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps signature items out of hoppers and out of the wrong player's inventory.
 *
 * <p>Drop/click guards in individual kits cover normal use, but an overflow item placed on the
 * ground could still be picked up by someone else or vacuumed into a hopper. The owner would then
 * receive a replacement on the next tick, duplicating the weapon.
 */
public final class BoundItemListener implements Listener {

    private final PowerSMP plugin;

    public BoundItemListener(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!isBound(item)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || !mayCarry(player, item)) {
            event.setCancelled(true);
            return;
        }
        if (alreadyCarries(player, item)) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isBound(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Prevents crafting grids and container slots becoming an indirect drop/duplication route. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        boolean outsidePlayerInventory = !(event.getClickedInventory() instanceof PlayerInventory);
        if (outsidePlayerInventory && isBound(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick() && isBound(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        int hotbar = event.getHotbarButton();
        if (outsidePlayerInventory && hotbar >= 0
                && isBound(event.getWhoClicked().getInventory().getItem(hotbar))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isBound(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private boolean alreadyCarries(Player player, ItemStack incoming) {
        UUID maceOwner = MaceItem.ownerOf(incoming);
        if (maceOwner != null) {
            return contains(player, item -> maceOwner.equals(MaceItem.ownerOf(item)));
        }
        UUID tridentOwner = TridentItem.ownerOf(incoming);
        if (tridentOwner != null) {
            return contains(player, item -> tridentOwner.equals(TridentItem.ownerOf(item)));
        }
        UUID spearOwner = SpearItem.ownerOf(incoming);
        if (spearOwner != null) {
            return contains(player, item -> spearOwner.equals(SpearItem.ownerOf(item)));
        }
        ItemMeta meta = incoming.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.getPersistentDataContainer().has(Keys.SCAR_MACE, PersistentDataType.INTEGER)) {
            return contains(player, this::isScarMace);
        }
        if (meta.getPersistentDataContainer().has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE)) {
            return contains(player, this::isBoundElytra);
        }
        if (meta.getPersistentDataContainer().has(Keys.DRACONIC_MACE, PersistentDataType.BYTE)) {
            return contains(player, this::isDraconicMace);
        }
        return false;
    }

    private boolean contains(Player player, java.util.function.Predicate<ItemStack> matcher) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (matcher.test(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKey(ItemStack item, org.bukkit.NamespacedKey key, PersistentDataType<?, ?> type) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, type);
    }

    private boolean isScarMace(ItemStack item) {
        return hasKey(item, Keys.SCAR_MACE, PersistentDataType.INTEGER);
    }

    private boolean isBoundElytra(ItemStack item) {
        return hasKey(item, Keys.BOUND_ELYTRA, PersistentDataType.BYTE);
    }

    private boolean isDraconicMace(ItemStack item) {
        return hasKey(item, Keys.DRACONIC_MACE, PersistentDataType.BYTE);
    }

    private boolean mayCarry(Player player, ItemStack item) {
        UUID exactOwner = MaceItem.ownerOf(item);
        if (exactOwner == null) {
            exactOwner = TridentItem.ownerOf(item);
        }
        if (exactOwner == null) {
            exactOwner = SpearItem.ownerOf(item);
        }
        if (exactOwner != null) {
            return exactOwner.equals(player.getUniqueId());
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.getPersistentDataContainer().has(Keys.SCAR_MACE, PersistentDataType.INTEGER)) {
            return plugin.kits().isOwner(player, "night_scar3");
        }
        if (meta.getPersistentDataContainer().has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(Keys.DRACONIC_MACE, PersistentDataType.BYTE)) {
            return plugin.kits().isOwner(player, "mavricc");
        }
        return false;
    }

    private boolean isBound(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (MaceItem.isSoulbound(item) || TridentItem.isBoundTrident(item) || SpearItem.isSpear(item)) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (meta.getPersistentDataContainer()
                .has(Keys.SCAR_MACE, PersistentDataType.INTEGER)
                || meta.getPersistentDataContainer().has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(Keys.DRACONIC_MACE, PersistentDataType.BYTE));
    }
}
