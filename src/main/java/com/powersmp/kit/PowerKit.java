package com.powersmp.kit;

import com.powersmp.progression.Power;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * One player's bespoke power set.
 *
 * <p>Kits own the <em>composition</em> of a player's powers and the wiring between them; the actual
 * mechanics live in shared services ({@code CooldownManager}, {@code ComboTracker},
 * {@code FreezeUtil}, {@code StanceManager}) that every kit calls into. A kit that reimplements
 * cooldown bookkeeping is a kit doing it wrong.
 */
public interface PowerKit {

    /** Stable id used in kits.yml assignments and admin commands. */
    String id();

    String displayName();

    /** Registers listeners and starts tasks. Called once, on plugin enable. */
    default void onEnable() {
    }

    /** Cancels tasks and reverses anything that would otherwise persist. */
    default void onDisable() {
    }

    default void onJoin(Player owner) {
    }

    default void onQuit(Player owner) {
    }

    /** Runs on the shared kit tick (once a second by default) while the owner is online. */
    default void tick(Player owner) {
    }

    /** Fired when a gated power of this kit is unlocked, however it was unlocked. */
    default void onUnlock(Player owner, Power power) {
    }

    /** Abilities the owner can fire with {@code /power use <id>}. */
    default List<Ability> abilities() {
        return List.of();
    }

    /** Ability bound to sneak + right-click, or null if the kit has no obvious primary. */
    default String primaryAbilityId() {
        return null;
    }

    /**
     * Fires an ability.
     *
     * @return true if it went off; false if it was rejected (locked, on cooldown, wrong conditions)
     *     -- the kit is responsible for telling the player why.
     */
    default boolean activate(Player owner, String abilityId) {
        return false;
    }
}
