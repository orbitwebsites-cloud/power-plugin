package com.powersmp.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Reimplements vanilla's critical-hit test.
 *
 * <p>Paper exposes {@code EntityDamageEvent#isCritical()} but only on some 1.21 builds, and it does
 * not distinguish a melee crit from an arrow crit. Sporic of the Sea (red) needs "every 3rd crit
 * with an axe" specifically, so the conditions are checked directly here instead.
 */
public final class Crits {

    private Crits() {
    }

    public static boolean isCriticalMelee(Player attacker) {
        if (attacker.getFallDistance() <= 0.0f) {
            return false;
        }
        if (attacker.isSprinting() || attacker.isInsideVehicle() || attacker.isInWater()) {
            return false;
        }
        if (attacker.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            return false;
        }
        try {
            if (attacker.isClimbing()) {
                return false;
            }
        } catch (Throwable ignored) {
            // Older API without isClimbing(); the remaining checks are close enough.
        }
        // A swing that has not fully recharged does not crit.
        return attacker.getAttackCooldown() > 0.9f;
    }
}
