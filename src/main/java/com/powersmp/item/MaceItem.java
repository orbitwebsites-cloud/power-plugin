package com.powersmp.item;

import com.powersmp.util.Keys;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Read-only recognizer for retired Tech Knight maces, kept solely for safe migration cleanup. */
public final class MaceItem {

    private MaceItem() {
    }

    public static boolean isSoulbound(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SOULBOUND_MACE, PersistentDataType.STRING);
    }

    public static UUID ownerOf(ItemStack item) {
        if (!isSoulbound(item)) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.SOULBOUND_MACE, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
