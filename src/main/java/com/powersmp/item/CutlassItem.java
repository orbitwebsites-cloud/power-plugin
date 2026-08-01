package com.powersmp.item;

import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Night Scar's custom cutlass. Minecraft Java has no native cutlass material. */
public final class CutlassItem {

    private CutlassItem() {
    }

    public static ItemStack create(UUID owner) {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer()
                    .set(Keys.CUTLASS_OWNER, PersistentDataType.STRING, owner.toString());
            item.setItemMeta(meta);
        }
        refresh(item);
        return item;
    }

    /** Reapplies the current appearance and combat metadata without replacing the bound item. */
    public static void refresh(ItemStack item) {
        if (!isCutlass(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<dark_red><bold>Cutlass Sword</bold></dark_red>"));
            meta.lore(List.of(
                    Text.mm("<gray>A quick iron blade built for sweeping strikes.</gray>"),
                    Text.mm("<dark_gray>Soulbound to Night Scar</dark_gray>")));
            meta.setUnbreakable(true);
            if (Enchants.SWEEPING_EDGE != null) {
                meta.addEnchant(Enchants.SWEEPING_EDGE, 3, true);
            }
            Enchants.applyVanishing(meta);
            item.setItemMeta(meta);
        }
        ResourcePackItems.apply(item, ResourcePackItems.NIGHTSCAR_CUTLASS);
    }

    public static UUID ownerOf(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer()
                .get(Keys.CUTLASS_OWNER, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean isCutlass(ItemStack item) {
        return ownerOf(item) != null;
    }
}
