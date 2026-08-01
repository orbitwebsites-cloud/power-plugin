package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * IdleDeathGamble: an active Jackpot roll supported by two passive odds modifiers.
 *
 * <p>A normal win arms Fever, making the first roll after the Jackpot a flat 50%. That Fever roll
 * is consumed whether it wins or loses, so a Fever Jackpot ends the two-Jackpot cycle. Rising Odds
 * adds to the next roll after each loss with no gameplay cap and resets on every win.
 */
public class IdleDeathGambleKit implements PowerKit {

    public static final String ID = "idledeathgamble";
    public static final String JACKPOT_TEAM = "jackpot";
    public static final String JACKPOT_TAG = "jackpotstarted";
    public static final String PREVIOUS_TEAM_TAG_PREFIX = "powersmp_jackpot_previous=";
    public static final String NO_PREVIOUS_TEAM = "-";

    private static final String ABILITY_JACKPOT = "jackpot";
    private static final long AURA_PERIOD_TICKS = 5L;
    /** Exact requested Jackpot duration: 4 minutes 11 seconds. */
    private static final double JACKPOT_DURATION_SECONDS = 251.0d;

    private final PowerSMP plugin;
    private final Map<UUID, BukkitTask> greenAuras = new ConcurrentHashMap<>();

    private int baseChance = 14;
    private int postWinResetChance = 15;
    private int failureIncrease = 14;
    private int feverChance = 50;
    private double normalCooldownSeconds = 35.0d;
    /** Cooldown that begins after the Jackpot buffs expire. */
    private double jackpotCooldownSeconds = 120.0d;
    private int strengthAmplifier = 2;
    private int regenerationAmplifier = 4;
    private int speedAmplifier = 2;

