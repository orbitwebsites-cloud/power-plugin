package com.powersmp.stance;

import java.util.Locale;

public enum Stance {

    RED("Red", "<red>"),
    BLUE("Blue", "<aqua>"),
    GREEN("Green", "<green>"),
    NONE("None", "<gray>");

    private final String displayName;
    private final String colour;

    Stance(String displayName, String colour) {
        this.displayName = displayName;
        this.colour = colour;
    }

    public String displayName() {
        return displayName;
    }

    /** MiniMessage open tag; callers close with the matching tag or just reset. */
    public String colour() {
        return colour;
    }

    public String coloured() {
        return colour + displayName + "</" + colour.substring(1);
    }

    public static Stance parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (Stance stance : values()) {
            if (stance.name().equalsIgnoreCase(raw.trim())) {
                return stance;
            }
        }
        return null;
    }

    public static Stance parseOrNone(String raw) {
        Stance parsed = parse(raw);
        return parsed == null ? NONE : parsed;
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
