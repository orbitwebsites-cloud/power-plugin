package com.powersmp.item;

import com.powersmp.PowerSMP;
import com.powersmp.util.Keys;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps signature items out of hoppers and out of the wrong player's inventory, and enforces the
 * server-wide mace ban.
 *
 * <p>Drop/click guards in individual kits cover normal use, but an overflow item placed on the
 * ground could still be picked up by someone else or vacuumed into a hopper. The owner would then
 * receive a replacement on the next tick, duplicating the weapon. Mace checks intentionally use
 * only the material, never a plugin tag, so vanilla and third-party maces are removed too.
 */
public final class BoundItemListener implements Listener {

    private final PowerSMP plugin;

    public BoundItemListener(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (isMace(item)) {
            event.setCancelled(true);
            event.getItem().remove();
            if (event.getEntity() instanceof Player player) {
                com.powersmp.util.Text.actionBar(
                        player, "<gray>Maces are disabled on this server.</gray>");
            }
            return;
        }
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
        if (isMace(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        } else if (isBound(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Prevents crafting grids and container slots becoming an indirect drop/duplication route. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isMace(event.getCurrentItem())) {
            event.setCurrentItem(null);
            event.setCancelled(true);
            return;
        }
        if (isMace(event.getCursor())) {
            event.getWhoClicked().setItemOnCursor(null);
            event.setCancelled(true);
            return;
        }
        boolean outsidePlayerInventory = !(event.getClickedInventory() instanceof PlayerInventory);
        if (outsidePlayerInventory && isBound(event.getCurrentItem())
                && (!(event.getWhoClicked() instanceof Player player)
                        || !mayCarry(player, event.getCurrentItem())
                        || alreadyCarries(player, event.getCurrentItem()))) {
            event.setCancelled(true);
            return;
        }
        if (outsidePlayerInventory && isBound(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick() && isBound(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        int hotbar = event.getHotbarButton();
        if (hotbar >= 0 && isMace(event.getWhoClicked().getInventory().getItem(hotbar))) {
            event.getWhoClicked().getInventory().setItem(hotbar, null);
            event.setCancelled(true);
            return;
        }
        if (outsidePlayerInventory && hotbar >= 0
                && isBound(event.getWhoClicked().getInventory().getItem(hotbar))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isMace(event.getOldCursor())) {
            event.setCancelled(true);
            event.getWhoClicked().setItemOnCursor(null);
            return;
        }
        if (!isBound(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    /** Removes every mace, including vanilla, custom, legacy, and kit-issued variants. */
    public int purgeMaces(Player player) {
        int removed = purge(player.getInventory()) + purge(player.getEnderChest());
        if (removed > 0) {
            com.powersmp.util.Text.msg(player,
                    "<gray>" + removed + " mace" + (removed == 1 ? " was" : "s were")
                            + " removed because maces are disabled.</gray>");
        }
        return removed;
    }

    /** Cleans all currently loaded chunks; newly loaded chunks are handled by {@link #onChunkLoad}. */
    public int purgeLoadedMaces() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                removed += purge(chunk);
            }
        }
        return removed;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Item item && isMace(item.getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof LivingEntity living) {
            purge(living);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        purge(event.getView().getTopInventory());
        purge(event.getView().getBottomInventory());
        if (event.getPlayer() instanceof Player player) {
            purge(player.getEnderChest());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (isMace(event.getInventory().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isMace(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        } else {
            event.getPlayer().getInventory().setItemInMainHand(null);
        }
        com.powersmp.util.Text.actionBar(event.getPlayer(),
                "<gray>Maces are disabled on this server.</gray>");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMaceAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity attacker)
                || attacker.getEquipment() == null
                || !isMace(attacker.getEquipment().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        attacker.getEquipment().setItemInMainHand(null);
        if (attacker instanceof Player player) {
            com.powersmp.util.Text.actionBar(player,
                    "<gray>Maces are disabled on this server.</gray>");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        purge(event.getChunk());
    }

    private int purge(Inventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isMace(item)) {
                inventory.setItem(slot, null);
                removed++;
            } else {
                removed += purgeMaces(item);
            }
        }
        return removed;
    }

    private int purge(Chunk chunk) {
        int removed = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof InventoryHolder holder) {
                removed += purge(holder.getInventory());
            }
        }
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item && isMace(item.getItemStack())) {
                item.remove();
                removed++;
            } else if (entity instanceof InventoryHolder holder) {
                removed += purge(holder.getInventory());
            }
            if (entity instanceof LivingEntity living) {
                removed += purge(living);
            }
        }
        return removed;
    }

    private int purge(LivingEntity living) {
        if (living.getEquipment() == null) {
            return 0;
        }
        int removed = 0;
        if (isMace(living.getEquipment().getItemInMainHand())) {
            living.getEquipment().setItemInMainHand(null);
            removed++;
        }
        if (isMace(living.getEquipment().getItemInOffHand())) {
            living.getEquipment().setItemInOffHand(null);
            removed++;
        }
        return removed;
    }

    public static boolean isMace(ItemStack item) {
        return item != null && item.getType() == Material.MACE;
    }

    /**
     * Removes maces nested inside an item container such as a bundle or shulker box.
     *
     * @return the number removed; a direct mace returns one and must be discarded by the caller
     */
    public static int purgeMaces(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        if (isMace(item)) {
            return 1;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            int removed = 0;
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack nested : bundle.getItems()) {
                if (isMace(nested)) {
                    removed++;
                } else {
                    removed += purgeMaces(nested);
                    kept.add(nested);
                }
            }
            if (removed > 0) {
                bundle.setItems(kept);
                item.setItemMeta(bundle);
            }
            return removed;
        }
        if (meta instanceof BlockStateMeta containerMeta
                && containerMeta.getBlockState() instanceof InventoryHolder holder) {
            int removed = purgeNested(holder.getInventory());
            if (removed > 0) {
                containerMeta.setBlockState((BlockState) holder);
                item.setItemMeta(containerMeta);
            }
            return removed;
        }
        return 0;
    }

    private static int purgeNested(Inventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack nested = inventory.getItem(slot);
            if (isMace(nested)) {
                inventory.setItem(slot, null);
                removed++;
            } else {
                removed += purgeMaces(nested);
            }
        }
        return removed;
    }

    private boolean alreadyCarries(Player player, ItemStack incoming) {
        UUID titanOwner = TitanBladeItem.ownerOf(incoming);
        if (titanOwner != null) {
            return contains(player, item -> titanOwner.equals(TitanBladeItem.ownerOf(item)));
        }
        UUID bloodlustOwner = BloodlustItem.ownerOf(incoming);
        if (bloodlustOwner != null) {
            return contains(player, item -> bloodlustOwner.equals(BloodlustItem.ownerOf(item)));
        }
        UUID cutlassOwner = CutlassItem.ownerOf(incoming);
        if (cutlassOwner != null) {
            return contains(player, item -> cutlassOwner.equals(CutlassItem.ownerOf(item)));
        }
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
        UUID exactOwner = TitanBladeItem.ownerOf(item);
        if (exactOwner == null) {
            exactOwner = BloodlustItem.ownerOf(item);
        }
        if (exactOwner == null) {
            exactOwner = CutlassItem.ownerOf(item);
        }
        if (exactOwner == null) {
            exactOwner = MaceItem.ownerOf(item);
        }
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
        if (TitanBladeItem.isBoneBlade(item) || BloodlustItem.isBloodlust(item)
                || CutlassItem.isCutlass(item)
                || MaceItem.isSoulbound(item)
                || TridentItem.isBoundTrident(item) || SpearItem.isSpear(item)) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (meta.getPersistentDataContainer()
                .has(Keys.SCAR_MACE, PersistentDataType.INTEGER)
                || meta.getPersistentDataContainer().has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(Keys.DRACONIC_MACE, PersistentDataType.BYTE));
    }
}
