package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.title.Title;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Voidwalker: Shadow Step, Grasp of Eylis, and the Illusory Realm.
 *
 * <p>The first two are ordinary combat abilities and live entirely in this class. The third is not
 * -- it opens a whole sealed arena with its own rule set, so the actual mechanics live in
 * {@link com.powersmp.domain.IllusoryRealm} and this class only decides who gets pulled in and when
 * the cooldown starts.
 *
 * <h2>Shadow Step</h2>
 * "Hitting someone lets you teleport behind them once within 6 seconds." Read as: landing a hit
 * arms a short window; a deliberate re-activation (not the next automatic swing) spends it to blink
 * behind the same target. Tracking is per-attacker so the window cannot be kept alive forever by
 * hitting different people.
 *
 * <h2>Grasp of Eylis</h2>
 * A pure AoE control cast -- no targeting beyond "everyone within range" -- so it is a plain
 * activated ability with no on-hit trigger to wire up.
 *
 * <h2>Illusory Realm</h2>
 * "300s cd (only starts counting when the domain ends)" is why this cooldown is set in the
 * {@code onClose} callback passed to {@link com.powersmp.domain.IllusoryRealm#open}, not at
 * activation time like every other ability in the plugin.
 */
public class VoidwalkerKit implements PowerKit, Listener {

    public static final String ID = "voidwalker";

    private static final String ABILITY_SHADOW_STEP = "shadowstep";
    private static final String ABILITY_GRASP = "graspofeylis";
    private static final String ABILITY_REALM = "illusoryrealm";
    private static final String COOLDOWN_REALM = "illusory_realm";

    private final PowerSMP plugin;
    /** Whoever this player last hit, and when -- the armed Shadow Step window. */
    private final Map<UUID, Armed> shadowStepArmed = new ConcurrentHashMap<>();

    // Shadow Step
    private double shadowStepWindowSeconds = 6.0d;
    private double shadowStepDistance = 1.2d;
    private int shadowStepBlindnessTicks = 40;
    private int shadowStepSlownessTicks = 40;
    private int shadowStepSlownessAmplifier = 3;
    private double shadowStepCooldown = 0.0d; // gated by the on-hit window itself, not a timer

    // Grasp of Eylis
    private double graspRadius = 10.0d;
    private int graspSlowTicks = 200;
    private int graspSlowAmplifier = 2;
    private int graspFatigueAmplifier = 2;
    private int graspSelfSpeedAmplifier = 2;
    private int graspSelfHasteAmplifier = 1;
    private int graspSelfBuffTicks = 100;
    private double graspCooldown = 90.0d;

    // Illusory Realm
    private double realmCooldown = 300.0d;

    public VoidwalkerKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Voidwalker";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection step = section.getConfigurationSection("shadow-step");
            if (step != null) {
                shadowStepWindowSeconds = step.getDouble("window-seconds", shadowStepWindowSeconds);
                shadowStepDistance = step.getDouble("behind-distance", shadowStepDistance);
                shadowStepBlindnessTicks = step.getInt("blindness-ticks", shadowStepBlindnessTicks);
                shadowStepSlownessTicks = step.getInt("slowness-ticks", shadowStepSlownessTicks);
                shadowStepSlownessAmplifier = step.getInt("slowness-amplifier", shadowStepSlownessAmplifier);
                shadowStepCooldown = step.getDouble("reuse-cooldown-seconds", shadowStepCooldown);
            }
            ConfigurationSection grasp = section.getConfigurationSection("grasp-of-eylis");
            if (grasp != null) {
                graspRadius = grasp.getDouble("radius", graspRadius);
                graspSlowTicks = grasp.getInt("duration-ticks", graspSlowTicks);
                graspSlowAmplifier = grasp.getInt("slowness-amplifier", graspSlowAmplifier);
                graspFatigueAmplifier = grasp.getInt("mining-fatigue-amplifier", graspFatigueAmplifier);
                graspSelfSpeedAmplifier = grasp.getInt("self-speed-amplifier", graspSelfSpeedAmplifier);
                graspSelfHasteAmplifier = grasp.getInt("self-haste-amplifier", graspSelfHasteAmplifier);
                graspSelfBuffTicks = grasp.getInt("self-buff-ticks", graspSelfBuffTicks);
                graspCooldown = grasp.getDouble("cooldown-seconds", graspCooldown);
            }
            ConfigurationSection realm = section.getConfigurationSection("illusory-realm");
            if (realm != null) {
                realmCooldown = realm.getDouble("cooldown-seconds", realmCooldown);
            }
        }
        plugin.cooldowns().registerLabel(COOLDOWN_REALM, "Illusory Realm");
        // The cooldown starts on close, not on use, so a restart mid-domain must not refund it.
        plugin.cooldowns().registerPersistent(COOLDOWN_REALM);
        plugin.cooldowns().registerLabel(ABILITY_GRASP, "Grasp of Eylis");
    }

    // ---- Shadow Step -------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.SHADOW_STEP)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target) || target.equals(player)
                || !TeamRules.canAffect(player, target)) {
            return;
        }
        shadowStepArmed.put(player.getUniqueId(), new Armed(target.getUniqueId(),
                System.currentTimeMillis() + (long) (shadowStepWindowSeconds * 1000.0d)));
    }

    /**
     * Spends the armed window, if there is one. Deliberately not automatic on every hit -- "lets you
     * teleport" reads as an option the player takes, not a guaranteed blink on every swing, and an
     * automatic version would be indistinguishable from lag to whoever's on the receiving end.
     */
    private boolean shadowStep(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SHADOW_STEP)) {
            return plugin.unlocks().denyLocked(owner, Power.SHADOW_STEP);
        }
        Armed armed = shadowStepArmed.get(owner.getUniqueId());
        if (armed == null || armed.expiresAt < System.currentTimeMillis()) {
            shadowStepArmed.remove(owner.getUniqueId());
            Text.msg(owner, "<red>Shadow Step is not armed -- land a hit first.");
            return false;
        }
        Entity targetEntity = Bukkit.getEntity(armed.target);
        if (!(targetEntity instanceof LivingEntity target) || target.isDead() || !target.isValid()
                || !TeamRules.canAffect(owner, target)) {
            shadowStepArmed.remove(owner.getUniqueId());
            Text.msg(owner, "<red>Your target is gone.");
            return false;
        }
        if (shadowStepCooldown > 0.0d
                && !plugin.cooldowns().tryUse(owner, ABILITY_SHADOW_STEP, shadowStepCooldown)) {
            return false;
        }

        Location targetLoc = target.getLocation();
        Vector behind = targetLoc.getDirection().normalize().multiply(-shadowStepDistance);
        Location requested = new Location(targetLoc.getWorld(),
                targetLoc.getX() + behind.getX(), targetLoc.getY(), targetLoc.getZ() + behind.getZ());
        Location destination = safeDestination(requested);
        if (destination == null) {
            if (shadowStepCooldown > 0.0d) {
                plugin.cooldowns().clear(owner.getUniqueId(), ABILITY_SHADOW_STEP);
            }
            Text.msg(owner, "<red>There is no safe space behind your target.");
            return false;
        }
        shadowStepArmed.remove(owner.getUniqueId());
        destination.setDirection(targetLoc.toVector().subtract(destination.toVector()));
        if (destination.getWorld() != null) {
            destination.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation(), 20, 0.3, 0.5, 0.3, 0.02);
        }
        owner.teleport(destination);
        if (destination.getWorld() != null) {
            destination.getWorld().spawnParticle(Particle.REVERSE_PORTAL, destination, 25, 0.3, 0.5, 0.3, 0.05);
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);

        Effects.apply(target, PotionEffectType.BLINDNESS, shadowStepBlindnessTicks, 0);
        Effects.apply(target, PotionEffectType.SLOWNESS, shadowStepSlownessTicks, shadowStepSlownessAmplifier);
        Text.actionBar(owner, "<dark_purple>Shadow Step</dark_purple>");
        return true;
    }

    private Location safeDestination(Location requested) {
        if (requested.getWorld() == null) {
            return null;
        }
        for (int offset : new int[]{0, 1, -1, 2, -2}) {
            Location candidate = requested.clone().add(0.0d, offset, 0.0d);
            if (candidate.getBlock().isPassable()
                    && candidate.clone().add(0.0d, 1.0d, 0.0d).getBlock().isPassable()
                    && !candidate.clone().add(0.0d, -1.0d, 0.0d).getBlock().isPassable()) {
                return candidate;
            }
        }
        return null;
    }

    // ---- Grasp of Eylis ------------------------------------------------

    private boolean graspOfEylis(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.GRASP_OF_EYLIS)) {
            return plugin.unlocks().denyLocked(owner, Power.GRASP_OF_EYLIS);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_GRASP, graspCooldown)) {
            return false;
        }

        int caught = 0;
        for (Entity nearby : owner.getNearbyEntities(graspRadius, graspRadius, graspRadius)) {
            if (!(nearby instanceof LivingEntity target) || target.equals(owner)
                    || !TeamRules.canAffect(owner, target)) {
                continue;
            }
            Effects.apply(target, PotionEffectType.SLOWNESS, graspSlowTicks, graspSlowAmplifier);
            Effects.apply(target, PotionEffectType.MINING_FATIGUE, graspSlowTicks, graspFatigueAmplifier);
            caught++;
        }
        Effects.apply(owner, PotionEffectType.SPEED, graspSelfBuffTicks, graspSelfSpeedAmplifier);
        Effects.apply(owner, PotionEffectType.HASTE, graspSelfBuffTicks, graspSelfHasteAmplifier);

        owner.getWorld().spawnParticle(Particle.SONIC_BOOM, owner.getLocation(), 1, 0.0, 0.0, 0.0, 0.0);
        owner.getWorld().spawnParticle(Particle.SQUID_INK, owner.getLocation(), 40, graspRadius / 2, 1.0, graspRadius / 2, 0.05);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.0f, 0.6f);
        Text.actionBar(owner, "<dark_purple><bold>GRASP OF EYLIS</bold></dark_purple> <gray>-- "
                + caught + " caught</gray>");
        return true;
    }

    // ---- Illusory Realm --------------------------------------------------

    private boolean illusoryRealm(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.ILLUSORY_REALM)) {
            return plugin.unlocks().denyLocked(owner, Power.ILLUSORY_REALM);
        }
        if (!plugin.realm().available()) {
            Text.msg(owner, "<red>The Illusory Realm is unavailable on this server.");
            return false;
        }
        if (plugin.realm().hasOpenDomain(owner)) {
            Text.msg(owner, "<red>Your Illusory Realm is already open.");
            return false;
        }
        if (plugin.realm().isInside(owner)) {
            Text.msg(owner, "<red>You cannot open a realm from inside another realm.");
            return false;
        }
        if (!plugin.cooldowns().isReady(owner.getUniqueId(), COOLDOWN_REALM)) {
            Text.msg(owner, "<red>Illusory Realm is on cooldown for another <white>"
                    + Text.duration(plugin.cooldowns().remainingMillis(owner.getUniqueId(), COOLDOWN_REALM))
                    + "</white>.");
            return false;
        }

        List<Player> participants = new ArrayList<>();
        participants.add(owner);
        for (Entity nearby : owner.getNearbyEntities(
                plugin.realm().gatherRadius(), plugin.realm().gatherRadius(), plugin.realm().gatherRadius())) {
            if (nearby instanceof Player other && !other.equals(owner)
                    && TeamRules.canAffect(owner, other)
                    && !plugin.realm().isInside(other)) {
                participants.add(other);
            }
        }

        int delayTicks = plugin.realm().entryDelayTicks();
        int blindTicks = plugin.realm().blindnessTicks();
        for (Player player : participants) {
            player.showTitle(Title.title(
                    Text.mm("<dark_purple><bold>ILLUSORY REALM</bold></dark_purple>"),
                    Text.mm("<gray>" + Text.plain(owner.getName()) + " reaches into the dark...</gray>")));
            if (blindTicks > 0) {
                Effects.apply(player, PotionEffectType.BLINDNESS, blindTicks + Math.max(1, delayTicks), 0);
            }
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1.0f, 0.5f);
        }
        Text.actionBar(owner, "<dark_purple>Illusory Realm</dark_purple> <gray>opening...</gray>");

        // "Only starts counting when the domain ends" -- the timer is armed as the onClose
        // callback here, not started now.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline() || plugin.realm().isInside(owner)
                    || !plugin.kits().isOwner(owner, ID)
                    || !plugin.unlocks().isUnlocked(owner, Power.ILLUSORY_REALM)) {
                return;
            }
            List<Player> stillHere = new ArrayList<>();
            for (Player player : participants) {
                if (player.isOnline()
                        && !plugin.realm().isInside(player)
                        && (player.equals(owner) || TeamRules.canAffect(owner, player))
                        && player.getWorld().equals(owner.getWorld())
                        && player.getLocation().distanceSquared(owner.getLocation())
                        <= plugin.realm().gatherRadius() * plugin.realm().gatherRadius() * 4) {
                    stillHere.add(player);
                }
            }
            if (stillHere.isEmpty() || !stillHere.contains(owner)) {
                stillHere = List.of(owner);
            }
            boolean opened = plugin.realm().open(owner, stillHere,
                    () -> plugin.cooldowns().setSeconds(owner.getUniqueId(), COOLDOWN_REALM, realmCooldown));
            if (!opened) {
                // The realm already reported why; nothing further to say here.
                return;
            }
        }, Math.max(1, delayTicks));
        return true;
    }

    // ---- abilities ---------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_SHADOW_STEP, "Shadow Step",
                        "Blink behind whoever you last hit, if it has been under "
                                + (int) shadowStepWindowSeconds + "s."),
                new Ability(ABILITY_GRASP, "Grasp of Eylis",
                        "Slow and weaken everyone nearby; speed and haste for yourself."),
                new Ability(ABILITY_REALM, "Illusory Realm",
                        "Drag everyone nearby into a sealed arena with every power turned off."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_SHADOW_STEP;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_SHADOW_STEP -> shadowStep(owner);
            case ABILITY_GRASP -> graspOfEylis(owner);
            case ABILITY_REALM -> illusoryRealm(owner);
            default -> false;
        };
    }

    @Override
    public void onQuit(Player owner) {
        shadowStepArmed.remove(owner.getUniqueId());
    }

    private record Armed(UUID target, long expiresAt) {
    }
}
