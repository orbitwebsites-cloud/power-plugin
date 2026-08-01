package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * disasterflames: instantaneous spatial exchange and a teammate proximity bond.
 *
 * <p>The swap has no artificial range cap: every currently loaded entity in the owner's world is
 * considered, and the closest entity whose hitbox intersects the owner's view ray is selected.
 * Unloaded entities cannot be seen by either the client or server and therefore cannot be valid
 * "looking at" targets.
 */
public class DisasterflamesKit implements PowerKit, Listener {

    public static final String ID = "disasterflames";

    private static final String ABILITY_SWAP = "instant_exchange";

    private final PowerSMP plugin;
    private final Map<UUID, Long> invincibleUntil = new ConcurrentHashMap<>();
    /** Prevents a held key or click macro from turning a failed target check into chat spam. */
    private final Map<UUID, Long> nextTargetHintAt = new ConcurrentHashMap<>();

    private double swapCooldownSeconds;
    private double targetAssistRadius = 0.25d;
    private long targetHintCooldownMillis = 1_500L;

    private double bondRadius = 10.0d;
    private int bondStrengthAmplifier;
    private int bondSpeedAmplifier;
    private int bondResistanceAmplifier;

    private int surgeStrengthAmplifier = 1;
    private int surgeSpeedAmplifier = 1;
    private int surgeBuffTicks = 200;
    private long surgeInvincibilityMillis = 2_000L;

