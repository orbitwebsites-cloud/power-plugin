package com.powersmp.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player picks up a dragon egg.
 *
 * <p>Draconic Evolution has no design yet ("it's unknown what this power does"), so this is the stub
 * the spec asks for: the trigger is wired and observable, and the power itself is a no-op. When
 * someone decides what it does, listen for this event -- the detection side is done.
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
