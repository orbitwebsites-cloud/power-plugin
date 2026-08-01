package com.powersmp.menu;

import com.powersmp.PowerSMP;
import com.powersmp.item.BoundItemListener;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Loadout editor for TechKnightGaming's Restock: a row of slots he fills with whatever he wants,
 * saved per-player and handed back every time Restock fires.
 *
 * <p><b>Nothing here ever moves a real item.</b> Every click is cancelled and handled by hand:
 * clicking a stack in your own inventory copies it into the loadout, clicking a loadout slot clears
 * it. That is deliberate. If the menu accepted genuine drag-and-drop it would have to either keep
 * the items — quietly taking one of everything he configures — or hand them back on close, which
 * duplicates whatever was pre-filled from the saved loadout. Copying sidesteps both: nothing is
 * consumed, nothing is duplicated, and the menu can safely show the current loadout when it opens.
 */
public class RestockMenu implements Listener {

    private static final int MAX_SLOTS = 27;

    private final PowerSMP plugin;
    private int slots = 7;

    public RestockMenu(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void slots(int slots) {
        this.slots = Math.max(1, Math.min(MAX_SLOTS, slots));
    }

    public int slots() {
        return slots;
    }

    public void open(Player player) {
        Holder holder = new Holder();
        // Inventories come in rows of nine; round up and block whatever is left over.
        int size = ((slots + 8) / 9) * 9;
        Inventory inventory = Bukkit.createInventory(holder, size,
                Text.mm("<dark_gray>Restock loadout</dark_gray>"));
        holder.inventory = inventory;

        List<ItemStack> saved = plugin.data().get(player.getUniqueId()).restockLoadout();
        if (saved.removeIf(BoundItemListener::isMace)) {
            plugin.data().markDirty();
        }
        for (ItemStack item : saved) {
            if (BoundItemListener.purgeMaces(item) > 0) {
                plugin.data().markDirty();
            }
        }
        for (int i = 0; i < slots && i < saved.size(); i++) {
            ItemStack item = saved.get(i);
            if (item != null) {
                inventory.setItem(i, item.clone());
            }
        }
        for (int i = slots; i < size; i++) {
            inventory.setItem(i, blocker());
        }

        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.3f);
        Text.msg(player, "<gray>Click a stack in your inventory to add it, or a slot above to clear "
                + "it. Nothing is taken from you -- the amount you click is the amount you get.</gray>");
    }

    private ItemStack blocker() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_gray>-</dark_gray>"));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        // Cancel unconditionally: every effect below is applied by hand.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        int raw = event.getRawSlot();
        if (raw < 0) {
            return;
        }

        if (raw < top.getSize()) {
            if (raw >= slots) {
                return;
            }
            ItemStack existing = top.getItem(raw);
            if (existing != null && !existing.getType().isAir()) {
                top.setItem(raw, null);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
            }
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        if (BoundItemListener.isMace(clicked)) {
            Text.msg(player, "<red>Maces cannot be added because they are disabled.</red>");
            return;
        }
        int free = firstFreeSlot(top);
        if (free < 0) {
            Text.msg(player, "<red>All " + slots + " slots are full. Clear one first.");
            return;
        }
        ItemStack copy = clicked.clone();
        int nestedMaces = BoundItemListener.purgeMaces(copy);
        top.setItem(free, copy);
        if (nestedMaces > 0) {
            Text.msg(player, "<gray>Removed " + nestedMaces
                    + " hidden mace(s) from that container.</gray>");
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    private int firstFreeSlot(Inventory inventory) {
        for (int i = 0; i < slots; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    /** Dragging would bypass the click handler entirely. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        List<ItemStack> loadout = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()
                    && !BoundItemListener.isMace(item)) {
                BoundItemListener.purgeMaces(item);
                loadout.add(item.clone());
            }
        }
        List<ItemStack> stored = plugin.data().get(player.getUniqueId()).restockLoadout();
        stored.clear();
        stored.addAll(loadout);
        plugin.data().markDirty();

        Text.msg(player, loadout.isEmpty()
                ? "<yellow>Restock loadout cleared -- it will fall back to the server default.</yellow>"
                : "<green>Restock loadout saved</green> <gray>-- " + loadout.size()
                        + " item(s).</gray>");
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_CLOSE, 0.7f, 1.3f);
    }

    /** Identifies our menu without needing to track open inventories by player. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
