package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * JJlionjxi: a three-tier kit -- Wind God, then Fat Tank, then Greedy Heal.
 *
 * <p>The tiers line up with the existing kill-gated progression, so the low tier is available from
 * the start and the other two sit behind thresholds in {@code progression.kill-thresholds}. As with
 * every other kit, {@code unlock-all} being true means all three are simply on right now.
 */
public class JJLionKit implements PowerKit, Listener {

    public static final String ID = "jjlionjxi";

    private static final String ABILITY_FAT_TANK = "fattank";
    private static final String ABILITY_GREEDY_HEAL = "greedyheal";

    private final PowerSMP plugin;

    // Wind God
    private int windChargeStack = 16;
    // Fat Tank
    private int resistanceAmplifier = 1;
    private int fatTankSeconds = 15;
    private double fatTankCooldown = 60.0d;
    // Greedy Heal
    private int instantHealthAmplifier = 1;
    private int regenerationAmplifier;
    private int regenerationSeconds = 10;
    private int absorptionAmplifier = 1;
    private int absorptionSeconds = 60;
    private double greedyHealCooldown = 90.0d;

    public JJLionKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tempest";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection wind = section.getConfigurationSection("wind-god");
            if (wind != null) {
                windChargeStack = Math.max(1, Math.min(Material.WIND_CHARGE.getMaxStackSize(),
                        wind.getInt("stack-size", windChargeStack)));
            }
            ConfigurationSection tank = section.getConfigurationSection("fat-tank");
            if (tank != null) {
                resistanceAmplifier = Math.max(0,
                        tank.getInt("resistance-amplifier", resistanceAmplifier));
                fatTankSeconds = Math.max(0, tank.getInt("duration-seconds", fatTankSeconds));
                fatTankCooldown = Math.max(0.0d,
                        tank.getDouble("cooldown-seconds", fatTankCooldown));
            }
            ConfigurationSection heal = section.getConfigurationSection("greedy-heal");
            if (heal != null) {
                instantHealthAmplifier = Math.max(0,
                        heal.getInt("instant-health-amplifier", instantHealthAmplifier));
                regenerationAmplifier = Math.max(0,
                        heal.getInt("regeneration-amplifier", regenerationAmplifier));
                regenerationSeconds = Math.max(0,
                        heal.getInt("regeneration-seconds", regenerationSeconds));
                absorptionAmplifier = Math.max(0,
                        heal.getInt("absorption-amplifier", absorptionAmplifier));
                absorptionSeconds = Math.max(0,
                        heal.getInt("absorption-seconds", absorptionSeconds));
                greedyHealCooldown = Math.max(0.0d,
                        heal.getDouble("cooldown-seconds", greedyHealCooldown));
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_FAT_TANK, "Fat Tank");
        plugin.cooldowns().registerLabel(ABILITY_GREEDY_HEAL, "Greedy Heal");
    }

    // ---- Wind God -------------------------------------------------------

    /**
     * "Infinite" is implemented as a stack that refills rather than one that never decrements --
     * vanilla consumes the item on throw and there is no unbreakable-style flag for a projectile
     * item, so the stack is simply topped back up on the tick after each throw.
     */
    private void ensureWindCharges(Player owner) {
        int held = 0;
        for (ItemStack item : owner.getInventory().getContents()) {
            if (item != null && item.getType() == Material.WIND_CHARGE) {
                held += item.getAmount();
            }
        }
        if (held >= windChargeStack) {
            return;
        }
        // Leftovers are discarded rather than dropped: a full inventory just means the top-up
        // waits for the next tick, and littering the floor with wind charges helps nobody.
        owner.getInventory().addItem(new ItemStack(Material.WIND_CHARGE, windChargeStack - held));
    }

    @Override
    public void onJoin(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.WIND_GOD)) {
            ensureWindCharges(owner);
        }
    }

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.WIND_GOD)) {
            ensureWindCharges(owner);
        }
    }

    /** Tops the stack back up the tick after a throw, so it never visibly runs down. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getItem() == null || event.getItem().getType() != Material.WIND_CHARGE) {
            return;
        }
        if (!plugin.kits().isOwner(player, ID) || !plugin.unlocks().isUnlocked(player, Power.WIND_GOD)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                ensureWindCharges(player);
            }
        });
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_FAT_TANK, "Fat Tank",
                        "Resistance " + roman(resistanceAmplifier + 1) + " for " + fatTankSeconds
                                + "s. " + (int) fatTankCooldown + "s cooldown."),
                new Ability(ABILITY_GREEDY_HEAL, "Greedy Heal",
                        "Instant Health " + roman(instantHealthAmplifier + 1) + ", Regeneration "
                                + roman(regenerationAmplifier + 1) + " and Absorption "
                                + roman(absorptionAmplifier + 1) + "."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_FAT_TANK;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_FAT_TANK -> fatTank(owner);
            case ABILITY_GREEDY_HEAL -> greedyHeal(owner);
            default -> false;
        };
    }

    private boolean fatTank(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.FAT_TANK)) {
            return plugin.unlocks().denyLocked(owner, Power.FAT_TANK);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_FAT_TANK, fatTankCooldown)) {
            return false;
        }
        Effects.apply(owner, PotionEffectType.RESISTANCE, fatTankSeconds * 20, resistanceAmplifier);
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.6f);
        owner.getWorld().spawnParticle(Particle.BLOCK, owner.getLocation().add(0, 1, 0), 60,
                0.6, 1.0, 0.6, Material.IRON_BLOCK.createBlockData());
        Text.msg(owner, "<gray><bold>FAT TANK</bold></gray> <gray>-- Resistance "
                + roman(resistanceAmplifier + 1) + " for " + fatTankSeconds + "s.</gray>");
        return true;
    }

    private boolean greedyHeal(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.GREEDY_HEAL)) {
            return plugin.unlocks().denyLocked(owner, Power.GREEDY_HEAL);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_GREEDY_HEAL, greedyHealCooldown)) {
            return false;
        }
        // Instant Health resolves immediately, so a one-tick duration is all it needs.
        Effects.apply(owner, PotionEffectType.INSTANT_HEALTH, 1, instantHealthAmplifier);
        Effects.apply(owner, PotionEffectType.REGENERATION, regenerationSeconds * 20, regenerationAmplifier);
        Effects.apply(owner, PotionEffectType.ABSORPTION, absorptionSeconds * 20, absorptionAmplifier);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        owner.getWorld().spawnParticle(Particle.HEART, owner.getLocation().add(0, 1.6, 0), 12, 0.4, 0.4, 0.4, 0.0);
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, owner.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.4);
        Text.msg(owner, "<light_purple><bold>GREEDY HEAL</bold></light_purple> <gray>-- topped up.</gray>");
        return true;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
