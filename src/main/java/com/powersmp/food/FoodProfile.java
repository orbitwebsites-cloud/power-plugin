package com.powersmp.food;

/**
 * Replacement food values for one category of item.
 *
 * @param nutrition   hunger points restored
 * @param saturation  saturation restored (the raw component value, not the old modifier)
 * @param maxStackSize new stack limit, or 0 to leave the vanilla limit alone
 */
public record FoodProfile(int nutrition, float saturation, int maxStackSize) {
}
