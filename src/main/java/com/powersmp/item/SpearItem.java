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
 * xCR1T1Cx's spear.
 *
 * <p>Built on vanilla spear materials (e.g. {@code Material.IRON_SPEAR}) added in Minecraft 1.21.11
 * Mounts of Mayhem. Stores a custom tier in its {@code PersistentDataContainer} to track upgrade
 * level (Lunge I-V) separately from vanilla mechanics. The tier is stored on the item rather than
 * only on the player so the weapon keeps its upgrades if it is dropped and picked back up, which
 * is what "the spear upgrades" implies. Player data mirrors it as the source of truth for
 * re-issuing a lost spear.
 *
 * <p>xCR1T1Cx's custom "Lunge" (pull + stun on hit with cooldown) is entirely separate from
 * vanilla's Lunge enchantment (self-propel on jab). The names happen to collide but the mechanics
 * are unrelated.
 */
public final class SpearItem {

    public static final int MIN_TIER = 3;
    public static final int MAX_TIER = 5;

    private static final String[] NUMERALS = {"", "I", "II", "III", "IV", "V"};

    private SpearItem() {
    }

    public static ItemStack create(UUID owner, int tier) {
        ItemStack spear = new ItemStack(Material.IRON_SPEAR);
        applyTier(spear, tier);
        ItemMeta meta = spear.getItemMeta();
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer()
                .set(Keys.SPEAR_OWNER, PersistentDataType.STRING, owner.toString());
        Enchants.applyVanishing(meta);
        spear.setItemMeta(meta);
        return spear;
    }

    public static boolean isSpear(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SPEAR) {
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
        if (item == null || item.getType() != Material.IRON_SPEAR) {
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
