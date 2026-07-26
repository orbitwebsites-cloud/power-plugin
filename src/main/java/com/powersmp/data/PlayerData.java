package com.powersmp.data;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Everything about a player that has to survive a restart: current stance, which powers they have
 * unlocked, kill counters used for unlock gating, and their spear's upgrade tier.
 */
public class PlayerData {

    private final UUID uuid;
    private String stance = "NONE";
    private final Set<String> unlocked = new LinkedHashSet<>();
    private int kills;
    private int spearKills;
    private int spearTier = 3;
    private String lastKnownName = "";

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public String stance() {
        return stance;
    }

    public void stance(String stance) {
        this.stance = stance;
    }

    public Set<String> unlocked() {
        return unlocked;
    }

    public boolean hasUnlocked(String powerId) {
        return unlocked.contains(powerId);
    }

    /** @return true if this call actually unlocked something new. */
    public boolean unlock(String powerId) {
        return unlocked.add(powerId);
    }

    public boolean revoke(String powerId) {
        return unlocked.remove(powerId);
    }

    public int kills() {
        return kills;
    }

    public void kills(int kills) {
        this.kills = Math.max(0, kills);
    }

    public int spearKills() {
        return spearKills;
    }

    public void spearKills(int spearKills) {
        this.spearKills = Math.max(0, spearKills);
    }

    public int spearTier() {
        return spearTier;
    }

    public void spearTier(int spearTier) {
        this.spearTier = spearTier;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    public void lastKnownName(String lastKnownName) {
        this.lastKnownName = lastKnownName == null ? "" : lastKnownName;
    }
}
