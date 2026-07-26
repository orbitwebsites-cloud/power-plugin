package com.powersmp.progression;

/**
 * Catalogue of every named power, with the kit it belongs to and how it is unlocked.
 *
 * <p>The {@link Gate} matters: {@code ALWAYS} powers are part of the kit from the start,
 * {@code KILLS} powers are the ones open question #2 covers (nobody has decided the real gating, so
 * they honour {@code progression.unlock-all}), and {@code TRIGGER} powers unlock from a specific
 * in-game event -- an advancement, or killing the Wither while holding fungus. {@code TRIGGER}
 * powers deliberately ignore {@code unlock-all}: earning them <em>is</em> the design.
 */
public enum Power {

    // --- Mavricc ---------------------------------------------------------
    STANCE_CHANGE("stance_change", "Stance Change", "mavricc", Gate.ALWAYS),
    MUSHROOM_AFFINITY("mushroom_affinity", "Mushroom Affinity", "mavricc", Gate.ALWAYS),
    MUSHROOM_HUNGER("mushroom_hunger", "Mushroom Hunger", "mavricc", Gate.ALWAYS),
    WITHER_WINGS("wither_wings", "Sporeic Wither Wings", "mavricc", Gate.TRIGGER),
    DIMENSIONAL_ADAPTATION("dimensional_adaptation", "Dimensional Adaptation", "mavricc", Gate.TRIGGER),
    SPORIC_MIND_CONTROL("sporic_mind_control", "Sporic Mind Control", "mavricc", Gate.TRIGGER),
    SPORIC_OF_THE_SEA("sporic_of_the_sea", "Sporic of the Sea", "mavricc", Gate.TRIGGER),
    DRACONIC_EVOLUTION("draconic_evolution", "Draconic Evolution", "mavricc", Gate.TRIGGER),

    // --- arhiahn ---------------------------------------------------------
    THE_WORLD("the_world", "The World", "arhiahn", Gate.KILLS),
    MADE_IN_HEAVEN("made_in_heaven", "Made In Heaven", "arhiahn", Gate.KILLS),
    REQUIEM("requiem", "Requiem", "arhiahn", Gate.KILLS),

    // --- KornFlakis ------------------------------------------------------
    KA_CHOW("ka_chow", "Ka-Chow", "kornflakis", Gate.KILLS),
    OVERDRIVE("overdrive", "Overdrive", "kornflakis", Gate.KILLS),
    SPEAR_MASTER("spear_master", "Spear Master", "kornflakis", Gate.KILLS),

    // --- MonkeyMan4167 ---------------------------------------------------
    FLASH("flash", "Flash", "monkeyman", Gate.KILLS),
    POWER_OF_THE_SUN("power_of_the_sun", "Power of the Sun", "monkeyman", Gate.KILLS),
    MIRAGE("mirage", "Mirage", "monkeyman", Gate.KILLS);

    public enum Gate {
        /** Part of the kit from the moment it is assigned. */
        ALWAYS,
        /** Kill-count gated; honours {@code progression.unlock-all}. */
        KILLS,
        /** Unlocked by a specific in-game trigger, regardless of {@code unlock-all}. */
        TRIGGER
    }

    private final String id;
    private final String displayName;
    private final String kitId;
    private final Gate gate;

    Power(String id, String displayName, String kitId, Gate gate) {
        this.id = id;
        this.displayName = displayName;
        this.kitId = kitId;
        this.gate = gate;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String kitId() {
        return kitId;
    }

    public Gate gate() {
        return gate;
    }

    public static Power byId(String id) {
        for (Power power : values()) {
            if (power.id.equalsIgnoreCase(id)) {
                return power;
            }
        }
        return null;
    }
}
