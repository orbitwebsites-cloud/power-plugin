package com.powersmp.kit;

import com.powersmp.PowerSMP;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Fires a kit's primary ability from whichever client action the player has configured as their
 * trigger -- see {@link AbilityTrigger} and {@code /power keybind}.
 *
 * <p>Previously this was hardcoded to sneak + right-click <em>with an empty hand</em>, which quietly
 * broke the shortcut for anyone holding gear (a weapon, their bound mace, a shield) -- which is most
 * players, most of the time. There is no hand check here at all now: whatever trigger is configured
 * fires regardless of what is held, and the underlying click is cancelled only when it actually
 * matches so held items keep working normally the rest of the time.
 */
public class AbilityTriggerListener implements Listener {

    private final PowerSMP plugin;

    public AbilityTriggerListener(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // A single physical click fires this once per hand; only ever react to the main hand, or a
        // trigger would fire (and spend a cooldown) twice per click.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) {
            return;
        }
        Player player = event.getPlayer();
        boolean sneaking = player.isSneaking();
        boolean matches = switch (triggerOf(player)) {
            case SNEAK_RIGHT_CLICK -> right && sneaking;
            case RIGHT_CLICK -> right;
            case SNEAK_LEFT_CLICK -> left && sneaking;
            case LEFT_CLICK -> left;
            case SWAP_HANDS, SNEAK_SWAP_HANDS -> false;
        };
        if (matches && fire(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        boolean matches = switch (triggerOf(player)) {
            case SWAP_HANDS -> true;
            case SNEAK_SWAP_HANDS -> player.isSneaking();
            default -> false;
        };
        if (matches && fire(player)) {
            event.setCancelled(true);
        }
    }

    private AbilityTrigger triggerOf(Player player) {
        return AbilityTrigger.fromName(
                plugin.data().get(player.getUniqueId()).abilityTrigger(), AbilityTrigger.SNEAK_RIGHT_CLICK);
    }

    /** @return true if there was a kit with a primary ability to fire at all (regardless of cooldown). */
    private boolean fire(Player player) {
        PowerKit kit = plugin.kits().kitOf(player);
        if (kit == null) {
            return false;
        }
        String chosen = plugin.data().get(player.getUniqueId()).primaryAbility();
        String primary = !chosen.isBlank() && hasAbility(kit, chosen) ? chosen : kit.primaryAbilityId();
        if (primary == null) {
            return false;
        }
        kit.activate(player, primary);
        return true;
    }

    private boolean hasAbility(PowerKit kit, String abilityId) {
        for (Ability ability : kit.abilities()) {
            if (ability.id().equalsIgnoreCase(abilityId)) {
                return true;
            }
        }
        return false;
    }
}
