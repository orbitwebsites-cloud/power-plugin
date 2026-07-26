package com.powersmp.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts consecutive hits an attacker lands on one target inside a rolling time window.
 *
 * <p>Switching targets or letting the window lapse restarts the count at 1. Generic on purpose --
 * Ka-Chow is the first user, but nothing here mentions lightning.
 */
public class ComboTracker {

    private final Map<UUID, Combo> combos = new ConcurrentHashMap<>();
    private volatile long windowMillis;

    public ComboTracker(double windowSeconds) {
        this.windowMillis = (long) (windowSeconds * 1000.0d);
    }

    public void windowSeconds(double windowSeconds) {
        this.windowMillis = (long) (windowSeconds * 1000.0d);
    }

    /**
     * Records a hit.
     *
     * @return the combo count including this hit (1 if the combo just restarted).
     */
    public int hit(UUID attacker, UUID target) {
        long now = System.currentTimeMillis();
        Combo combo = combos.get(attacker);
        if (combo == null || !combo.target.equals(target) || now - combo.lastHit > windowMillis) {
            combo = new Combo(target, now);
            combos.put(attacker, combo);
            return combo.count;
        }
        combo.count++;
        combo.lastHit = now;
        return combo.count;
    }

    /** Current count, or 0 if there is no live combo. */
    public int current(UUID attacker) {
        Combo combo = combos.get(attacker);
        if (combo == null || System.currentTimeMillis() - combo.lastHit > windowMillis) {
            return 0;
        }
        return combo.count;
    }

    public void reset(UUID attacker) {
        combos.remove(attacker);
    }

    public void clear() {
        combos.clear();
    }

    private static final class Combo {
        private final UUID target;
        private int count = 1;
        private long lastHit;

        private Combo(UUID target, long lastHit) {
            this.target = target;
            this.lastHit = lastHit;
        }
    }
}
