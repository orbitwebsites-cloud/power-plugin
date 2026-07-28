package com.powersmp.menu;

import com.powersmp.PowerSMP;
import com.powersmp.kit.AbilityTrigger;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /power keybind} -- lets a player choose which client action fires their primary ability.
 *
 * <p>There is no way for a server plugin to see a literal key like "G" -- Minecraft only ever sends
 * the vanilla action a key is bound to (sneak, attack, use-item, swap-hands). What this menu actually
 * offers is a choice of <em>which action</em>. To land on a specific physical key, rebind that
 * vanilla action to it in Minecraft's own Controls menu -- e.g. pick "Swap Hands" here, then rebind
 * "Swap Item In Hand" from F to G in Controls, and G fires the ability. That mapping is entirely
 * client-side; the server never needs to know which physical key was pressed.
 */
public class KeybindMenu implements Listener {

    private final PowerSMP plugin;

    public KeybindMenu(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 9,
                Text.mm("<dark_gray>Ability trigger</dark_gray>"));
        holder.inventory = inventory;
        redraw(player, inventory);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void redraw(Player player, Inventory inventory) {
        AbilityTrigger current = AbilityTrigger.fromName(
                plugin.data().get(player.getUniqueId()).abilityTrigger(), AbilityTrigger.SNEAK_RIGHT_CLICK);
        AbilityTrigger[] values = AbilityTrigger.values();
        for (int i = 0; i < values.length; i++) {
            inventory.setItem(i, icon(values[i], values[i] == current));
        }
    }

    private ItemStack icon(AbilityTrigger trigger, boolean selected) {
        ItemStack item = new ItemStack(selected ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm((selected ? "<green><bold>" : "<white>") + Text.plain(trigger.label())));
            meta.lore(List.of(
                    Text.mm("<gray>" + Text.plain(trigger.description()) + "</gray>"),
                    selected ? Text.mm("<green>Currently selected</green>")
                            : Text.mm("<yellow>Click to select</yellow>")));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        AbilityTrigger[] values = AbilityTrigger.values();
        if (!(event.getWhoClicked() instanceof Player player) || slot < 0 || slot >= values.length) {
            return;
        }
        AbilityTrigger chosen = values[slot];
        plugin.data().get(player.getUniqueId()).abilityTrigger(chosen.name());
        plugin.data().markDirty();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
        Text.msg(player, "<green>Ability trigger set to <white>" + Text.plain(chosen.label()) + "</white>.</green>");
        redraw(player, event.getInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Identifies our menu without tracking open inventories by player. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
