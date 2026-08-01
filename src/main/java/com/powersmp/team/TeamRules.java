package com.powersmp.team;

import com.powersmp.kit.impl.IdleDeathGambleKit;
import com.powersmp.util.Effects;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * Shared scoreboard-team rules for every kit.
 *
 * <p>Team membership comes from the vanilla main scoreboard, so existing {@code /team} setup works
 * without another data file or command system. A player without a scoreboard team remains neutral.
 */
public final class TeamRules implements Listener {

    private static boolean protectFromNegativeAbilities = true;
    private static boolean shareBuffs = true;
    private static double buffRadius = 50.0d;
    private static int maxSharedDurationTicks = 15 * 20;
    private static int maxSharedAmplifier = 1;
    private static final ThreadLocal<Player> ACTIVE_ABILITY_SOURCE = new ThreadLocal<>();

    public TeamRules() {
    }

    public static void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        protectFromNegativeAbilities =
                section.getBoolean("protect-from-negative-abilities", protectFromNegativeAbilities);
        ConfigurationSection sharing = section.getConfigurationSection("buff-sharing");
        if (sharing != null) {
            shareBuffs = sharing.getBoolean("enabled", shareBuffs);
            buffRadius = Math.max(0.0d, sharing.getDouble("radius", buffRadius));
            maxSharedDurationTicks = Math.max(1,
                    (int) Math.round(sharing.getDouble(
                            "max-duration-seconds", maxSharedDurationTicks / 20.0d) * 20.0d));
            maxSharedAmplifier = Math.max(0,
                    sharing.getInt("max-amplifier", maxSharedAmplifier));
        }
    }

    /** True when a PowerSMP hostile effect may be applied to this target. */
    public static boolean canAffect(Player source, LivingEntity target) {
        return !protectFromNegativeAbilities
                || !(target instanceof Player player)
                || !areTeammates(source, player);
    }

    /** Resolves the player responsible for direct and projectile damage. */
    public static Player playerSource(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    public static boolean areTeammates(Player first, Player second) {
        if (first == null || second == null || first.equals(second)) {
            return false;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard main = manager.getMainScoreboard();
        Team firstDirect = main.getEntryTeam(first.getName());
        Team secondDirect = main.getEntryTeam(second.getName());
        boolean firstJackpotGlow = isTemporaryJackpotTeam(first, firstDirect);
        boolean secondJackpotGlow = isTemporaryJackpotTeam(second, secondDirect);
        Team firstMain = effectiveMainTeam(first, main, firstDirect);
        Team secondMain = effectiveMainTeam(second, main, secondDirect);
        if (firstMain != null || secondMain != null || firstJackpotGlow || secondJackpotGlow) {
            return firstMain != null && Objects.equals(firstMain, secondMain);
        }

        // Some team plugins place both players on the same private scoreboard rather than main.
        Scoreboard firstBoard = first.getScoreboard();
        Scoreboard secondBoard = second.getScoreboard();
        if (!Objects.equals(firstBoard, secondBoard)) {
            return false;
        }
        Team firstTeam = firstBoard.getEntryTeam(first.getName());
        return firstTeam != null
                && Objects.equals(firstTeam, secondBoard.getEntryTeam(second.getName()));
    }

    private static boolean isTemporaryJackpotTeam(Player player, Team directTeam) {
        return directTeam != null
                && IdleDeathGambleKit.JACKPOT_TEAM.equals(directTeam.getName())
                && player.getScoreboardTags().contains(IdleDeathGambleKit.JACKPOT_TAG);
    }

    private static Team effectiveMainTeam(Player player, Scoreboard main, Team directTeam) {
        if (!isTemporaryJackpotTeam(player, directTeam)) {
            return directTeam;
        }
        for (String tag : player.getScoreboardTags()) {
            if (!tag.startsWith(IdleDeathGambleKit.PREVIOUS_TEAM_TAG_PREFIX)) {
                continue;
            }
            String previousName =
                    tag.substring(IdleDeathGambleKit.PREVIOUS_TEAM_TAG_PREFIX.length());
            return IdleDeathGambleKit.NO_PREVIOUS_TEAM.equals(previousName)
                    ? null : main.getTeam(previousName);
        }
        return null;
    }

    /**
     * Attributes synchronous area damage (explosions and lightning) to an ability owner so the
     * event listener can protect nearby teammates caught outside the ability's direct target list.
     */
    public static void runProtected(Player source, Runnable action) {
        Player previous = ACTIVE_ABILITY_SOURCE.get();
        ACTIVE_ABILITY_SOURCE.set(source);
        try {
            action.run();
        } finally {
            if (previous == null) {
                ACTIVE_ABILITY_SOURCE.remove();
            } else {
                ACTIVE_ABILITY_SOURCE.set(previous);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaDamage(EntityDamageEvent event) {
        Player source = ACTIVE_ABILITY_SOURCE.get();
        if (source != null && event.getEntity() instanceof Player target
                && !canAffect(source, target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaCombust(EntityCombustEvent event) {
        Player source = ACTIVE_ABILITY_SOURCE.get();
        if (source != null && event.getEntity() instanceof Player target
                && !canAffect(source, target)) {
            event.setCancelled(true);
        }
    }

    /**
     * Mirrors a beneficial PowerSMP potion effect to nearby teammates with server-configured caps.
     * Called only by {@link Effects}; the direct add avoids recursive re-sharing.
     */
    public static void shareBuff(
            LivingEntity recipient, PotionEffectType type, int durationTicks, int amplifier) {
        if (!shareBuffs || buffRadius <= 0.0d || !(recipient instanceof Player source)
                || !Effects.isBeneficial(type) || amplifier < 0) {
            return;
        }
        int sharedDuration = durationTicks == PotionEffect.INFINITE_DURATION
                ? maxSharedDurationTicks
                : Math.max(1, Math.min(durationTicks, maxSharedDurationTicks));
        int sharedAmplifier = Math.min(amplifier, maxSharedAmplifier);
        double radiusSquared = buffRadius * buffRadius;
        for (Entity nearby : source.getNearbyEntities(buffRadius, buffRadius, buffRadius)) {
            if (!(nearby instanceof Player teammate)
                    || source.getLocation().distanceSquared(teammate.getLocation())
                            > radiusSquared
                    || !areTeammates(source, teammate)) {
                continue;
            }
            PotionEffect existing = teammate.getPotionEffect(type);
            if (existing != null
                    && (existing.getAmplifier() > sharedAmplifier
                            || (existing.getAmplifier() == sharedAmplifier
                                    && (existing.getDuration() == PotionEffect.INFINITE_DURATION
                                            || existing.getDuration() >= sharedDuration)))) {
                continue;
            }
            teammate.addPotionEffect(new PotionEffect(
                    type, sharedDuration, sharedAmplifier, true, false, true));
        }
    }
}
