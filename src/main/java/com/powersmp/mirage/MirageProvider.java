package com.powersmp.mirage;

import org.bukkit.entity.Player;

/**
 * Backend for Mirage's clones.
 *
 * <p>This is an interface rather than a concrete class because the choice of backend is
 * <b>open question #4</b> and has not been made. Plain Paper cannot spawn a fake {@code Player}
 * entity at all -- a real clone (your skin, walking, attackable) needs packet-level entity spoofing
 * via ProtocolLib or an NPC library. {@link ArmorStandMirageProvider} is the zero-dependency
 * fallback and is honestly a downgrade: the decoys are static-ish and obviously not you up close.
 *
 * <p>If the dependency gets approved, add a {@code ProtocolLibMirageProvider} here and switch
 * {@code monkeyman.mirage.provider} in kits.yml. Nothing else has to change.
 */
public interface MirageProvider {

    /** Name shown in {@code /powersmp info}. */
    String name();

    /**
     * Spawns decoys around the owner and cleans them up when the duration elapses.
     *
     * @return how many were actually spawned.
     */
    int spawn(Player owner, int count, double radius, int durationTicks);

    /** Removes every clone this provider still has out. Called on plugin disable. */
    void despawnAll();
}
