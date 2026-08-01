package com.powersmp.kit;

import com.powersmp.PowerSMP;
import java.util.List;
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
 * broke the shortcut for anyone holding gear (a weapon, a shield) -- which is most
 * players, most of the time. There is no hand check here at all now: whatever trigger is configured
 * fires regardless of what is held, and the underlying click is cancelled only when it actually
 * matches so held items keep working normally the rest of the time.
 */
public class AbilityTriggerListener implements Listener {

    private final PowerSMP plugin;

    public AbilityTriggerListener(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
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
        // Some clients/servers pre-cancel air clicks for items that cannot normally be used in
        // air. Those are precisely the clicks our custom powers need. Respect cancellation for
        // block interactions, though, so protection plugins still own protected containers/blocks.
        if (event.isCancelled()
                && (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        boolean sneaking = player.isSneaking();
        AbilityTrigger trigger = right
                ? (sneaking ? AbilityTrigger.SNEAK_RIGHT_CLICK : AbilityTrigger.RIGHT_CLICK)
                : (sneaking ? AbilityTrigger.SNEAK_LEFT_CLICK : AbilityTrigger.LEFT_CLICK);
        if (fire(player, trigger)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        AbilityTrigger trigger = player.isSneaking()
                ? AbilityTrigger.SNEAK_SWAP_HANDS : AbilityTrigger.SWAP_HANDS;
        if (fire(player, trigger)) {
            event.setCancelled(true);
        }
    }

    /**
     * @return true if there was a kit with a primary ability to fire at all (regardless of cooldown).
     * When a player has more than one kit at once (e.g. Phantom + Life Stealer), the chosen ability
     * is looked up across all of them so the bound ability fires on whichever kit actually owns it.
     */
    private boolean fire(Player player, AbilityTrigger trigger) {
        List<PowerKit> kits = plugin.kits().kitsOf(player);
        if (kits.isEmpty()) {
            return false;
        }
        com.powersmp.data.PlayerData data = plugin.data().get(player.getUniqueId());
        String chosen = data.abilityBindings().getOrDefault(trigger.name(), "");
        if (chosen.isBlank() && data.abilityBindings().isEmpty()
                && trigger == AbilityTrigger.fromName(
                        data.abilityTrigger(), AbilityTrigger.SNEAK_RIGHT_CLICK)) {
            chosen = data.primaryAbility();
        }
        if (!chosen.isBlank()) {
            for (PowerKit kit : kits) {
                if (hasAbility(kit, chosen)) {
                    kit.activate(player, chosen);
                    return true;
                }
            }
            return false;
        }
        if (!data.abilityBindings().isEmpty() || trigger != AbilityTrigger.SNEAK_RIGHT_CLICK) {
            return false;
        }
        PowerKit first = kits.get(0);
        String primary = first.primaryAbilityId();
        if (primary == null) {
            return false;
        }
        first.activate(player, primary);
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
