package com.powersmp.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player picks up a dragon egg.
 *
 * <p>The kit handles this itself -- the egg becomes a Dragon Omelet, and eating that consolidates
 * the stances and grants the Draconic Mace. The event is kept as a public hook so other plugins (or
 * later additions to this one) can react to the egg changing hands without editing the kit.
 */
public class DraconicEvolutionEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ItemStack egg;

    public DraconicEvolutionEvent(@NotNull Player who, @NotNull ItemStack egg) {
        super(who);
        this.egg = egg;
    }

    public ItemStack getEgg() {
        return egg;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
