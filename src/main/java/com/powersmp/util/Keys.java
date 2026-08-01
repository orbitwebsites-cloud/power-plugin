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
    public static NamespacedKey TIDAL_COMBO_ATTACK_SPEED;
    public static NamespacedKey SCAR_BONUS_HEALTH;
    public static NamespacedKey REALM_BONUS_HEALTH;
    public static NamespacedKey TECH_FORTIFY_KNOCKBACK;

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
    public static NamespacedKey WEB_SHOOTER;
    public static NamespacedKey SCAR_MACE;
    public static NamespacedKey CUTLASS_OWNER;
    public static NamespacedKey TITAN_BLADE_OWNER;
    public static NamespacedKey TITAN_BLADE_TIER;
    public static NamespacedKey VULCANS_CROSSBOW_OWNER;
    public static NamespacedKey BLOODLUST_OWNER;
    public static NamespacedKey BLOODLUST_KILLS;
    public static NamespacedKey SHADOW_MARK;
    public static NamespacedKey SHADOW_ORIGINAL_ID;
    public static NamespacedKey SHADOW_OWNER;
    public static NamespacedKey BOUND_TRIDENT;
    public static NamespacedKey ENERGY_CORE;
    public static NamespacedKey MOVEMENT_EXEMPT;
    public static NamespacedKey MOVEMENT_PREVIOUS_ALLOW_FLIGHT;
    public static NamespacedKey MOVEMENT_PREVIOUS_FLYING;

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
        TIDAL_COMBO_ATTACK_SPEED = new NamespacedKey(plugin, "tidal_combo_attack_speed");
        SCAR_BONUS_HEALTH = new NamespacedKey(plugin, "scar_bonus_health");
        REALM_BONUS_HEALTH = new NamespacedKey(plugin, "realm_bonus_health");
        TECH_FORTIFY_KNOCKBACK = new NamespacedKey(plugin, "tech_fortify_knockback");

        FOOD_STAMP = new NamespacedKey(plugin, "food_stamp");
        SPEAR_TIER = new NamespacedKey(plugin, "spear_tier");
        SPEAR_OWNER = new NamespacedKey(plugin, "spear_owner");
        BOUND_ELYTRA = new NamespacedKey(plugin, "bound_elytra");
        MIRAGE_CLONE = new NamespacedKey(plugin, "mirage_clone");
        SOULBOUND_MACE = new NamespacedKey(plugin, "soulbound_mace");
        MACE_KILLS = new NamespacedKey(plugin, "mace_kills");
        DRAGON_OMELET = new NamespacedKey(plugin, "dragon_omelet");
        DRACONIC_MACE = new NamespacedKey(plugin, "draconic_mace");
        WEB_SHOOTER = new NamespacedKey(plugin, "web_shooter");
        SCAR_MACE = new NamespacedKey(plugin, "scar_mace");
        CUTLASS_OWNER = new NamespacedKey(plugin, "cutlass_owner");
        TITAN_BLADE_OWNER = new NamespacedKey(plugin, "titan_blade_owner");
        TITAN_BLADE_TIER = new NamespacedKey(plugin, "titan_blade_tier");
        VULCANS_CROSSBOW_OWNER = new NamespacedKey(plugin, "vulcans_crossbow_owner");
        BLOODLUST_OWNER = new NamespacedKey(plugin, "bloodlust_owner");
        BLOODLUST_KILLS = new NamespacedKey(plugin, "bloodlust_kills");
        SHADOW_MARK = new NamespacedKey(plugin, "shadow_mark");
        SHADOW_ORIGINAL_ID = new NamespacedKey(plugin, "shadow_original_id");
        SHADOW_OWNER = new NamespacedKey(plugin, "shadow_owner");
        BOUND_TRIDENT = new NamespacedKey(plugin, "bound_trident");
        ENERGY_CORE = new NamespacedKey(plugin, "energy_core");
        MOVEMENT_EXEMPT = new NamespacedKey(plugin, "movement_exempt");
        MOVEMENT_PREVIOUS_ALLOW_FLIGHT = new NamespacedKey(plugin, "movement_previous_allow_flight");
        MOVEMENT_PREVIOUS_FLYING = new NamespacedKey(plugin, "movement_previous_flying");
    }
}
