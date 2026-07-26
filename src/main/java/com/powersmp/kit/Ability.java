package com.powersmp.kit;

/**
 * An ability a player can deliberately fire, as opposed to a passive that just happens.
 *
 * @param id          lower_snake identifier, also the cooldown key
 * @param name        display name
 * @param description one line shown by {@code /power list}
 */
public record Ability(String id, String name, String description) {
}
