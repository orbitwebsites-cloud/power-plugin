package com.powersmp.menu;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
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
 *
 * <p>A player can have more than one kit at once (see {@link PowerKit} multi-kit support); every
 * kit's abilities are pooled into one menu, each slot remembering which kit it belongs to so a
 * click activates the right one.
 */
public class PowerMenu implements Listener {

    private final PowerSMP plugin;

    public PowerMenu(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, List<PowerKit> kits) {
        List<Entry> entries = new ArrayList<>();
        for (PowerKit kit : kits) {
            for (Ability ability : kit.abilities()) {
                entries.add(new Entry(kit, ability));
            }
        }
        if (entries.isEmpty()) {
            Text.msg(player, "<gray>None of your kits have activated abilities -- they are all passive.</gray>");
            return;
        }
        Holder holder = new Holder(entries);
        int size = Math.min(54, ((entries.size() + 8) / 9) * 9);
        String title = kits.size() == 1
                ? Text.plain(kits.get(0).displayName())
                : entries.size() + " abilities";
        Inventory inventory = Bukkit.createInventory(holder, size,
                Text.mm("<dark_gray>" + title + " -- powers</dark_gray>"));
        holder.inventory = inventory;
        redraw(player, inventory, entries);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void redraw(Player player, Inventory inventory, List<Entry> entries) {
        for (int i = 0; i < entries.size() && i < inventory.getSize(); i++) {
            inventory.setItem(i, icon(player, entries.get(i).ability));
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
        List<Entry> entries = holder.entries;
        if (slot >= entries.size()) {
            return;
        }
        Entry entry = entries.get(slot);
        entry.kit.activate(player, entry.ability.id());
        redraw(player, event.getInventory(), entries);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private record Entry(PowerKit kit, Ability ability) {
    }

    /** Identifies our menu, and which kit each slot belongs to, without tracking open inventories by player. */
    private static final class Holder implements InventoryHolder {
        private final List<Entry> entries;
        private Inventory inventory;

        private Holder(List<Entry> entries) {
            this.entries = entries;
        }

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
