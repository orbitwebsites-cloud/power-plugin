package com.powersmp.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Everything about a player that has to survive a restart: current stance, which powers they have
 * unlocked, kill counters used for unlock gating, and their spear's upgrade tier.
 */
public class PlayerData {

    private final UUID uuid;
    private String stance = "NONE";
    private final Set<String> unlocked = new LinkedHashSet<>();
    /** Admin overrides that keep a normally automatic power disabled until it is granted again. */
    private final Set<String> revoked = new LinkedHashSet<>();
    /** Whole kits explicitly added with /powersmp grant; normal IGN assignments remain separate. */
    private final Set<String> grantedKits = new LinkedHashSet<>();
    private int kills;
    private int spearKills;
    private int spearTier = 5;
    private int maceKills;
    /** Doman: player kills made with the Bloodlust Sword. */
    private int bloodlustKills;
    /** Idle Death Gamble: percentage chance carried into the next Jackpot roll. */
    private int jackpotChance = 14;
    /** Idle Death Gamble: whether the next Jackpot roll uses Fever's 50% chance. */
    private boolean jackpotFeverArmed;
    /** Draconic Evolution: the omelet has been eaten, so all three stances run at once. */
    private boolean stanceConsolidated;
    /** The omelet has been handed out once; it is not re-issued after being eaten. */
    private boolean omeletGranted;
    private String lastKnownName = "";
    /** Which client action fires this player's primary ability. See {@code AbilityTrigger}. */
    private String abilityTrigger = "SNEAK_RIGHT_CLICK";
    /**
     * Player-chosen ability id to fire on that trigger, overriding the kit's own
     * {@code primaryAbilityId()}. Empty means "use the kit default".
     */
    private String primaryAbility = "";
    /** Trigger enum name -> ability id, allowing every gesture to fire a different ability. */
    private final Map<String, String> abilityBindings = new HashMap<>();
    /**
     * Serialised location to send someone home to if the Illusory Realm loses track of them --
     * a crash or a forced shutdown mid-domain must not strand anyone in the arena world. Cleared
     * the moment they are released normally.
     */
    private String realmReturn = "";
    /** ability id -> epoch millis, for cooldowns long enough that a restart must not clear them. */
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** Player-chosen Restock kit. Empty means fall back to the server default in kits.yml. */
    private final List<ItemStack> restockLoadout = new ArrayList<>();
    /** Shadows Technique: private inventory that survives death and restarts. */
    private final List<ItemStack> shadowStorage = new ArrayList<>();
    /** Whether the owner has personally defeated the untamed Mahoraga ritual. */
    private boolean mahoragaTamed;
    /** ThePoultryMan10: lifetime uncancelled damage counted toward armor adaptation. */
    private double adaptationDamage;

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

    public Set<String> revoked() {
        return revoked;
    }

    public Set<String> grantedKits() {
        return grantedKits;
    }

    public boolean isRevoked(String powerId) {
        return revoked.contains(powerId);
    }

    /** @return true if this call actually unlocked something new. */
    public boolean unlock(String powerId) {
        return unlocked.add(powerId);
    }

    public boolean revoke(String powerId) {
        boolean changed = unlocked.remove(powerId);
        return revoked.add(powerId) || changed;
    }

    public boolean grant(String powerId) {
        boolean changed = revoked.remove(powerId);
        return unlocked.add(powerId) || changed;
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

    public int maceKills() {
        return maceKills;
    }

    public void maceKills(int maceKills) {
        this.maceKills = Math.max(0, maceKills);
    }

    public int bloodlustKills() {
        return bloodlustKills;
    }

    public void bloodlustKills(int bloodlustKills) {
        this.bloodlustKills = Math.max(0, bloodlustKills);
    }

    public int jackpotChance() {
        return jackpotChance;
    }

    public void jackpotChance(int jackpotChance) {
        // Rising Odds intentionally has no percentage cap.
        this.jackpotChance = Math.max(1, jackpotChance);
    }

    public boolean jackpotFeverArmed() {
        return jackpotFeverArmed;
    }

    public void jackpotFeverArmed(boolean jackpotFeverArmed) {
        this.jackpotFeverArmed = jackpotFeverArmed;
    }

    public Map<String, Long> cooldowns() {
        return cooldowns;
    }

    public List<ItemStack> restockLoadout() {
        return restockLoadout;
    }

    public List<ItemStack> shadowStorage() {
        return shadowStorage;
    }

    public boolean mahoragaTamed() {
        return mahoragaTamed;
    }

    public void mahoragaTamed(boolean mahoragaTamed) {
        this.mahoragaTamed = mahoragaTamed;
    }

    public double adaptationDamage() {
        return adaptationDamage;
    }

    public void adaptationDamage(double adaptationDamage) {
        this.adaptationDamage = Math.max(0.0d, adaptationDamage);
    }

    public boolean stanceConsolidated() {
        return stanceConsolidated;
    }

    public void stanceConsolidated(boolean stanceConsolidated) {
        this.stanceConsolidated = stanceConsolidated;
    }

    public boolean omeletGranted() {
        return omeletGranted;
    }

    public void omeletGranted(boolean omeletGranted) {
        this.omeletGranted = omeletGranted;
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    public void lastKnownName(String lastKnownName) {
        this.lastKnownName = lastKnownName == null ? "" : lastKnownName;
    }

    public String abilityTrigger() {
        return abilityTrigger;
    }

    public void abilityTrigger(String abilityTrigger) {
        this.abilityTrigger = abilityTrigger == null || abilityTrigger.isBlank()
                ? "SNEAK_RIGHT_CLICK" : abilityTrigger;
    }

    public String primaryAbility() {
        return primaryAbility;
    }

    public void primaryAbility(String primaryAbility) {
        this.primaryAbility = primaryAbility == null ? "" : primaryAbility;
    }

    public Map<String, String> abilityBindings() {
        return abilityBindings;
    }

    public String realmReturn() {
        return realmReturn;
    }

    public void realmReturn(String realmReturn) {
        this.realmReturn = realmReturn == null ? "" : realmReturn;
    }
}
