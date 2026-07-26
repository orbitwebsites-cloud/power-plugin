package com.powersmp.util;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

/**
 * Potion effect helpers.
 *
 * <p>Conditional buffs (stances, daytime, affinity) are applied with a short duration and refreshed
 * on every kit tick, so they lapse on their own the moment the condition stops holding -- no
 * bookkeeping needed to take them away. Genuinely permanent buffs use {@link #applyInfinite}.
 */
public final class Effects {

    /** Comfortably longer than the default 20-tick refresh interval, so there is no flicker. */
    public static final int REFRESH_TICKS = 60;

    private Effects() {
    }

    public static void apply(LivingEntity entity, PotionEffectType type, int durationTicks, int amplifier) {
        if (type == null || amplifier < 0) {
            return;
        }
        entity.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, true, false, true));
    }

    /**
     * Refreshed-every-tick variant used by everything conditional.
     *
     * <p>Bukkit's {@code addPotionEffect} overwrites unconditionally, so a naive refresh would strip
     * a player's own Speed II potion once a second and replace it with our weaker one. Anything
     * already stronger, or running much longer than a refresh cycle, is left alone.
     */
    public static void refresh(LivingEntity entity, PotionEffectType type, int amplifier) {
        if (type == null || amplifier < 0) {
            return;
        }
        PotionEffect existing = entity.getPotionEffect(type);
        if (existing != null
                && (existing.getAmplifier() > amplifier
                || existing.getDuration() == PotionEffect.INFINITE_DURATION
                || existing.getDuration() > REFRESH_TICKS * 4)) {
            return;
        }
        apply(entity, type, REFRESH_TICKS, amplifier);
    }

    public static void applyInfinite(LivingEntity entity, PotionEffectType type, int amplifier) {
        if (type == null || amplifier < 0) {
            return;
        }
        PotionEffect existing = entity.getPotionEffect(type);
        if (existing != null && existing.getAmplifier() == amplifier
                && existing.getDuration() == PotionEffect.INFINITE_DURATION) {
            return;
        }
        entity.addPotionEffect(
                new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, false, true));
    }

    public static void remove(LivingEntity entity, PotionEffectType type) {
        if (type != null && entity.hasPotionEffect(type)) {
            entity.removePotionEffect(type);
        }
    }

    /**
     * Removes an effect only if it looks like one of ours.
     *
     * <p>Kits that revoke a buff on some condition (Overdrive dropping Speed when its owner takes a
     * hit) must not also destroy a potion the player drank themselves. Refreshed effects always have
     * at most {@link #REFRESH_TICKS} left; a real potion has far more, so anything longer is left
     * alone.
     */
    public static void removeIfTransient(LivingEntity entity, PotionEffectType type) {
        if (type == null) {
            return;
        }
        PotionEffect existing = entity.getPotionEffect(type);
        if (existing != null
                && existing.getDuration() != PotionEffect.INFINITE_DURATION
                && existing.getDuration() <= REFRESH_TICKS) {
            entity.removePotionEffect(type);
        }
    }

    public static boolean isHarmful(PotionEffectType type) {
        try {
            return type.getEffectCategory() == PotionEffectTypeCategory.HARMFUL;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