    public IdleDeathGambleKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Idle Death Gamble";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            baseChance = positiveChance(
                    section.getInt("base-win-chance-percent", baseChance));
            postWinResetChance = positiveChance(
                    section.getInt("post-win-reset-percent", postWinResetChance));
            failureIncrease = Math.max(0,
                    section.getInt("failure-increase-percent", failureIncrease));
            feverChance = positiveChance(
                    section.getInt("fever-chance-percent", feverChance));
            normalCooldownSeconds = Math.max(0.0d,
                    section.getDouble("cooldown-seconds", normalCooldownSeconds));
            jackpotCooldownSeconds = Math.max(0.0d,
                    section.getDouble("jackpot-cooldown-seconds", jackpotCooldownSeconds));
            strengthAmplifier = Math.max(0,
                    section.getInt("strength-amplifier", strengthAmplifier));
            regenerationAmplifier = Math.max(0,
                    section.getInt("regeneration-amplifier", regenerationAmplifier));
            speedAmplifier = Math.max(0, section.getInt("speed-amplifier", speedAmplifier));
        }
        plugin.cooldowns().registerLabel(ABILITY_JACKPOT, "Jackpot");
        // The odds and Fever charge survive a restart, so the matching cooldown must as well.
        plugin.cooldowns().registerPersistent(ABILITY_JACKPOT);
    }

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY_JACKPOT, "Jackpot",
                "Roll your current odds for 4m 11s of Strength III, Regen V, and Speed III."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_JACKPOT;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        if (!ABILITY_JACKPOT.equals(abilityId)) {
            return false;
        }
        if (!plugin.unlocks().isUnlocked(owner, Power.JACKPOT)) {
            return plugin.unlocks().denyLocked(owner, Power.JACKPOT);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_JACKPOT, normalCooldownSeconds)) {
            return false;
        }

        PlayerData data = plugin.data().get(owner.getUniqueId());
        int risingChance = Math.max(baseChance, positiveChance(data.jackpotChance()));
        boolean feverRoll = data.jackpotFeverArmed()
                && plugin.unlocks().isUnlocked(owner, Power.FEVER);
        int rollChance = feverRoll ? feverChance : risingChance;
        boolean won = ThreadLocalRandom.current().nextInt(100) < rollChance;

        if (won) {
            win(owner, data, rollChance, feverRoll);
        } else {
            lose(owner, data, rollChance, risingChance);
        }
        plugin.data().markDirty();
        return true;
    }

    private void win(Player owner, PlayerData data, int chance, boolean feverRoll) {
        int durationTicks = (int) (JACKPOT_DURATION_SECONDS * 20.0d);

        // Keep rerolls locked for the full Jackpot, then apply the configured post-Jackpot CD.
        plugin.cooldowns().setSeconds(
                owner.getUniqueId(), ABILITY_JACKPOT,
                JACKPOT_DURATION_SECONDS + jackpotCooldownSeconds);

        Effects.apply(owner, PotionEffectType.STRENGTH, durationTicks, strengthAmplifier);
        Effects.apply(owner, PotionEffectType.REGENERATION, durationTicks, regenerationAmplifier);
        Effects.apply(owner, PotionEffectType.SPEED, durationTicks, speedAmplifier);
        Effects.apply(owner, PotionEffectType.GLOWING, durationTicks, 0);
        startGreenAura(owner, durationTicks);

        data.jackpotChance(postWinResetChance);
        boolean feverUnlocked = plugin.unlocks().isUnlocked(owner, Power.FEVER);
        // A normal Jackpot arms one Fever roll. A Fever Jackpot is the second Jackpot in the
        // cycle, so it consumes Fever instead of immediately arming another 50% roll.
        boolean feverArmed = feverUnlocked && !feverRoll;
        data.jackpotFeverArmed(feverArmed);

        String feverStatus = feverArmed
                ? " <light_purple>After it ends, your next roll is "
                        + feverChance + "%.</light_purple>"
                : "";
        String source = feverRoll
                ? "<light_purple><bold>FEVER JACKPOT!</bold></light_purple> <gray>The "
                        + chance + "% Fever roll hit.</gray>" + feverStatus
                : "<gold><bold>JACKPOT!</bold></gold> <gray>Won at <white>" + chance
                        + "%</white>.</gray>" + feverStatus;
        Text.msg(owner, source);
        Text.actionBar(owner, "<gold><bold>JACKPOT</bold></gold> <green>BUFFS ACTIVE</green>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.25f);
        owner.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING, owner.getLocation().add(0, 1, 0),
                45, 0.6, 0.8, 0.6, 0.15);
    }

    /** A deliberately excessive green Jackpot aura that follows the winner for the full buff. */
    private void startGreenAura(Player owner, int durationTicks) {
        cancelGreenAura(owner);
        beginGreenGlow(owner);
        UUID ownerId = owner.getUniqueId();
        Particle.DustOptions lime = new Particle.DustOptions(Color.LIME, 1.65f);

        // The hit itself should be unmistakable even before the repeating aura's first tick.
        owner.getWorld().spawnParticle(
                Particle.DUST, owner.getLocation().add(0, 1, 0),
                180, 0.9, 1.15, 0.9, 0.05, lime);
        owner.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER, owner.getLocation().add(0, 1, 0),
                70, 0.8, 1.0, 0.8, 0.18);

        BukkitRunnable aura = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                if (!owner.isOnline() || elapsedTicks >= durationTicks) {
                    greenAuras.remove(ownerId);
                    endGreenGlow(owner, true);
                    cancel();
                    return;
                }
                owner.getWorld().spawnParticle(
                        Particle.DUST, owner.getLocation().add(0, 1, 0),
                        55, 0.65, 1.0, 0.65, 0.03, lime);
                owner.getWorld().spawnParticle(
                        Particle.HAPPY_VILLAGER, owner.getLocation().add(0, 1, 0),
                        12, 0.55, 0.85, 0.55, 0.08);
                owner.getWorld().spawnParticle(
                        Particle.COMPOSTER, owner.getLocation().add(0, 1, 0),
                        8, 0.5, 0.75, 0.5, 0.06);
                elapsedTicks += (int) AURA_PERIOD_TICKS;
            }
        };
        greenAuras.put(ownerId, aura.runTaskTimer(plugin, 0L, AURA_PERIOD_TICKS));
    }

    private void cancelGreenAura(Player owner) {
        BukkitTask task = greenAuras.remove(owner.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void stopGreenAura(Player owner, boolean removeGlow) {
        cancelGreenAura(owner);
        endGreenGlow(owner, removeGlow);
    }

    private void beginGreenGlow(Player owner) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard scoreboard = manager.getMainScoreboard();
        Team jackpot = scoreboard.getTeam(JACKPOT_TEAM);
        if (jackpot == null) {
            jackpot = scoreboard.registerNewTeam(JACKPOT_TEAM);
        }
        jackpot.setColor(ChatColor.GREEN);

        String previousTag = previousTeamTag(owner);
        Team current = scoreboard.getEntryTeam(owner.getName());
        if (previousTag == null) {
            String previousName = current == null ? NO_PREVIOUS_TEAM : current.getName();
            owner.addScoreboardTag(PREVIOUS_TEAM_TAG_PREFIX + previousName);
        }
        jackpot.addEntry(owner.getName());
        owner.addScoreboardTag(JACKPOT_TAG);
    }

    private void endGreenGlow(Player owner, boolean removeGlow) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        String previousTag = previousTeamTag(owner);
        if (manager != null && (owner.getScoreboardTags().contains(JACKPOT_TAG)
                || previousTag != null)) {
            Scoreboard scoreboard = manager.getMainScoreboard();
            Team jackpot = scoreboard.getTeam(JACKPOT_TEAM);
            if (jackpot != null) {
                jackpot.removeEntry(owner.getName());
            }
            if (previousTag != null) {
                String previousName = previousTag.substring(PREVIOUS_TEAM_TAG_PREFIX.length());
                Team previous = NO_PREVIOUS_TEAM.equals(previousName)
                        ? null : scoreboard.getTeam(previousName);
                if (previous != null) {
                    previous.addEntry(owner.getName());
                }
            }
        }
        owner.removeScoreboardTag(JACKPOT_TAG);
        for (String tag : List.copyOf(owner.getScoreboardTags())) {
            if (tag.startsWith(PREVIOUS_TEAM_TAG_PREFIX)) {
                owner.removeScoreboardTag(tag);
            }
        }
        if (removeGlow) {
            Effects.remove(owner, PotionEffectType.GLOWING);
        }
    }

    private static String previousTeamTag(Player owner) {
        for (String tag : owner.getScoreboardTags()) {
            if (tag.startsWith(PREVIOUS_TEAM_TAG_PREFIX)) {
                return tag;
            }
        }
        return null;
    }

    private void lose(Player owner, PlayerData data, int rolledChance, int risingChance) {
        int nextChance = baseChance;
        if (plugin.unlocks().isUnlocked(owner, Power.RISING_ODDS)) {
            // Fever is a one-roll override, not the new Rising Odds baseline. After a Jackpot
            // resets the ladder to 15%, a failed 50% Fever roll therefore raises it to 29%.
            // There is deliberately no percentage cap. Saturating at the Java integer ceiling
            // only prevents corrupt/configured values from wrapping around into a negative chance.
            nextChance = (int) Math.min(
                    Integer.MAX_VALUE, (long) risingChance + failureIncrease);
        }
        data.jackpotChance(nextChance);
        data.jackpotFeverArmed(false);

        Text.msg(owner, "<red><bold>NO JACKPOT</bold></red> <gray>Rolled at <white>"
                + rolledChance + "%</white>. Next chance: <yellow>"
                + nextChance + "%</yellow>.</gray>");
        owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
        owner.getWorld().spawnParticle(
                Particle.SMOKE, owner.getLocation().add(0, 1, 0),
                12, 0.25, 0.35, 0.25, 0.02);
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        if (power == Power.JACKPOT) {
            stopGreenAura(owner, true);
        } else if (power == Power.FEVER) {
            data.jackpotFeverArmed(false);
            plugin.data().markDirty();
        } else if (power == Power.RISING_ODDS) {
            data.jackpotChance(baseChance);
            plugin.data().markDirty();
        }
    }

    @Override
    public void onQuit(Player owner) {
        stopGreenAura(owner, true);
    }

    @Override
    public void onJoin(Player owner) {
        if (!owner.getScoreboardTags().contains(JACKPOT_TAG)) {
            return;
        }
        PotionEffect glow = owner.getPotionEffect(PotionEffectType.GLOWING);
        if (glow != null && glow.getDuration() > 0
                && plugin.unlocks().isUnlocked(owner, Power.JACKPOT)) {
            startGreenAura(owner, glow.getDuration());
        } else {
            stopGreenAura(owner, true);
        }
    }

    @Override
    public void onDisable() {
        for (UUID ownerId : List.copyOf(greenAuras.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                stopGreenAura(owner, true);
            } else {
                BukkitTask task = greenAuras.remove(ownerId);
                if (task != null) {
                    task.cancel();
                }
            }
        }
    }

    private static int positiveChance(int chance) {
        return Math.max(1, chance);
    }
}
