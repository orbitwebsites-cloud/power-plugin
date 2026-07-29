package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Sparkkkkkkkk: The Atom.
 *
 * <p>The form gave three lines -- "more gunpowder from creeper drops", "an explosion", "long range
 * explosion or if you want to go crazy the atom bomb" -- and no numbers at all, so every value here
 * is a starting point marked ASSUMED in kits.yml rather than anything he asked for.
 *
 * <p>Both explosions default to <b>not</b> breaking blocks. An ability that reshapes terrain on a
 * cooldown is a griefing tool as much as a weapon, and turning it on is a one-word config change
 * that an admin can make deliberately; turning it off after someone has cratered spawn is not.
 */
public class SparkKit implements PowerKit, Listener {

    public static final String ID = "sparkkkkkkkk";

    private static final String ABILITY_BLAST = "blast";
    private static final String ABILITY_ATOM = "atom";

    private final PowerSMP plugin;

    private int bonusGunpowder = 3;
    private float blastPower = 3.0f;
    private boolean blastBreaksBlocks;
    private boolean blastSetsFire;
    private double blastCooldown = 45.0d;
    private float atomPower = 6.0f;
    private boolean atomBreaksBlocks;
    private boolean atomSetsFire;
    private double atomRange = 40.0d;
    private double atomCooldown = 300.0d;

    public SparkKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "The Atom";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection powder = section.getConfigurationSection("gunpowder");
            if (powder != null) {
                bonusGunpowder = Math.max(0,
                        powder.getInt("bonus-per-creeper", bonusGunpowder));
            }
            ConfigurationSection blast = section.getConfigurationSection("explosion");
            if (blast != null) {
                blastPower = (float) Math.max(0.0d, blast.getDouble("power", blastPower));
                blastBreaksBlocks = blast.getBoolean("break-blocks", false);
                blastSetsFire = blast.getBoolean("set-fire", false);
                blastCooldown = Math.max(0.0d,
                        blast.getDouble("cooldown-seconds", blastCooldown));
            }
            ConfigurationSection atom = section.getConfigurationSection("atom-bomb");
            if (atom != null) {
                atomPower = (float) Math.max(0.0d, atom.getDouble("power", atomPower));
                atomBreaksBlocks = atom.getBoolean("break-blocks", false);
                atomSetsFire = atom.getBoolean("set-fire", false);
                atomRange = Math.max(1.0d, atom.getDouble("range", atomRange));
                atomCooldown = Math.max(0.0d,
                        atom.getDouble("cooldown-seconds", atomCooldown));
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_BLAST, "Explosion");
        plugin.cooldowns().registerLabel(ABILITY_ATOM, "Atom Bomb");
    }

    // ---- low tier: creeper drops ----------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Creeper)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.GUNPOWDER) || bonusGunpowder <= 0) {
            return;
        }
        event.getDrops().add(new ItemStack(Material.GUNPOWDER, bonusGunpowder));
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_BLAST, "Explosion", "Detonate where you are standing."),
                new Ability(ABILITY_ATOM, "Atom Bomb",
                        "Detonate where you are looking, up to " + (int) atomRange + " blocks away."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_BLAST;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_BLAST -> blast(owner);
            case ABILITY_ATOM -> atom(owner);
            default -> false;
        };
    }

    private boolean blast(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.EXPLOSION)) {
            return plugin.unlocks().denyLocked(owner, Power.EXPLOSION);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_BLAST, blastCooldown)) {
            return false;
        }
        detonate(owner, owner.getLocation(), blastPower, blastBreaksBlocks, blastSetsFire);
        Text.msg(owner, "<gold><bold>BOOM</bold></gold>");
        return true;
    }

    private boolean atom(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.ATOM_BOMB)) {
            return plugin.unlocks().denyLocked(owner, Power.ATOM_BOMB);
        }
        Block target = owner.getTargetBlockExact((int) atomRange);
        Location where = target != null
                ? target.getLocation()
                : owner.getLocation().add(owner.getLocation().getDirection().multiply(atomRange));
        if (!plugin.cooldowns().tryUse(owner, ABILITY_ATOM, atomCooldown)) {
            return false;
        }
        detonate(owner, where, atomPower, atomBreaksBlocks, atomSetsFire);
        Text.msg(owner, "<dark_red><bold>ATOM BOMB</bold></dark_red>");
        return true;
    }

    /**
     * The caster is credited as the source, so kills are attributed to him rather than to the world
     * -- which also means his own kit's kill-gated unlocks progress from it.
     */
    private void detonate(Player owner, Location where, float power, boolean breakBlocks, boolean fire) {
        if (where.getWorld() == null) {
            return;
        }
        // Particle.FLASH throws IllegalArgumentException ("missing required data class
        // org.bukkit.Color") on this server build -- createExplosion's own EXPLOSION particle
        // covers the visual anyway.
        where.getWorld().createExplosion(where, power, fire, breakBlocks, owner);
        where.getWorld().playSound(where, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
        where.getWorld().spawnParticle(Particle.LARGE_SMOKE, where, 60, power, power, power, 0.05);
    }
}
