package com.powersmp.util;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Vanilla's own server-side sanity check ("Player moved wrongly!" / "moved too quickly!") has no
 * idea a grapple, dash, launch, pull or knockup is a deliberate server-driven burst -- it just sees
 * a position report that does not match expected physics, and silently snaps the player back to the
 * last accepted position. That is not a cosmetic glitch: it undoes the ability's whole effect while
 * looking, from the player's side, like the power simply did not work.
 *
 * <p>There is no dedicated Paper event or flag to say "let this one through" -- the closest lever
 * exposed through public API is the same one creative/spectator flight already relies on: while
 * {@code allowFlight} and {@code flying} are both true, the server relaxes the ground-speed check
 * it would otherwise apply. Toggling both for the duration of a burst, then restoring whatever the
 * player actually had before, gets a real dash-style movement through without granting them
 * standing flight.
 */
public final class MovementExemption {

    private static final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, boolean[]> saved = new ConcurrentHashMap<>();

    private MovementExemption() {
    }

    /** Starts (or extends) an exemption window. Safe to call every tick while a burst is ongoing. */
    public static void begin(Player player) {
        UUID id = player.getUniqueId();
        if (active.add(id)) {
            saved.put(id, new boolean[]{player.getAllowFlight(), player.isFlying()});
        }
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        if (!player.isFlying()) {
            player.setFlying(true);
        }
    }

    /** Ends the exemption and restores whatever flight state the player actually had. */
    public static void end(Player player) {
        UUID id = player.getUniqueId();
        boolean[] previous = saved.remove(id);
        active.remove(id);
        if (previous == null || !player.isOnline()) {
            return;
        }
        if (player.isFlying() != previous[1]) {
            player.setFlying(previous[1]);
        }
        if (player.getAllowFlight() != previous[0]) {
            player.setAllowFlight(previous[0]);
        }
    }
}
