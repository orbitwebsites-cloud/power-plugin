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
 * <p>Genuinely a trident ({@code Material.TRIDENT}) -- unlike xCR1T1Cx's Spear of Momentum, which
 * used to share this same material back when Minecraft had no vanilla spear item to build it from.
 * That stopped being true in 1.21.11 (Mounts of Mayhem added a real {@code Material.SPEAR}), so the
 * two are now on entirely different item types; {@link #isBoundTrident} and {@link SpearItem#isSpear}
 * still check independent PDC keys regardless, since nothing about that guarantee should depend on
 * which materials happen to be in use.
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
