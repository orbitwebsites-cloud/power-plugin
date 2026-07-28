package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * disasterflames: a permanent buff and an on-demand snack.
 *
 * <p>Two powers, both {@code ALWAYS} -- no tiers were given, same call as LlamaChas and Voidwalker.
 *
 * <p>"Sneak + C" is not a request the server can see directly -- the client only ever tells the
 * server about the logical action bound to a key, never which physical key it was, and the vanilla
 * swap-offhand-item action is the one most commonly rebound to C (F is the cramped default). Binding
 * the ability to sneak + swap-hands, whatever key that happens to be on his client, is what "sneak +
 * C" actually means at the protocol level. {@code /power use cookie_stash} works too, in case he
 * ever changes his keybinds back.
 */
public class DisasterFlamesKit implements PowerKit, Listener {

    public static final String ID = "disasterflames";

    private static final String ABILITY_COOKIES = "cookie_stash";

    private final PowerSMP plugin;

    private int regenAmplifier = 4;    // Regeneration V, as specified
    private int strengthAmplifier;     // Strength I
    private int speedAmplifier;        // Speed I
    private int cookieStackSize = 64;

    public DisasterFlamesKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Sugar Rush";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection vigor = section.getConfigurationSection("sweet-vigor");
            if (vigor != null) {
                regenAmplifier = vigor.getInt("regen-amplifier", regenAmplifier);
                strengthAmplifier = vigor.getInt("strength-amplifier", strengthAmplifier);
                speedAmplifier = vigor.getInt("speed-amplifier", speedAmplifier);
            }
            ConfigurationSection cookies = section.getConfigurationSection("cookie-stash");
            if (cookies != null) {
                cookieStackSize = Math.max(1, cookies.getInt("stack-size", cookieStackSize));
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_COOKIES, "Cookie Stash");
    }

    // ---- passive: Sweet Vigor ---------------------------------------------

    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SWEET_VIGOR)) {
            return;
        }
        Effects.applyInfinite(owner, PotionEffectType.REGENERATION, regenAmplifier);
        Effects.applyInfinite(owner, PotionEffectType.STRENGTH, strengthAmplifier);
        Effects.applyInfinite(owner, PotionEffectType.SPEED, speedAmplifier);
    }

    // ---- ability: Cookie Stash ---------------------------------------------

    /** No cooldown, per how every other free-item power in this plugin was specified. */
    private boolean giveCookies(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.COOKIE_STASH)) {
            return plugin.unlocks().denyLocked(owner, Power.COOKIE_STASH);
        }
        owner.getInventory().addItem(new ItemStack(Material.COOKIE, cookieStackSize));
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 1.2f);
        owner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, owner.getLocation().add(0, 1, 0), 10, 0.4, 0.4, 0.4, 0.0);
        Text.actionBar(owner, "<gold>+" + cookieStackSize + " cookies</gold>");
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        event.setCancelled(true);
        giveCookies(player);
    }

    // ---- abilities ---------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY_COOKIES, "Cookie Stash",
                "Sneak + swap-hands for " + cookieStackSize + " cookies."));
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return ABILITY_COOKIES.equalsIgnoreCase(abilityId) && giveCookies(owner);
    }
}
