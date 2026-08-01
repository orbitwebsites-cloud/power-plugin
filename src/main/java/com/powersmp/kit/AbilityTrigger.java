package com.powersmp.kit;

/**
 * Every client action a server plugin can see and map to an activated ability.
 *
 * <p>There is no way to detect a literal key like "G" from the server -- Minecraft never sends the
 * physical key, only the action it is bound to (sneak, attack, use-item, swap-hands, and so on).
 * What {@code /power keybinds} offers is an independent ability mapping for each action. To get a
 * specific physical key, rebind that vanilla action to it in your own Minecraft
 * Controls menu -- e.g. set {@code SWAP_HANDS} here, then rebind "Swap Item In Hand" from F to G in
 * Controls, and pressing G fires your ability. The mapping is entirely client-side and needs no
 * server support.
 */
public enum AbilityTrigger {

    SNEAK_RIGHT_CLICK("Sneak + Right Click", "Hold shift and right-click. The default."),
    RIGHT_CLICK("Right Click", "Just right-click, no sneaking. Takes over right-click entirely while "
            + "you have a kit -- you will not be able to eat, block, or place blocks with a normal "
            + "right-click. Fine for kits that do not hold interactive items."),
    SNEAK_LEFT_CLICK("Sneak + Left Click", "Hold shift and left-click (swing at air or a block)."),
    LEFT_CLICK("Left Click", "Just left-click, no sneaking. Only swings at air or blocks trigger this "
            + "-- hitting a mob or player is unaffected, so it is safe in combat, but every idle "
            + "swing elsewhere now fires your ability."),
    SWAP_HANDS("Swap Hands (F)", "Press the Swap Item In Hand key (F by default). Rebind that key in "
            + "Controls to use anything you like -- G, whatever -- as your real trigger."),
    SNEAK_SWAP_HANDS("Sneak + Swap Hands", "Hold shift and press Swap Item In Hand (F by default).");

    private final String label;
    private final String description;

    AbilityTrigger(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static AbilityTrigger fromName(String name, AbilityTrigger fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return AbilityTrigger.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
