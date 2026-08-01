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

    // --- NorthOfNowhere ---------------------------------------------------------
    THE_WORLD("the_world", "The World", "northofnowhere", Gate.KILLS),
    MADE_IN_HEAVEN("made_in_heaven", "Made In Heaven", "northofnowhere", Gate.KILLS),
    REQUIEM("requiem", "Requiem", "northofnowhere", Gate.KILLS),

    // --- KornFlakis ------------------------------------------------------
    KILL_COMMAND("kill_command", "Execution", "kornflakis", Gate.ALWAYS),

    // --- xCR1T1Cx --------------------------------------------------------
    KA_CHOW("ka_chow", "Ka-Chow", "xcr1t1cx", Gate.KILLS),
    OVERDRIVE("overdrive", "Overdrive", "xcr1t1cx", Gate.KILLS),
    SPEAR_MASTER("spear_master", "Spear Master", "xcr1t1cx", Gate.KILLS),

    // --- MonkeyMan4167 ---------------------------------------------------
    FLASH("flash", "Flash", "monkeyman", Gate.KILLS),
    POWER_OF_THE_SUN("power_of_the_sun", "Power of the Sun", "monkeyman", Gate.KILLS),
    MIRAGE("mirage", "Mirage", "monkeyman", Gate.KILLS),

    // --- ItzMeTentx ------------------------------------------------------
    AQUATIC_GRACE("aquatic_grace", "Infinite Breathing & Dolphin's Grace", "itzmetentx", Gate.ALWAYS),
    TIDAL_SPEED("tidal_speed", "Faster Attack Speed", "itzmetentx", Gate.ALWAYS),
    TRIDENT_GOD("trident_god", "Trident God", "itzmetentx", Gate.ALWAYS),

    // --- JJlionjxi -------------------------------------------------------
    // Low / mid / high tier, so these ride the existing kill-gated thresholds.
    WIND_GOD("wind_god", "Wind God", "jjlionjxi", Gate.KILLS),
    FAT_TANK("fat_tank", "Fat Tank", "jjlionjxi", Gate.KILLS),
    GREEDY_HEAL("greedy_heal", "Greedy Heal", "jjlionjxi", Gate.KILLS),

    // --- domanthegamer ---------------------------------------------------
    BLOOD_BOUND("blood_bound", "Blood-Bound Vitality", "domanthegamer", Gate.KILLS),
    TRACKING("tracking", "Tracking", "domanthegamer", Gate.KILLS),
    BLOODLUST("bloodlust", "Bloodlust Sword", "domanthegamer", Gate.KILLS),

    // --- Sparkkkkkkkk ----------------------------------------------------
    GUNPOWDER("gunpowder", "Creeper Harvest", "sparkkkkkkkk", Gate.KILLS),
    EXPLOSION("explosion", "Explosion", "sparkkkkkkkk", Gate.KILLS),
    ATOM_BOMB("atom_bomb", "Atom Bomb", "sparkkkkkkkk", Gate.KILLS),

    // --- Night_Scar3 -----------------------------------------------------
    INFERNAL_VITALITY("infernal_vitality", "Infernal Vitality", "night_scar3", Gate.KILLS),
    SHADOW_BOMB("shadow_bomb", "Shadow Bomb", "night_scar3", Gate.KILLS),
    CUTLASS_MASTER("cutlass_master", "Cutlass Sword", "night_scar3", Gate.KILLS),

    // --- Marb13_ ---------------------------------------------------------
    MINERS_HAVEN("miners_haven", "Miner's Haven", "marb13", Gate.KILLS),
    ENDER_MAGIC("ender_magic", "Ender Magic", "marb13", Gate.KILLS),
    SHADOW_MASTER("shadow_master", "Portal and Shadow Master", "marb13", Gate.KILLS),

    // --- LlamaChas -------------------------------------------------------
    // No tiers were given, so all five are simply part of the kit.
    FLIGHT("flight", "Flight", "llamachas", Gate.ALWAYS),
    HEAT_VISION("heat_vision", "Heat Vision", "llamachas", Gate.ALWAYS),
    XRAY("xray", "X-Ray Vision", "llamachas", Gate.ALWAYS),
    FREEZE_BREATH("freeze_breath", "Freeze Breath", "llamachas", Gate.ALWAYS),
    SUPER_STRENGTH("super_strength", "Super Strength", "llamachas", Gate.ALWAYS),

    // --- Voidwalker -------------------------------------------------------
    // No tiers were given, so all three are simply part of the kit -- same call
    // as LlamaChas. The escalating cooldowns (30s / 90s / 300s) already do the
    // pacing that a kill gate would otherwise be doing.
    SHADOW_STEP("shadow_step", "Shadow Step", "voidwalker", Gate.ALWAYS),
    GRASP_OF_EYLIS("grasp_of_eylis", "Grasp of Eylis", "voidwalker", Gate.ALWAYS),
    ILLUSORY_REALM("illusory_realm", "Illusory Realm", "voidwalker", Gate.ALWAYS),

    // --- disasterflames / Shadows Technique --------------------------------
    DIVINE_DOGS("divine_dogs", "Divine Dogs", "disasterflames", Gate.ALWAYS),
    RABBIT_ESCAPE("rabbit_escape", "Rabbit Escape", "disasterflames", Gate.KILLS),
    SHADOW_STORAGE("shadow_storage", "Cursed Reinforcement & Shadow Storage", "disasterflames", Gate.KILLS),
    MAHORAGA("mahoraga", "Mahoraga", "disasterflames", Gate.KILLS),

    // --- The Ghost ---------------------------------------------------------
    // No tiers were given, so all three are simply part of the kit -- same call as
    // LlamaChas, Voidwalker, and disasterflames.
    POSSESSION("possession", "Possession", "theghost", Gate.ALWAYS),
    SPECTRAL_BODY("spectral_body", "Spectral Body", "theghost", Gate.ALWAYS),
    ASTRAL_FORM("astral_form", "Astral Form", "theghost", Gate.ALWAYS),

    // --- TechKnightGaming -------------------------------------------------
    // All three are ALWAYS: the spec describes them as things he simply has,
    // with no unlock condition mentioned.
    SHIELD_BREAKER("shield_breaker", "Shield Breaker", "techknight", Gate.ALWAYS),
    TITAN_PROTOCOL("titan_protocol", "Titan Protocol", "techknight", Gate.ALWAYS),
    RESTOCK("restock", "Restock", "techknight", Gate.ALWAYS),
    INFINITE_XP("infinite_xp", "Infinite XP Bottles", "techknight", Gate.ALWAYS),
    // Added after the fact: the original three read as stat/utility gear rather than a "power" you
    // press a button for. Earthbreaker is that button and scales with total kills.
    GRAPPLE_SHOT("grapple_shot", "Grapple Shot", "techknight", Gate.ALWAYS),

    // --- Phantom -----------------------------------------------------------
    // No tiers were given, so all three are simply part of the kit -- same call as
    // LlamaChas, Voidwalker, disasterflames, and The Ghost.
    PHANTOM_SPEED("phantom_speed", "Phantom Speed", "phantom", Gate.ALWAYS),
    PHANTOM_CLOAK("phantom_cloak", "Phantom Cloak", "phantom", Gate.ALWAYS),
    PHANTOM_VANISH("phantom_vanish", "Full Vanish", "phantom", Gate.ALWAYS),

    // --- Lucky ---------------------------------------------------------------
    // Just the one power -- there is nothing to tier.
    LUCKY_ROLL("lucky_roll", "Lucky Roll", "lucky", Gate.ALWAYS),

    // --- Ldledeathgamble -----------------------------------------------------
    // Jackpot is the button; Fever and Rising Odds are always-on modifiers to its next roll.
    JACKPOT("jackpot", "Jackpot", "idledeathgamble", Gate.ALWAYS),
    FEVER("fever", "Fever", "idledeathgamble", Gate.ALWAYS),
    RISING_ODDS("rising_odds", "Rising Odds", "idledeathgamble", Gate.ALWAYS),

    // --- Life Stealer --------------------------------------------------------
    // No tiers were given, so all three are simply part of the kit.
    LIFESTEAL("lifesteal", "Lifesteal", "lifestealer", Gate.ALWAYS),
    MARKED_PREY("marked_prey", "Marked Prey", "lifestealer", Gate.ALWAYS),
    DOUBLE_DROPS("double_drops", "Double Drops", "lifestealer", Gate.ALWAYS),

    // --- crazyTNT2cool (The Honored One) -----------------------------------
    // This request was explicitly "full on everything", so the complete kit is available at once.
    SIX_EYES("six_eyes", "Six Eyes", "crazytnt2cool", Gate.ALWAYS),
    INFINITY("infinity", "Infinity", "crazytnt2cool", Gate.ALWAYS),
    CURSED_TECHNIQUE_BLUE(
            "cursed_technique_blue", "Cursed Technique Lapse: Blue", "crazytnt2cool", Gate.ALWAYS),
    CURSED_TECHNIQUE_RED(
            "cursed_technique_red", "Cursed Technique Reversal: Red", "crazytnt2cool", Gate.ALWAYS),
    HOLLOW_PURPLE("hollow_purple", "Hollow Purple", "crazytnt2cool", Gate.ALWAYS),
    LIMITLESS_WARP("limitless_warp", "Limitless Warp", "crazytnt2cool", Gate.ALWAYS),
    REVERSE_CURSED_TECHNIQUE(
            "reverse_cursed_technique", "Reverse Cursed Technique", "crazytnt2cool", Gate.ALWAYS),
    UNLIMITED_VOID("unlimited_void", "Domain Expansion: Unlimited Void", "crazytnt2cool", Gate.ALWAYS),

    // --- I_BL0W_STUFF_UP -------------------------------------------------
    BLAST_PROOF("blast_proof", "Blast Proof", "bites_the_dust", Gate.ALWAYS),
    STICKY_TNT("sticky_tnt", "Sticky TNT", "bites_the_dust", Gate.ALWAYS),
    BITES_THE_DUST("bites_the_dust", "Bites the Dust", "bites_the_dust", Gate.ALWAYS),

    // --- FunnySounds -----------------------------------------------------
    VILLAGE_CHARMER("village_charmer", "Village Charmer", "funnysounds", Gate.ALWAYS);

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
