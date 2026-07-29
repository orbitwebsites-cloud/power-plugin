package com.powersmp.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

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
 *
 * <p>Reference-counted rather than a plain on/off flag: multiple kits call this on the same player
 * (a grapple pulling someone who is also mid-dash, TechKnightGaming's Earthbreaker landing on someone
 * being reeled in, etc.), and a naive flag lets whichever ability finishes first restore original
 * flight state out from under an ability that is still mid-burst -- the second one then gets snapped
 * back by vanilla's own check right as it is happening. Counting how many callers are still inside
 * their window means the real state is only restored once every one of them has called {@link #end}.
 */
public final class MovementExemption {

    private static final Map<UUID, Integer> depth = new ConcurrentHashMap<>();
    private static final Map<UUID, boolean[]> saved = new ConcurrentHashMap<>();

    private MovementExemption() {
    }

    /** Starts (or extends) an exemption window. Safe to call every tick while a burst is ongoing. */
    public static void begin(Player player) {
        UUID id = player.getUniqueId();
        int count = depth.merge(id, 1, Integer::sum);
        if (count == 1) {
            saved.put(id, new boolean[]{player.getAllowFlight(), player.isFlying()});
            PersistentDataContainer data = player.getPersistentDataContainer();
            data.set(Keys.MOVEMENT_EXEMPT, PersistentDataType.BYTE, (byte) 1);
            data.set(Keys.MOVEMENT_PREVIOUS_ALLOW_FLIGHT, PersistentDataType.BYTE,
                    player.getAllowFlight() ? (byte) 1 : (byte) 0);
            data.set(Keys.MOVEMENT_PREVIOUS_FLYING, PersistentDataType.BYTE,
                    player.isFlying() ? (byte) 1 : (byte) 0);
        }
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        if (!player.isFlying()) {
            player.setFlying(true);
        }
    }

    /**
     * Ends one exemption window. Only restores the player's real flight state once every {@link
     * #begin} on this player has a matching {@code end} -- an unmatched extra {@code end} (a bug
     * elsewhere) is clamped at zero rather than going negative and leaving the count permanently
     * confused.
     */
    public static void end(Player player) {
        UUID id = player.getUniqueId();
        int remaining = depth.merge(id, -1, Integer::sum);
        if (remaining > 0) {
            return;
        }
        depth.remove(id);
        if (!player.isOnline()) {
            // The flight flags are player data and survive a disconnect. Keep the persisted
            // restoration marker so handleJoin() can repair them before any kit is reapplied.
            return;
        }
        restore(player);
    }

    /**
     * Restores the exact flight flags saved by the first {@link #begin} call. The marker lives in
     * the player's PDC, so this also repairs an exemption interrupted by a crash or server restart.
     */
    public static void restore(Player player) {
        UUID id = player.getUniqueId();
        depth.remove(id);
        boolean[] cached = saved.remove(id);
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (!data.has(Keys.MOVEMENT_EXEMPT, PersistentDataType.BYTE) && cached == null) {
            return;
        }
        Byte allow = data.get(Keys.MOVEMENT_PREVIOUS_ALLOW_FLIGHT, PersistentDataType.BYTE);
        Byte flying = data.get(Keys.MOVEMENT_PREVIOUS_FLYING, PersistentDataType.BYTE);
        boolean previousAllow = allow == null
                ? cached != null && cached[0]
                : allow != 0;
        boolean previousFlying = flying == null
                ? cached != null && cached[1]
                : flying != 0;
        data.remove(Keys.MOVEMENT_EXEMPT);
        data.remove(Keys.MOVEMENT_PREVIOUS_ALLOW_FLIGHT);
        data.remove(Keys.MOVEMENT_PREVIOUS_FLYING);
        if (!player.isOnline()) {
            return;
        }
        // Bukkit refuses setFlying(true) unless allowFlight is already true, and refuses taking
        // allowFlight away cleanly while the player is still flying. Apply in the safe order for
        // both directions.
        if (previousAllow && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        if (player.isFlying() != previousFlying) {
            player.setFlying(previousFlying);
        }
        if (!previousAllow && player.getAllowFlight()) {
            player.setAllowFlight(previousAllow);
        }
    }
}
