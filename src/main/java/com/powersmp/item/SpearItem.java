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
 * <p>Built on the real {@code Material.SPEAR} -- Minecraft's Mounts of Mayhem update added an actual
 * vanilla spear in exactly 1.21.11, the version this whole plugin targets, so the earlier "no vanilla
 * spear exists" workaround (a re-skinned Trident) is gone. The tier is still entirely our own,
 * tracked in the item's {@code PersistentDataContainer} rather than only on the player, so the weapon
 * keeps its upgrades if it is dropped and picked back up. Player data mirrors it as the source of
 * truth for re-issuing a lost spear.
 *
 * <p><b>Naming coincidence worth knowing about:</b> vanilla now has its own spear-exclusive
 * enchantment also called Lunge (max level III, launches the wielder forward on a jab attack). This
 * kit's "Lunge I-V" is unrelated flavor text predating that enchantment -- it names a custom
 * pull-the-target-in-and-stun effect, not vanilla's self-launch, and vanilla Lunge is deliberately
 * never applied to this item so the two do not show up stacked in the tooltip.
 */
public final class SpearItem {

    public static final int MIN_TIER = 3;
    public static final int MAX_TIER = 5;

    private static final String[] NUMERALS = {"", "I", "II", "III", "IV", "V"};

    private SpearItem() {
    }

    public static ItemStack create(UUID owner, int tier) {
        ItemStack spear = new ItemStack(Material.SPEAR);
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
        if (item == null || item.getType() != Material.SPEAR) {
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
        if (item == null || item.getType() != Material.SPEAR) {
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
