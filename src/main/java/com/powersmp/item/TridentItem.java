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

/**
 * ItzMeTentx's trident: Riptide III baked in from the start, bound the same way every other
 * signature weapon in this plugin is -- unbreakable, Curse of Vanishing, re-issued if lost.
 *
 * <p>Distinguished from xCR1T1Cx's Spear of Momentum by its own PDC key rather than by material --
 * both are built on {@code Material.TRIDENT}, since that is the only vanilla item shaped like either
 * weapon, but {@link #isBoundTrident} and {@link SpearItem#isSpear} check different keys, so neither
 * kit's logic can mistake the other's item for its own.
 */
public final class TridentItem {

    public static final int RIPTIDE_LEVEL = 3;

    private TridentItem() {
    }

    public static ItemStack create(UUID owner) {
        ItemStack trident = new ItemStack(Material.TRIDENT);
        ItemMeta meta = trident.getItemMeta();
        meta.setUnbreakable(true);
        if (Enchants.RIPTIDE != null) {
            meta.addEnchant(Enchants.RIPTIDE, RIPTIDE_LEVEL, true);
        }
        meta.displayName(Text.mm("<aqua><bold>Trident of the Tide</bold></aqua>"));
        meta.lore(List.of(
                Text.mm("<dark_gray>Riptide III</dark_gray>"),
                Text.mm("<gray>Fires a riptide launch even on dry land.</gray>")));
        meta.getPersistentDataContainer()
                .set(Keys.BOUND_TRIDENT, PersistentDataType.STRING, owner.toString());
        Enchants.applyVanishing(meta);
        trident.setItemMeta(meta);
        return trident;
    }

    public static boolean isBoundTrident(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(Keys.BOUND_TRIDENT, PersistentDataType.STRING);
    }
}
