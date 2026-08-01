package com.powersmp.util;

import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Registry-backed enchantment lookup for custom signature weapons.
 */
public final class Enchants {

    public static final Enchantment RIPTIDE = resolve("riptide");
    public static final Enchantment LUNGE = resolve("lunge");
    public static final Enchantment SWEEPING_EDGE = resolve("sweeping_edge");
    public static final Enchantment SHARPNESS = resolve("sharpness");
    public static final Enchantment VANISHING_CURSE = resolve("vanishing_curse");

    private Enchants() {
    }

    private static Enchantment resolve(String key) {
        try {
            return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Adds Curse of Vanishing to a player-bound item, so if a bug or a plugin conflict ever lets it
     * slip past the drop/death guards, vanilla itself destroys it instead of leaving a duplicate
     * lootable on the ground. The item's own reissue-on-respawn logic still hands the owner a fresh
     * one -- their progress lives in player data, not in which physical item exists.
     */
    public static void applyVanishing(ItemMeta meta) {
        if (VANISHING_CURSE != null && meta != null) {
            meta.addEnchant(VANISHING_CURSE, 1, true);
        }
    }

    public static void warnMissing(Logger logger) {
        check(logger, LUNGE, "lunge");
        check(logger, SWEEPING_EDGE, "sweeping_edge");
        check(logger, SHARPNESS, "sharpness");
    }

    private static void check(Logger logger, Enchantment enchantment, String name) {
        if (enchantment == null) {
            logger.warning("Enchantment '" + name + "' is unavailable on this server version; "
                    + "the matching signature weapon will omit it.");
        }
    }
}
