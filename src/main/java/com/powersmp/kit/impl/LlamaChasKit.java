package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * LlamaChas: flight, heat vision, x-ray, freeze breath, super strength.
 *
 * <p>Two of these needed working around rather than implementing directly.
 *
 * <p><b>X-ray</b> cannot be done honestly -- the client decides what it renders and the server
 * cannot reach into that. What it <em>can</em> do is lie about the world: every common stone-type
 * block in range is sent to this player alone as air, so ores are left hanging in the open. Only
 * that one client is affected, the real world is untouched, and the true blocks are re-sent when it
 * expires. The hide list is deliberately restricted to natural filler so it cannot blank out
 * someone's build.
 *
 * <p><b>Flight</b> is genuinely just creative flight, but it is the single most anticheat-triggering
 * thing in this whole plugin. Without the NoCheatPlus exemptions in
 * {@code server-setup/luckperms-commands.txt} he will be flagged and kicked within seconds.
 */
public class LlamaChasKit implements PowerKit, Listener {

    public static final String ID = "llamachas";

    private static final String ABILITY_HEAT = "heatvision";
    private static final String ABILITY_XRAY = "xray";
    private static final String ABILITY_FREEZE = "freezebreath";

    private final PowerSMP plugin;
    /** Blocks currently faked as air per player, so the real ones can be put back. */
    private final Map<UUID, List<Location>> hiddenBlocks = new ConcurrentHashMap<>();

