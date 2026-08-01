package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.RayTraceResult;

/** FunnySounds' lighthearted villager-family power. */
public final class FunnySoundsKit implements PowerKit {

    public static final String ID = "funnysounds";
    private static final String ABILITY = "village_charmer";

    private final PowerSMP plugin;
    private double range = 8.0d;
    private double cooldownSeconds = 60.0d;

    public FunnySoundsKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Village Charmer"; }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            range = Math.max(1.0d, section.getDouble("range", range));
            cooldownSeconds = Math.max(0.0d,
                    section.getDouble("cooldown-seconds", cooldownSeconds));
        }
        plugin.cooldowns().registerLabel(ABILITY, "Village Charmer");
    }

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY, "Village Charmer",
                "Look at an adult villager to welcome a baby villager into the village."));
    }

    @Override public String primaryAbilityId() { return ABILITY; }

    @Override
    public boolean activate(Player owner, String abilityId) {
        if (!ABILITY.equalsIgnoreCase(abilityId)) return false;
        if (!plugin.unlocks().isUnlocked(owner, Power.VILLAGE_CHARMER)) {
            return plugin.unlocks().denyLocked(owner, Power.VILLAGE_CHARMER);
        }

        Villager adult = aimedVillager(owner);
        if (adult == null || !adult.isAdult()) {
            Text.msg(owner, "<red>Look at an adult villager within " + (int) range + " blocks.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY, cooldownSeconds)) return false;

        Location spawn = adult.getLocation().clone().add(0.6d, 0.0d, 0.6d);
        Villager baby = adult.getWorld().spawn(spawn, Villager.class, villager -> {
            villager.setBaby();
            villager.setProfession(Villager.Profession.NONE);
            villager.setVillagerType(adult.getVillagerType());
            villager.customName(Text.mm("<yellow>FunnySounds' Village Baby</yellow>"));
        });
        adult.getWorld().spawnParticle(Particle.HEART,
                adult.getLocation().add(0.0d, 1.2d, 0.0d), 25, 0.8d, 0.7d, 0.8d, 0.08d);
        baby.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                baby.getLocation().add(0.0d, 0.6d, 0.0d), 35, 0.6d, 0.5d, 0.6d, 0.12d);
        owner.playSound(owner.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.15f);
        Text.msg(owner, "<green>The village welcomed a new baby villager.</green>");
        return true;
    }

    private Villager aimedVillager(Player owner) {
        RayTraceResult trace = owner.getWorld().rayTraceEntities(
                owner.getEyeLocation(), owner.getEyeLocation().getDirection(), range,
                0.6d, entity -> entity instanceof Villager);
        Entity hit = trace == null ? null : trace.getHitEntity();
        if (!(hit instanceof Villager villager) || !owner.hasLineOfSight(villager)) return null;
        // A wall still blocks the ability even though entity ray tracing itself ignores blocks.
        RayTraceResult block = owner.getWorld().rayTraceBlocks(
                owner.getEyeLocation(), owner.getEyeLocation().getDirection(), range,
                FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition() != null
                && block.getHitPosition().distanceSquared(owner.getEyeLocation().toVector())
                < villager.getEyeLocation().toVector().distanceSquared(owner.getEyeLocation().toVector())) {
            return null;
        }
        return villager;
    }
}
