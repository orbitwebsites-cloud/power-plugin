package com.powersmp.item;

import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * TechKnightGaming's soulbound mace.
 *
 * <p>The kill count lives in the item's PDC and is mirrored in player data, so the weapon can be
 * re-issued at the right power if it is ever lost to the void, lava or an admin {@code /clear}.
 *
 * <p>Levels above a vanilla maximum are applied with {@code ignoreLevelRestriction}, which the
 * server honours -- Density VIII really does scale damage. That is only reachable in
 * {@code LITERAL} mode; see the kit config.
 */
public final class MaceItem {

    private static final String[] NUMERALS =
            {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private MaceItem() {
    }

    public static ItemStack create(UUID owner, int kills, Levels levels, boolean unbreakable) {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(unbreakable);
            meta.getPersistentDataContainer()
                    .set(Keys.SOULBOUND_MACE, PersistentDataType.STRING, owner.toString());
            mace.setItemMeta(meta);
        }
        apply(mace, kills, levels);
        return mace;
    }

    public static boolean isSoulbound(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.SOULBOUND_MACE, PersistentDataType.STRING);
    }

    /** @return the owner's UUID, or null if this is not a soulbound mace. */
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

    public static int killsOf(ItemStack item) {
        if (!isSoulbound(item)) {
            return 0;
        }
        Integer kills = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.MACE_KILLS, PersistentDataType.INTEGER);
        return kills == null ? 0 : kills;
    }

    /** Rewrites the enchantments, name and lore to match {@code kills}. */
    public static void apply(ItemStack mace, int kills, Levels levels) {
        if (mace == null || mace.getType() != Material.MACE) {
            return;
        }
        ItemMeta meta = mace.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(Keys.MACE_KILLS, PersistentDataType.INTEGER, kills);

        setLevel(meta, Enchants.DENSITY, levels.density());
        setLevel(meta, Enchants.BREACH, levels.breach());
        setLevel(meta, Enchants.WIND_BURST, levels.windBurst());

        meta.displayName(Text.mm("<gradient:#ff5f5f:#ffb347><bold>Massacre</bold></gradient>"));

        List<Component> lore = new ArrayList<>();
        lore.add(Text.mm("<dark_gray>" + kills + " kill" + (kills == 1 ? "" : "s") + "</dark_gray>"));
        if (levels.density() > 0) {
            lore.add(Text.mm("<gray>Density " + numeral(levels.density()) + "</gray>"));
        }
        if (levels.breach() > 0) {
            lore.add(Text.mm("<gray>Breach " + numeral(levels.breach()) + "</gray>"));
        }
        if (levels.windBurst() > 0) {
            lore.add(Text.mm("<gray>Wind Burst " + numeral(levels.windBurst()) + "</gray>"));
        }
        lore.add(Text.mm("<dark_purple>Bound -- kept on death.</dark_purple>"));
        meta.lore(lore);

        mace.setItemMeta(meta);
    }

    private static void setLevel(ItemMeta meta, Enchantment enchantment, int level) {
        if (enchantment == null) {
            return;
        }
        if (meta.hasEnchant(enchantment)) {
            meta.removeEnchant(enchantment);
        }
        if (level > 0) {
            // ignoreLevelRestriction: LITERAL mode intentionally exceeds the vanilla maximum.
            meta.addEnchant(enchantment, level, true);
        }
    }

    public static String numeral(int level) {
        return level > 0 && level < NUMERALS.length ? NUMERALS[level] : String.valueOf(level);
    }

    /** The three mace enchantment levels for a given kill count. */
    public record Levels(int density, int breach, int windBurst) {
    }
}