    // Flight
    private float flySpeed = 0.1f;
    // Heat vision
    private double heatRange = 24.0d;
    private double heatDamage = 6.0d;
    private int heatFireTicks = 80;
    private boolean heatIgnitesBlocks;
    private double heatCooldown = 8.0d;
    // X-ray
    private int xrayRadius = 8;
    private int xraySeconds = 12;
    private double xrayCooldown = 60.0d;
    private Set<Material> xrayHides = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRAVEL,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.NETHERRACK);
    // Freeze breath
    private double freezeRange = 10.0d;
    private double freezeAngle = 40.0d;
    private int freezeTicks = 200;
    private int freezeSlowness = 2;
    private double freezeCooldown = 20.0d;
    // Super strength
    private int strengthAmplifier = 1;
    private double bonusKnockback = 0.6d;

    public LlamaChasKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Kryptonian";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            flySpeed = (float) Math.max(0.0d,
                    Math.min(1.0d, section.getDouble("flight.speed", flySpeed)));

            ConfigurationSection heat = section.getConfigurationSection("heat-vision");
            if (heat != null) {
                heatRange = heat.getDouble("range", heatRange);
                heatDamage = heat.getDouble("damage", heatDamage);
                heatFireTicks = heat.getInt("fire-ticks", heatFireTicks);
                heatIgnitesBlocks = heat.getBoolean("ignite-blocks", false);
                heatCooldown = heat.getDouble("cooldown-seconds", heatCooldown);
            }
            ConfigurationSection xray = section.getConfigurationSection("xray");
            if (xray != null) {
                xrayRadius = Math.max(1, Math.min(16, xray.getInt("radius", xrayRadius)));
                xraySeconds = xray.getInt("duration-seconds", xraySeconds);
                xrayCooldown = xray.getDouble("cooldown-seconds", xrayCooldown);
                Set<Material> parsed = EnumSet.noneOf(Material.class);
                for (String name : xray.getStringList("hide-blocks")) {
                    Material material = Material.matchMaterial(name);
                    if (material != null) {
                        parsed.add(material);
                    }
                }
                if (!parsed.isEmpty()) {
                    xrayHides = parsed;
                }
            }
            ConfigurationSection freeze = section.getConfigurationSection("freeze-breath");
            if (freeze != null) {
                freezeRange = freeze.getDouble("range", freezeRange);
                freezeAngle = freeze.getDouble("cone-degrees", freezeAngle);
                freezeTicks = freeze.getInt("freeze-ticks", freezeTicks);
                freezeSlowness = freeze.getInt("slowness-amplifier", freezeSlowness);
                freezeCooldown = freeze.getDouble("cooldown-seconds", freezeCooldown);
            }
            ConfigurationSection strength = section.getConfigurationSection("super-strength");
            if (strength != null) {
                strengthAmplifier = strength.getInt("amplifier", strengthAmplifier);
                bonusKnockback = strength.getDouble("bonus-knockback", bonusKnockback);
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_HEAT, "Heat Vision");
        plugin.cooldowns().registerLabel(ABILITY_XRAY, "X-Ray");
        plugin.cooldowns().registerLabel(ABILITY_FREEZE, "Freeze Breath");
    }

    // ---- passives: flight and super strength ----------------------------

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.FLIGHT)) {
            owner.setAllowFlight(true);
            if (owner.getFlySpeed() != flySpeed) {
                owner.setFlySpeed(flySpeed);
            }
        }
        if (plugin.unlocks().isUnlocked(owner, Power.SUPER_STRENGTH)) {
            Effects.applyInfinite(owner, PotionEffectType.STRENGTH, strengthAmplifier);
        }
    }

    /** Super strength also hits harder in the literal sense -- things go further. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (bonusKnockback <= 0.0d || !(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.SUPER_STRENGTH)) {
            return;
        }
        Entity target = event.getEntity();
        Vector away = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (away.lengthSquared() < 1.0e-4) {
            return;
        }
        Vector push = away.normalize().multiply(bonusKnockback);
        push.setY(Math.max(0.25d, push.getY()));
        target.setVelocity(target.getVelocity().add(push));
    }

    /**
     * Flight has to be handed back on the way out, or he keeps creative flight in survival forever --
     * {@code allowFlight} is persisted in player data. Creative and spectator are left alone.
     */
    private void revokeFlight(Player owner) {
        if (owner.getGameMode() != GameMode.CREATIVE && owner.getGameMode() != GameMode.SPECTATOR) {
            owner.setAllowFlight(false);
            owner.setFlying(false);
        }
        owner.setFlySpeed(0.1f);
    }

    @Override
    public void onJoin(Player owner) {
        // Infinite effects persist in player data across restarts. Clear stale state first; the
        // shared tick immediately re-applies powers that are still granted.
        Effects.remove(owner, PotionEffectType.STRENGTH);
    }

    @Override
    public void onQuit(Player owner) {
        revokeFlight(owner);
        restoreBlocks(owner);
        Effects.remove(owner, PotionEffectType.STRENGTH);
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.FLIGHT) {
            revokeFlight(owner);
        } else if (power == Power.SUPER_STRENGTH) {
            Effects.remove(owner, PotionEffectType.STRENGTH);
        } else if (power == Power.XRAY) {
            restoreBlocks(owner);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                revokeFlight(player);
                restoreBlocks(player);
                Effects.remove(player, PotionEffectType.STRENGTH);
            }
        }
    }

    /** Someone else's x-ray must not survive their disconnect as a permanently wrong world view. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitEvent(PlayerQuitEvent event) {
        hiddenBlocks.remove(event.getPlayer().getUniqueId());
    }

    // ---- heat vision -----------------------------------------------------

    /**
     * Steps along the look vector until it meets something. Stepping by hand rather than using a
     * ray-trace helper means the beam particles can be drawn along the exact path that was tested,
     * so what he sees is what actually got hit.
     */
    private boolean heatVision(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.HEAT_VISION)) {
            return plugin.unlocks().denyLocked(owner, Power.HEAT_VISION);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_HEAT, heatCooldown)) {
            return false;
        }

        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        LivingEntity struck = null;
        Block blocked = null;

        for (double distance = 0.0d; distance <= heatRange; distance += 0.5d) {
            Location point = eye.clone().add(direction.clone().multiply(distance));
            if (point.getWorld() == null) {
                break;
            }
            point.getWorld().spawnParticle(Particle.FLAME, point, 2, 0.02d, 0.02d, 0.02d, 0.0d);

            Block block = point.getBlock();
            if (!block.isPassable()) {
                blocked = block;
                break;
            }
            for (Entity nearby : point.getWorld().getNearbyEntities(point, 0.6d, 0.6d, 0.6d)) {
                if (nearby instanceof LivingEntity candidate && !candidate.equals(owner)) {
                    struck = candidate;
                    break;
                }
            }
            if (struck != null) {
                break;
            }
        }

        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0f, 1.6f);

        if (struck != null) {
            struck.damage(heatDamage, owner);
            struck.setFireTicks(heatFireTicks);
            Text.actionBar(owner, "<gold>Heat vision</gold> <gray>burns "
                    + Text.plain(struck.getName()) + "</gray>");
        } else if (blocked != null && heatIgnitesBlocks) {
            Block above = blocked.getRelative(0, 1, 0);
            if (above.getType().isAir()) {
                above.setType(Material.FIRE);
            }
        }
        return true;
    }

    // ---- x-ray -----------------------------------------------------------

    /**
     * Fake block changes sent to one player only. The server's world is never touched, so nothing
     * here can grief terrain or leak to anyone else -- the worst case is one client briefly seeing
     * stale blocks, which the restore pass corrects.
     */
    private boolean xray(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.XRAY)) {
            return plugin.unlocks().denyLocked(owner, Power.XRAY);
        }
        if (hiddenBlocks.containsKey(owner.getUniqueId())) {
            Text.msg(owner, "<red>X-ray is already active.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_XRAY, xrayCooldown)) {
            return false;
        }

        List<Location> hidden = new ArrayList<>();
        Location centre = owner.getLocation();
        org.bukkit.block.data.BlockData air = Bukkit.createBlockData(Material.AIR);

        for (int x = -xrayRadius; x <= xrayRadius; x++) {
            for (int y = -xrayRadius; y <= xrayRadius; y++) {
                for (int z = -xrayRadius; z <= xrayRadius; z++) {
                    Block block = centre.getBlock().getRelative(x, y, z);
                    if (xrayHides.contains(block.getType())) {
                        owner.sendBlockChange(block.getLocation(), air);
                        hidden.add(block.getLocation());
                    }
                }
            }
        }
        hiddenBlocks.put(owner.getUniqueId(), hidden);

        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.8f);
        Text.msg(owner, "<aqua>X-ray</aqua> <gray>-- " + hidden.size() + " blocks see-through for "
                + xraySeconds + "s.</gray>");

        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreBlocks(owner), xraySeconds * 20L);
        return true;
    }

    /** Sends the real blocks back. Safe to call twice; the second call finds nothing to do. */
    private void restoreBlocks(Player owner) {
        List<Location> hidden = hiddenBlocks.remove(owner.getUniqueId());
        if (hidden == null || !owner.isOnline()) {
            return;
        }
        for (Location location : hidden) {
            owner.sendBlockChange(location, location.getBlock().getBlockData());
        }
        Text.actionBar(owner, "<gray>X-ray fades</gray>");
    }

    // ---- freeze breath ---------------------------------------------------

    private boolean freezeBreath(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.FREEZE_BREATH)) {
            return plugin.unlocks().denyLocked(owner, Power.FREEZE_BREATH);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_FREEZE, freezeCooldown)) {
            return false;
        }

        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        double cosLimit = Math.cos(Math.toRadians(freezeAngle));
        int caught = 0;

        for (Entity nearby : owner.getNearbyEntities(freezeRange, freezeRange, freezeRange)) {
            if (!(nearby instanceof LivingEntity target) || target.equals(owner)) {
                continue;
            }
            if (!owner.hasLineOfSight(target)) {
                continue;
            }
            Vector to = target.getLocation().toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0e-4 || to.normalize().dot(look) < cosLimit) {
                continue;
            }
            // Powder-snow freezing: the vanilla frozen overlay and damage, without the snow.
            target.setFreezeTicks(Math.min(freezeTicks, target.getMaxFreezeTicks() * 4));
            Effects.apply(target, PotionEffectType.SLOWNESS, freezeTicks, freezeSlowness);
            caught++;
        }

        for (double distance = 1.0d; distance <= freezeRange; distance += 0.6d) {
            Location point = eye.clone().add(look.clone().multiply(distance));
            owner.getWorld().spawnParticle(Particle.SNOWFLAKE, point, 6,
                    distance * 0.05d, distance * 0.05d, distance * 0.05d, 0.01d);
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 1.0f, 0.6f);
        Text.actionBar(owner, "<aqua>Freeze breath</aqua> <gray>-- " + caught + " frozen</gray>");
        return true;
    }

    // ---- abilities -------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_HEAT, "Heat Vision",
                        "Burn what you are looking at, up to " + (int) heatRange + " blocks."),
                new Ability(ABILITY_XRAY, "X-Ray",
                        "See through stone for " + xraySeconds + "s."),
                new Ability(ABILITY_FREEZE, "Freeze Breath",
                        "Freeze everything in a cone in front of you."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_HEAT;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_HEAT -> heatVision(owner);
            case ABILITY_XRAY -> xray(owner);
            case ABILITY_FREEZE -> freezeBreath(owner);
            default -> false;
        };
    }
}
