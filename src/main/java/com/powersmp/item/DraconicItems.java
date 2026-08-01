package com.powersmp.item;

import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Draconic Evolution's omelet plus a recognizer for retired Draconic Maces. */
public final class DraconicItems {

    private DraconicItems() {
    }

    /**
     * The Dragon Omelet -- eaten once to consolidate all three stances.
     *
     * <p>Any edible material works; the identity lives in the PDC tag, not the material, so the
     * server can swap the look without breaking omelets already in circulation.
     */
    public static ItemStack omelet(Material material) {
        ItemStack omelet = new ItemStack(material == null ? Material.PUMPKIN_PIE : material);
        ItemMeta meta = omelet.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<gradient:#c77dff:#7b2cbf><bold>Dragon Omelet</bold></gradient>"));
            meta.lore(List.of(
                    Text.mm("<gray>Eat to fuse red, blue and green</gray>"),
                    Text.mm("<gray>into a single stance.</gray>"),
                    Text.mm("<dark_gray>One only. There is no second egg.</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.DRAGON_OMELET, PersistentDataType.BYTE, (byte) 1);
            omelet.setItemMeta(meta);
        }
        return omelet;
    }

    public static boolean isOmelet(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.DRAGON_OMELET, PersistentDataType.BYTE);
    }

    /** Recognizes old items so cleanup code can delete them; no new mace can be created. */
    public static boolean isDraconicMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.DRACONIC_MACE, PersistentDataType.BYTE);
    }

}
