package com.powersmp.menu;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
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
 * A clickable ability menu -- {@code /power gui}.
 *
 * <p>Built for console players. Several kits gate abilities behind an exact typed id
 * ({@code /power spear}, {@code /power cookie_stash}) or a sneak + secondary-key gesture; both are
 * fine on a keyboard and genuinely awkward on a controller, where "type the right word under
 * pressure" means fighting an on-screen keyboard. Clicking an item in an inventory is something a
 * controller (and Bedrock via Geyser) already does well, so every kit's abilities are listed here
 * with their live cooldown state, and clicking one fires it and redraws in place -- no typing
 * required for anything this menu covers. It is additive: {@code /power} and {@code /power list}
 * behave exactly as before.
 */
public class PowerMenu implements Listener {

    private final PowerSMP plugin;

    public PowerMenu(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, PowerKit kit) {
        if (kit.abilities().isEmpty()) {
            Text.msg(player, "<gray>" + Text.plain(kit.displayName())
                    + " has no activated abilities -- it is all passive.</gray>");
            return;
        }
        Holder holder = new Holder(kit);
        int size = Math.min(54, ((kit.abilities().size() + 8) / 9) * 9);
        Inventory inventory = Bukkit.createInventory(holder, size,
                Text.mm("<dark_gray>" + Text.plain(kit.displayName()) + " -- powers</dark_gray>"));
        holder.inventory = inventory;
        redraw(player, inventory, kit);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void redraw(Player player, Inventory inventory, PowerKit kit) {
        List<Ability> abilities = kit.abilities();
        for (int i = 0; i < abilities.size() && i < inventory.getSize(); i++) {
            inventory.setItem(i, icon(player, abilities.get(i)));
        }
    }

    private ItemStack icon(Player player, Ability ability) {
        long remaining = plugin.cooldowns().remainingMillis(player.getUniqueId(), ability.id());
        boolean ready = remaining <= 0L;
        ItemStack item = new ItemStack(ready ? Material.NETHER_STAR : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm((ready ? "<green>" : "<red>") + Text.plain(ability.name())));
            meta.lore(List.of(
                    Text.mm("<gray>" + Text.plain(ability.description()) + "</gray>"),
                    Text.mm(ready ? "<green>Ready -- click to use</green>"
                            : "<red>Cooldown: " + Text.duration(remaining) + "</red>")));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player)
                || slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }
        List<Ability> abilities = holder.kit.abilities();
        if (slot >= abilities.size()) {
            return;
        }
        holder.kit.activate(player, abilities.get(slot).id());
        redraw(player, event.getInventory(), holder.kit);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Identifies our menu, and which kit it belongs to, without tracking open inventories by player. */
    private static final class Holder implements InventoryHolder {
        private final PowerKit kit;
        private Inventory inventory;

        private Holder(PowerKit kit) {
            this.kit = kit;
        }

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
