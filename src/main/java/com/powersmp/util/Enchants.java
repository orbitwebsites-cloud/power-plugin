package com.powersmp.util;

import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

/**
 * Mace enchantment lookup.
 *
 * <p>Resolved through the registry rather than as static constants, for the same reason
 * {@link Attributes} does: enchantments became registry-backed in 1.20.5, and Density, Breach and
 * Wind Burst only exist from 1.21. A missing one degrades to a logged warning instead of a
 * link error at class load.
 */
public final class Enchants {

    public static final Enchantment DENSITY = resolve("density");
    public static final Enchantment BREACH = resolve("breach");
    public static final Enchantment WIND_BURST = resolve("wind_burst");

    private Enchants() {
    }

    private static Enchantment resolve(String key) {
        try {
            return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void warnMissing(Logger logger) {
        check(logger, DENSITY, "density");
        check(logger, BREACH, "breach");
        check(logger, WIND_BURST, "wind_burst");
    }

    private static void check(Logger logger, Enchantment enchantment, String name) {
        if (enchantment == null) {
            logger.warning("Enchantment '" + name + "' is unavailable on this server version; "
                    + "the mace will skip that rung of its ladder.");
        }
    }
}