    public DisasterflamesKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Disasterflames";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection swap = section.getConfigurationSection("instant-exchange");
        if (swap != null) {
            swapCooldownSeconds = Math.max(0.0d,
                    swap.getDouble("cooldown-seconds", swapCooldownSeconds));
            targetAssistRadius = Math.max(0.0d,
                    swap.getDouble("target-assist-radius", targetAssistRadius));
            targetHintCooldownMillis = Math.max(0L,
                    Math.round(swap.getDouble(
                            "target-hint-cooldown-seconds", targetHintCooldownMillis / 1000.0d)
                            * 1000.0d));
        }
        ConfigurationSection bond = section.getConfigurationSection("brother-bond");
        if (bond != null) {
            bondRadius = Math.max(0.0d, bond.getDouble("radius", bondRadius));
            bondStrengthAmplifier = Math.max(0,
                    bond.getInt("strength-amplifier", bondStrengthAmplifier));
            bondSpeedAmplifier = Math.max(0,
                    bond.getInt("speed-amplifier", bondSpeedAmplifier));
            bondResistanceAmplifier = Math.max(0,
                    bond.getInt("resistance-amplifier", bondResistanceAmplifier));
        }
        ConfigurationSection surge = section.getConfigurationSection("swap-surge");
        if (surge != null) {
            surgeStrengthAmplifier = Math.max(0,
                    surge.getInt("strength-amplifier", surgeStrengthAmplifier));
            surgeSpeedAmplifier = Math.max(0,
                    surge.getInt("speed-amplifier", surgeSpeedAmplifier));
            surgeBuffTicks = Math.max(0,
                    (int) Math.round(surge.getDouble("buff-seconds", surgeBuffTicks / 20.0d) * 20.0d));
            surgeInvincibilityMillis = Math.max(0L,
                    Math.round(surge.getDouble(
                            "invincibility-seconds", surgeInvincibilityMillis / 1000.0d) * 1000.0d));
        }
    }

    @Override
    public void onEnable() {
        plugin.cooldowns().registerLabel(ABILITY_SWAP, "Instant Exchange");
    }

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(
                ABILITY_SWAP,
                "Instant Exchange",
                "Swap positions with the entity you are looking at."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_SWAP;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        if (!ABILITY_SWAP.equalsIgnoreCase(abilityId)) {
            return false;
        }
        if (!plugin.unlocks().isUnlocked(owner, Power.SWAP_POSITIONS)) {
            return plugin.unlocks().denyLocked(owner, Power.SWAP_POSITIONS);
        }

        Entity target = findLookTarget(owner);
        if (target == null) {
            showTargetHint(owner);
            return false;
        }
        if (swapCooldownSeconds > 0.0d
                && !plugin.cooldowns().tryUse(owner, ABILITY_SWAP, swapCooldownSeconds)) {
            return false;
        }
        if (!swap(owner, target)) {
            if (swapCooldownSeconds > 0.0d) {
                plugin.cooldowns().clear(owner.getUniqueId(), ABILITY_SWAP);
            }
            Text.msg(owner, "<red>That entity could not be moved.");
            return false;
        }

        if (plugin.unlocks().isUnlocked(owner, Power.SWAP_SURGE)) {
            applySwapSurge(owner);
        }
        return true;
    }

    private Entity findLookTarget(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection().normalize();
        Entity best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Entity candidate : owner.getWorld().getEntities()) {
            if (candidate.equals(owner) || !candidate.isValid() || candidate.isDead()
                    || (candidate instanceof LivingEntity living
                            && !TeamRules.canAffect(owner, living))) {
                continue;
            }
            BoundingBox hitbox = candidate.getBoundingBox().expand(targetAssistRadius);
            RayTraceResult hit = hitbox.rayTrace(origin, direction, Double.MAX_VALUE);
            if (hit == null || !owner.hasLineOfSight(candidate)) {
                continue;
            }
            double distanceSquared = hit.getHitPosition().distanceSquared(origin);
            if (distanceSquared < bestDistanceSquared) {
                best = candidate;
                bestDistanceSquared = distanceSquared;
            }
        }
        return best;
    }

    /** Shows one short action-bar hint, then stays silent until the configured retry window ends. */
    private void showTargetHint(Player owner) {
        long now = System.currentTimeMillis();
        UUID uuid = owner.getUniqueId();
        Long nextHintAt = nextTargetHintAt.get(uuid);
        if (nextHintAt != null && nextHintAt > now) {
            return;
        }
        nextTargetHintAt.put(uuid, now + targetHintCooldownMillis);
        Text.actionBar(owner, "<red>Look at a player or mob to use Instant Exchange.</red>");
    }

    private boolean swap(Player owner, Entity target) {
        Location ownerStart = owner.getLocation().clone();
        Location targetStart = target.getLocation().clone();

        Location ownerDestination = targetStart.clone();
        ownerDestination.setYaw(ownerStart.getYaw());
        ownerDestination.setPitch(ownerStart.getPitch());
        Location targetDestination = ownerStart.clone();
        targetDestination.setYaw(targetStart.getYaw());
        targetDestination.setPitch(targetStart.getPitch());

        owner.getWorld().spawnParticle(
                Particle.REVERSE_PORTAL, ownerStart.clone().add(0.0d, 1.0d, 0.0d),
                35, 0.4d, 0.8d, 0.4d, 0.08d);
        if (!owner.teleport(ownerDestination)) {
            return false;
        }
        if (!target.teleport(targetDestination)) {
            owner.teleport(ownerStart);
            return false;
        }

        owner.getWorld().spawnParticle(
                Particle.PORTAL, owner.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                45, 0.4d, 0.8d, 0.4d, 0.12d);
        target.getWorld().spawnParticle(
                Particle.PORTAL, target.getLocation().clone().add(0.0d, 0.5d, 0.0d),
                30, 0.4d, 0.5d, 0.4d, 0.1d);
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.35f);
        if (target instanceof Player targetPlayer) {
            targetPlayer.playSound(
                    targetPlayer.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.85f);
        }
        Text.actionBar(owner, "<light_purple><bold>INSTANT EXCHANGE</bold></light_purple>");
        return true;
    }

    private void applySwapSurge(Player owner) {
        Effects.apply(owner, PotionEffectType.STRENGTH, surgeBuffTicks, surgeStrengthAmplifier);
        Effects.apply(owner, PotionEffectType.SPEED, surgeBuffTicks, surgeSpeedAmplifier);
        if (surgeInvincibilityMillis > 0L) {
            invincibleUntil.put(
                    owner.getUniqueId(), System.currentTimeMillis() + surgeInvincibilityMillis);
        }
        owner.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING, owner.getLocation().clone().add(0.0d, 1.0d, 0.0d),
                20, 0.35d, 0.6d, 0.35d, 0.12d);
    }

    // ---- Brother Bond ------------------------------------------------------

    @Override
    public void tick(Player owner) {
        expireInvincibility(owner.getUniqueId());
        if (!plugin.unlocks().isUnlocked(owner, Power.BROTHER_BOND) || bondRadius <= 0.0d) {
            return;
        }
        double radiusSquared = bondRadius * bondRadius;
        for (Player teammate : Bukkit.getOnlinePlayers()) {
            if (teammate.equals(owner)
                    || !teammate.getWorld().equals(owner.getWorld())
                    || teammate.getLocation().distanceSquared(owner.getLocation()) > radiusSquared
                    || !TeamRules.areTeammates(owner, teammate)) {
                continue;
            }
            applyBond(owner);
            applyBond(teammate);
        }
    }

    private void applyBond(Player player) {
        Effects.refresh(player, PotionEffectType.STRENGTH, bondStrengthAmplifier);
        Effects.refresh(player, PotionEffectType.SPEED, bondSpeedAmplifier);
        Effects.refresh(player, PotionEffectType.RESISTANCE, bondResistanceAmplifier);
    }

    // ---- Swap Surge invincibility -----------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.SWAP_SURGE)) {
            return;
        }
        Long until = invincibleUntil.get(player.getUniqueId());
        if (until == null) {
            return;
        }
        if (until <= System.currentTimeMillis()) {
            invincibleUntil.remove(player.getUniqueId(), until);
            return;
        }
        event.setCancelled(true);
    }

    private void expireInvincibility(UUID owner) {
        Long until = invincibleUntil.get(owner);
        if (until != null && until <= System.currentTimeMillis()) {
            invincibleUntil.remove(owner, until);
        }
    }

    @Override
    public void onQuit(Player owner) {
        invincibleUntil.remove(owner.getUniqueId());
        nextTargetHintAt.remove(owner.getUniqueId());
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.SWAP_SURGE) {
            invincibleUntil.remove(owner.getUniqueId());
        }
    }

    @Override
    public void onDisable() {
        invincibleUntil.clear();
        nextTargetHintAt.clear();
    }
}
