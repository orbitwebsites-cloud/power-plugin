package com.powersmp.util;

import java.util.ArrayList;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * Attribute lookup and modifier plumbing.
 *
 * <p>Attributes are resolved through {@link Registry} rather than referenced as constants on
 * purpose. 1.21.1 keys them as {@code minecraft:generic.max_health}; 1.21.3+ dropped the
 * {@code generic.} prefix and turned {@code Attribute} from an enum into an interface. Looking each
 * one up by both spellings keeps a single jar working across the whole 1.21 line, which is what
 * "Paper 1.21.1+" asks for. A missing attribute degrades to a warning and a no-op rather than a
 * {@code NoSuchFieldError} at class-load time.
 */
public final class Attributes {

    public static final Attribute MAX_HEALTH = resolve("max_health", "generic.max_health");
    public static final Attribute ARMOR = resolve("armor", "generic.armor");
    public static final Attribute KNOCKBACK_RESISTANCE =
            resolve("knockback_resistance", "generic.knockback_resistance");
    public static final Attribute ATTACK_SPEED = resolve("attack_speed", "generic.attack_speed");
    public static final Attribute ATTACK_DAMAGE = resolve("attack_damage", "generic.attack_damage");
    public static final Attribute SCALE = resolve("scale", "generic.scale");
    public static final Attribute BLOCK_INTERACTION_RANGE =
            resolve("block_interaction_range", "player.block_interaction_range");
    public static final Attribute ENTITY_INTERACTION_RANGE =
            resolve("entity_interaction_range", "player.entity_interaction_range");
    public static final Attribute WAYPOINT_TRANSMIT_RANGE =
            resolve("waypoint_transmit_range", "player.waypoint_transmit_range");
    public static final Attribute WAYPOINT_RECEIVE_RANGE =
            resolve("waypoint_receive_range", "player.waypoint_receive_range");

    private Attributes() {
    }

    private static Attribute resolve(String... candidateKeys) {
        for (String key : candidateKeys) {
            try {
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (attribute != null) {
                    return attribute;
                }
            } catch (Throwable ignored) {
                // Registry shape differs between versions; fall through to the next spelling.
            }
        }
        return null;
    }

    /** Logs once per unresolved attribute so a server on an odd version knows what it is missing. */
    public static void warnMissing(Logger logger) {
        check(logger, MAX_HEALTH, "max_health");
        check(logger, ARMOR, "armor");
        check(logger, KNOCKBACK_RESISTANCE, "knockback_resistance");
        check(logger, ATTACK_SPEED, "attack_speed");
        check(logger, ATTACK_DAMAGE, "attack_damage");
        check(logger, SCALE, "scale");
        check(logger, BLOCK_INTERACTION_RANGE, "block_interaction_range");
        check(logger, ENTITY_INTERACTION_RANGE, "entity_interaction_range");
        // Locator Bar attributes were added later in the 1.21 line. They are optional so older
        // 1.21 servers can still load the plugin; Doman's Tracking power logs its own fallback.
    }

    private static void check(Logger logger, Attribute attribute, String name) {
        if (attribute == null) {
            logger.warning("Attribute '" + name + "' is not available on this server version; "
                    + "any kit effect that uses it will be skipped.");
        }
    }

    /**
     * Sets this plugin's modifier for {@code attribute} to {@code amount}, replacing any previous
     * value we set. An amount of 0 removes the modifier entirely.
     */
    public static void set(LivingEntity entity, Attribute attribute, NamespacedKey key, double amount) {
        set(entity, attribute, key, amount, AttributeModifier.Operation.ADD_NUMBER);
    }

    public static void set(LivingEntity entity, Attribute attribute, NamespacedKey key, double amount,
                           AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        removeFrom(instance, key);
        if (amount != 0.0d) {
            instance.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        }
    }

    public static void clear(LivingEntity entity, Attribute attribute, NamespacedKey key) {
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            removeFrom(instance, key);
        }
    }

    private static void removeFrom(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (key.equals(modifier.getKey())) {
                instance.removeModifier(modifier);
            }
        }
    }

    /** Base value plus every modifier, i.e. what the entity actually has right now. */
    public static double valueOf(LivingEntity entity, Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }
}
