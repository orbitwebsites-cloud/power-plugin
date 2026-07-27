package com.powersmp.item;

import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** The two items Draconic Evolution hands out: the omelet, and the weakened mace. */
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

    /**
     * The weakened mace: keeps Breach, loses the slam.
     *
     * <p>Breach is a real enchantment so it is simply applied. "Loses slam" has no enchantment or
     * flag behind it -- the fall-distance damage bonus is baked into vanilla's mace attack -- so it
     * is undone at damage time in {@code MavriccKit}. The tag here is what marks a mace as the one
     * that should have its slam stripped.
     */
    public static ItemStack mace(int breachLevel, boolean unbreakable) {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<gradient:#7b2cbf:#c77dff><bold>Draconic Mace</bold></gradient>"));
            meta.lore(List.of(
                    Text.mm("<gray>Breach " + numeral(breachLevel) + "</gray>"),
                    Text.mm("<dark_gray>Cannot slam -- falling adds nothing.</dark_gray>")));
            meta.setUnbreakable(unbreakable);
            if (Enchants.BREACH != null && breachLevel > 0) {
                meta.addEnchant(Enchants.BREACH, breachLevel, true);
            }
            meta.getPersistentDataContainer().set(Keys.DRACONIC_MACE, PersistentDataType.BYTE, (byte) 1);
            mace.setItemMeta(meta);
        }
        return mace;
    }

    public static boolean isDraconicMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.DRACONIC_MACE, PersistentDataType.BYTE);
    }

    /**
     * Vanilla's mace smash bonus for a given fall distance: +4 per block for the first three, +2 per
     * block for the next five, +1 per block after that. Subtracting exactly this leaves Strength,
     * Breach and everything else intact, which capping the total damage would not.
     */
    public static double slamBonus(double fallDistance) {
        if (fallDistance <= 0.0d) {
            return 0.0d;
        }
        if (fallDistance <= 3.0d) {
            return fallDistance * 4.0d;
        }
        if (fallDistance <= 8.0d) {
            return 12.0d + (fallDistance - 3.0d) * 2.0d;
        }
        return 22.0d + (fallDistance - 8.0d);
    }

    private static String numeral(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
