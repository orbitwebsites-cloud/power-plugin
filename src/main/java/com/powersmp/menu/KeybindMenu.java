package com.powersmp.menu;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.AbilityTrigger;
import com.powersmp.kit.PowerKit;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Guided keybind editor: choose a gesture, then choose the ability it should fire.
 */
public class KeybindMenu implements Listener {

    public static final String UNBOUND = "__none__";

    private static final int SIZE = 54;
    private static final int[] TRIGGER_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int ABILITY_START = 27;
    private static final int ABILITIES_PER_PAGE = 18;
    private static final int PREVIOUS_PAGE = 45;
    private static final int CLEAR_SELECTED = 47;
    private static final int RESET_DEFAULT = 48;
    private static final int CLOSE = 49;
    private static final int CLEAR_ALL = 50;
    private static final int NEXT_PAGE = 53;

    private final PowerSMP plugin;

    public KeybindMenu(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Holder holder = new Holder(AbilityTrigger.SNEAK_RIGHT_CLICK);
        Inventory inventory = Bukkit.createInventory(
                holder, SIZE, Text.mm("<dark_gray>Power Controls</dark_gray>"));
        holder.inventory = inventory;
        redraw(player, inventory);
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void redraw(Player player, Inventory inventory) {
        Holder holder = (Holder) inventory.getHolder();
        inventory.clear();
        fillBorders(inventory);

        inventory.setItem(4, item(Material.KNOWLEDGE_BOOK,
                "<aqua><bold>HOW TO BIND A POWER</bold></aqua>",
                "<white>1.</white> <gray>Choose a gesture below.</gray>",
                "<white>2.</white> <gray>Choose the power it should activate.</gray>",
                "<dark_gray>Tip: rebind Swap Hands in Minecraft Controls",
                "<dark_gray>to turn it into your own custom power key.</dark_gray>"));

        AbilityTrigger[] triggers = AbilityTrigger.values();
        for (int i = 0; i < triggers.length && i < TRIGGER_SLOTS.length; i++) {
            String bound = binding(player, triggers[i]);
            inventory.setItem(TRIGGER_SLOTS[i], triggerIcon(
                    triggers[i], triggers[i] == holder.selectedTrigger,
                    abilityName(player, bound)));
        }

        String selectedId = binding(player, holder.selectedTrigger);
        inventory.setItem(22, item(Material.COMPASS,
                "<gold><bold>NOW EDITING</bold></gold>",
                "<white>" + Text.plain(holder.selectedTrigger.label()) + "</white>",
                "<gray>Current power: <aqua>" + Text.plain(abilityName(player, selectedId))
                        + "</aqua></gray>",
                "<yellow>Click a power below to bind it.</yellow>"));

        List<AbilityEntry> abilities = allAbilities(plugin.kits().kitsOf(player));
        int maxPage = Math.max(0, (abilities.size() - 1) / ABILITIES_PER_PAGE);
        holder.page = Math.min(holder.page, maxPage);
        int offset = holder.page * ABILITIES_PER_PAGE;
        for (int i = 0; i < ABILITIES_PER_PAGE; i++) {
            int index = offset + i;
            int slot = ABILITY_START + i;
            if (index >= abilities.size()) {
                inventory.setItem(slot, emptySlot());
                continue;
            }
            AbilityEntry entry = abilities.get(index);
            inventory.setItem(slot, abilityIcon(entry,
                    entry.ability.id().equalsIgnoreCase(selectedId)));
        }

        inventory.setItem(PREVIOUS_PAGE, holder.page > 0
                ? item(Material.ARROW, "<yellow>Previous page</yellow>",
                        "<gray>Page " + holder.page + " of " + (maxPage + 1) + "</gray>")
                : emptySlot());
        inventory.setItem(NEXT_PAGE, holder.page < maxPage
                ? item(Material.ARROW, "<yellow>Next page</yellow>",
                        "<gray>Page " + (holder.page + 2) + " of " + (maxPage + 1) + "</gray>")
                : emptySlot());
        inventory.setItem(CLEAR_SELECTED, item(Material.BARRIER,
                "<red>Unbind selected gesture</red>",
                "<gray>Only clears <white>" + Text.plain(holder.selectedTrigger.label())
                        + "</white>.</gray>"));
        inventory.setItem(RESET_DEFAULT, item(Material.RECOVERY_COMPASS,
                "<green>Restore default controls</green>",
                "<gray>Clears custom controls and restores</gray>",
                "<white>Sneak + Right Click</white><gray> as the primary action.</gray>"));
        inventory.setItem(CLOSE, item(Material.OAK_DOOR,
                "<white>Done</white>", "<gray>Close this menu.</gray>"));
        inventory.setItem(CLEAR_ALL, item(Material.LAVA_BUCKET,
                "<dark_red>Unbind everything</dark_red>",
                "<gray>Turns every gesture off. You can still use</gray>",
                "<white>/power &lt;ability&gt;</white><gray> or this menu.</gray>"));
    }

    private String binding(Player player, AbilityTrigger trigger) {
        com.powersmp.data.PlayerData data = plugin.data().get(player.getUniqueId());
        String explicit = data.abilityBindings().get(trigger.name());
        if (explicit != null) {
            return explicit;
        }
        if (!data.abilityBindings().isEmpty() || trigger != AbilityTrigger.SNEAK_RIGHT_CLICK) {
            return "";
        }
        if (!data.primaryAbility().isBlank()) {
            return data.primaryAbility();
        }
        List<PowerKit> kits = plugin.kits().kitsOf(player);
        return kits.isEmpty() || kits.get(0).primaryAbilityId() == null
                ? "" : kits.get(0).primaryAbilityId();
    }

    private List<AbilityEntry> allAbilities(List<PowerKit> kits) {
        List<AbilityEntry> abilities = new ArrayList<>();
        for (PowerKit kit : kits) {
            for (Ability ability : kit.abilities()) {
                abilities.add(new AbilityEntry(kit, ability));
            }
        }
        return abilities;
    }

    private String abilityName(Player player, String id) {
        if (id.isBlank() || UNBOUND.equalsIgnoreCase(id)) {
            return "Unbound";
        }
        for (AbilityEntry entry : allAbilities(plugin.kits().kitsOf(player))) {
            if (entry.ability.id().equalsIgnoreCase(id)) {
                return entry.ability.name();
            }
        }
        return "Unavailable";
    }

    private ItemStack triggerIcon(AbilityTrigger trigger, boolean selected, String bound) {
        Material material = switch (trigger) {
            case RIGHT_CLICK, SNEAK_RIGHT_CLICK -> Material.WARPED_FUNGUS_ON_A_STICK;
            case LEFT_CLICK, SNEAK_LEFT_CLICK -> Material.IRON_SWORD;
            case SWAP_HANDS, SNEAK_SWAP_HANDS -> Material.SHIELD;
        };
        String color = selected ? "<green><bold>" : "<white>";
        return item(material, color + Text.plain(trigger.label()),
                "<gray>" + Text.plain(shortDescription(trigger)) + "</gray>",
                "<aqua>Bound power: <white>" + Text.plain(bound) + "</white></aqua>",
                selected ? "<green>Selected — choose a power below</green>"
                        : "<yellow>Click to edit this gesture</yellow>");
    }

    private String shortDescription(AbilityTrigger trigger) {
        return switch (trigger) {
            case SNEAK_RIGHT_CLICK -> "Hold Sneak, then use/right-click.";
            case RIGHT_CLICK -> "Use/right-click without sneaking.";
            case SNEAK_LEFT_CLICK -> "Hold Sneak, then attack/left-click.";
            case LEFT_CLICK -> "Attack/left-click without sneaking.";
            case SWAP_HANDS -> "Press Swap Hands (F by default).";
            case SNEAK_SWAP_HANDS -> "Hold Sneak, then press Swap Hands.";
        };
    }

    private ItemStack abilityIcon(AbilityEntry entry, boolean selected) {
        return item(selected ? Material.LIME_DYE : abilityMaterial(entry.ability.id()),
                (selected ? "<green><bold>" : "<white>") + Text.plain(entry.ability.name()),
                "<dark_gray>" + Text.plain(entry.kit.displayName()) + "</dark_gray>",
                "<gray>" + Text.plain(entry.ability.description()) + "</gray>",
                selected ? "<green>Currently bound to this gesture</green>"
                        : "<yellow>Click to bind this power</yellow>");
    }

    private Material abilityMaterial(String id) {
        String value = id.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("grapple") || value.contains("chain")) return Material.LEAD;
        if (value.contains("blood")) return Material.REDSTONE;
        if (value.contains("heal")) return Material.GOLDEN_APPLE;
        if (value.contains("flight") || value.contains("wind")) return Material.FEATHER;
        if (value.contains("shadow") || value.contains("astral") || value.contains("phantom")) {
            return Material.ENDER_EYE;
        }
        if (value.contains("explosion") || value.contains("bomb")) return Material.TNT;
        if (value.contains("bone") || value.contains("cage")) return Material.BONE;
        if (value.contains("restock") || value.contains("loadout")) return Material.CHEST;
        if (value.contains("xp")) return Material.EXPERIENCE_BOTTLE;
        if (value.contains("jackpot") || value.contains("lucky")) return Material.EMERALD;
        return Material.NETHER_STAR;
    }

