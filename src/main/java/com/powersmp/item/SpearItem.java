package com.powersmp.item;

import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * KornFlakis's spear.
 *
 * <p>1.21.1 has no vanilla spear -- the Trial Chambers weapon is the Mace, and there is no Lunge
 * enchantment at all -- so this is a Trident carrying a tier in its
 * {@code PersistentDataContainer}. The tier is stored on the item rather than only on the player so
 * the weapon keeps its upgrades if it is dropped and picked back up, which is what "the spear
 * upgrades" implies. Player data mirrors it as the source of truth for re-issuing a lost spear.
 */
public final class SpearItem {

    public static final int MIN_TIER = 3;
    public static final int MAX_TIER = 5;

    private static final String[] NUMERALS = {"", "I", "II", "III", "IV", "V"};

    private SpearItem() {
    }

    public static ItemStack create(UUID owner, int tier) {
        ItemStack spear = new ItemStack(Material.TRIDENT);
        applyTier(spear, tier);
        ItemMeta meta = spear.getItemMeta();
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer()
                .set(Keys.SPEAR_OWNER, PersistentDataType.STRING, owner.toString());
        spear.setItemMeta(meta);
        return spear;
    }

    public static boolean isSpear(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(Keys.SPEAR_TIER, PersistentDataType.INTEGER);
    }

    public static int tierOf(ItemStack item) {
        if (!isSpear(item)) {
            return 0;
        }
        Integer tier = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.SPEAR_TIER, PersistentDataType.INTEGER);
        return tier == null ? MIN_TIER : clamp(tier);
    }

    /** Writes the tier and refreshes the name and lore to match. */
    public static void applyTier(ItemStack item, int tier) {
        if (item == null || item.getType() != Material.TRIDENT) {
            return;
        }
        int clamped = clamp(tier);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(Keys.SPEAR_TIER, PersistentDataType.INTEGER, clamped);
        meta.displayName(Text.mm("<gold>Spear of Momentum</gold> <gray>(Lunge "
                + NUMERALS[clamped] + ")</gray>"));
        meta.lore(List.of(
                Text.mm("<dark_gray>Lunge " + NUMERALS[clamped] + "</dark_gray>"),
                Text.mm("<gray>On hit: yanks the target in and stuns it.</gray>"),
                Text.mm("<dark_gray>Upgrades with kills.</dark_gray>")));
        item.setItemMeta(meta);
    }

    public static String numeral(int tier) {
        return NUMERALS[clamp(tier)];
    }

    private static int clamp(int tier) {
        return Math.max(MIN_TIER, Math.min(MAX_TIER, tier));
    }
}
