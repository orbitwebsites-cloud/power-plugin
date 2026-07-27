package com.powersmp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of the {@link NamespacedKey}s this plugin owns.
 *
 * <p>Attribute modifiers are keyed rather than random-UUID'd (1.21+ API) so that re-applying a
 * stance is idempotent: we look the old modifier up by key and replace it instead of stacking.
 */
public final class Keys {

    private Keys() {
    }

    // Attribute modifier keys.
    public static NamespacedKey STANCE_ARMOR;
    public static NamespacedKey STANCE_KNOCKBACK;
    public static NamespacedKey STANCE_BLOCK_REACH;
    public static NamespacedKey STANCE_ENTITY_REACH;
    public static NamespacedKey STANCE_ATTACK_SPEED;
    public static NamespacedKey ADAPTATION_SCALE;
    public static NamespacedKey ADAPTATION_HEALTH;
    public static NamespacedKey MIH_ATTACK_SPEED;
    public static NamespacedKey TIDAL_ATTACK_SPEED;

    // PersistentDataContainer keys.
    public static NamespacedKey FOOD_STAMP;
    public static NamespacedKey SPEAR_TIER;
    public static NamespacedKey SPEAR_OWNER;
    public static NamespacedKey BOUND_ELYTRA;
    public static NamespacedKey MIRAGE_CLONE;
    public static NamespacedKey SOULBOUND_MACE;
    public static NamespacedKey MACE_KILLS;
    public static NamespacedKey DRAGON_OMELET;
    public static NamespacedKey DRACONIC_MACE;

    public static void init(Plugin plugin) {
        STANCE_ARMOR = new NamespacedKey(plugin, "stance_armor");
        STANCE_KNOCKBACK = new NamespacedKey(plugin, "stance_knockback");
        STANCE_BLOCK_REACH = new NamespacedKey(plugin, "stance_block_reach");
        STANCE_ENTITY_REACH = new NamespacedKey(plugin, "stance_entity_reach");
        STANCE_ATTACK_SPEED = new NamespacedKey(plugin, "stance_attack_speed");
        ADAPTATION_SCALE = new NamespacedKey(plugin, "adaptation_scale");
        ADAPTATION_HEALTH = new NamespacedKey(plugin, "adaptation_health");
        MIH_ATTACK_SPEED = new NamespacedKey(plugin, "mih_attack_speed");
        TIDAL_ATTACK_SPEED = new NamespacedKey(plugin, "tidal_attack_speed");

        FOOD_STAMP = new NamespacedKey(plugin, "food_stamp");
        SPEAR_TIER = new NamespacedKey(plugin, "spear_tier");
        SPEAR_OWNER = new NamespacedKey(plugin, "spear_owner");
        BOUND_ELYTRA = new NamespacedKey(plugin, "bound_elytra");
        MIRAGE_CLONE = new NamespacedKey(plugin, "mirage_clone");
        SOULBOUND_MACE = new NamespacedKey(plugin, "soulbound_mace");
        MACE_KILLS = new NamespacedKey(plugin, "mace_kills");
        DRAGON_OMELET = new NamespacedKey(plugin, "dragon_omelet");
        DRACONIC_MACE = new NamespacedKey(plugin, "draconic_mace");
    }
}