    private void fillBorders(Inventory inventory) {
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray> </dark_gray>");
        for (int slot = 0; slot < SIZE; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 2 || row == 5 || column == 0 || column == 8) {
                inventory.setItem(slot, border);
            }
        }
    }

    private ItemStack emptySlot() {
        return item(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Empty</dark_gray>");
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm(name));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Text.mm(line));
            }
            meta.lore(lines);
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        AbilityTrigger[] triggers = AbilityTrigger.values();
        for (int i = 0; i < triggers.length && i < TRIGGER_SLOTS.length; i++) {
            if (slot == TRIGGER_SLOTS[i]) {
                holder.selectedTrigger = triggers[i];
                holder.page = 0;
                click(player, 1.35f);
                redraw(player, event.getInventory());
                return;
            }
        }

        if (slot >= ABILITY_START && slot < ABILITY_START + ABILITIES_PER_PAGE) {
            List<AbilityEntry> abilities = allAbilities(plugin.kits().kitsOf(player));
            int index = holder.page * ABILITIES_PER_PAGE + slot - ABILITY_START;
            if (index < abilities.size()) {
                Ability chosen = abilities.get(index).ability;
                plugin.data().get(player.getUniqueId()).abilityBindings()
                        .put(holder.selectedTrigger.name(), chosen.id());
                plugin.data().markDirty();
                click(player, 1.65f);
                Text.actionBar(player, "<green>" + Text.plain(holder.selectedTrigger.label())
                        + " → " + Text.plain(chosen.name()) + "</green>");
                redraw(player, event.getInventory());
            }
            return;
        }

        if (slot == PREVIOUS_PAGE && holder.page > 0) {
            holder.page--;
            click(player, 1.25f);
        } else if (slot == NEXT_PAGE
                && (holder.page + 1) * ABILITIES_PER_PAGE
                < allAbilities(plugin.kits().kitsOf(player)).size()) {
            holder.page++;
            click(player, 1.25f);
        } else if (slot == CLEAR_SELECTED) {
            plugin.data().get(player.getUniqueId()).abilityBindings()
                    .put(holder.selectedTrigger.name(), UNBOUND);
            plugin.data().markDirty();
            click(player, 0.75f);
        } else if (slot == RESET_DEFAULT) {
            plugin.data().get(player.getUniqueId()).abilityBindings().clear();
            plugin.data().get(player.getUniqueId()).primaryAbility("");
            plugin.data().markDirty();
            holder.selectedTrigger = AbilityTrigger.SNEAK_RIGHT_CLICK;
            holder.page = 0;
            click(player, 1.8f);
        } else if (slot == CLEAR_ALL) {
            Map<String, String> bindings =
                    plugin.data().get(player.getUniqueId()).abilityBindings();
            bindings.clear();
            for (AbilityTrigger trigger : AbilityTrigger.values()) {
                bindings.put(trigger.name(), UNBOUND);
            }
            plugin.data().get(player.getUniqueId()).primaryAbility("");
            plugin.data().markDirty();
            click(player, 0.55f);
        } else if (slot == CLOSE) {
            player.closeInventory();
            click(player, 1.4f);
            return;
        } else {
            return;
        }
        redraw(player, event.getInventory());
    }

    private void click(Player player, float pitch) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.75f, pitch);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private record AbilityEntry(PowerKit kit, Ability ability) {
    }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private AbilityTrigger selectedTrigger;
        private int page;

        private Holder(AbilityTrigger selectedTrigger) {
            this.selectedTrigger = selectedTrigger;
        }

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
