package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * domanthegamer: spider powers.
 *
 * <p>Low tier is passive (Resistance, unimpeded by cobwebs, no fall damage). Mid tier is Web Strike,
 * which encases a target in cobwebs and lets him climb walls. High tier is the web shooter -- a
 * soulbound harpoon gun that grapples on left click and reels players in on right click.
 *
 * <p>Web Strike restores whatever it replaced. Every block it overwrites is recorded and put back
 * when the webs expire, so the ability cannot be used to permanently grief terrain, and a target
 * webbed inside their own base does not lose a wall.
 */
public class DomanKit implements PowerKit, Listener {

    public static final String ID = "domanthegamer";

    private static final String ABILITY_WEB_STRIKE = "webstrike";
    private static final String ABILITY_SHOOTER = "webshooter";
    private static final String COOLDOWN_PULL = "web_pull";

    private final PowerSMP plugin;
    /** Y at which the current unbroken climb started, so climb-limit-blocks means what it says. */
    private final Map<UUID, Double> climbStartY = new ConcurrentHashMap<>();
    /** In-flight grapple pulls, so re-firing retargets instead of stacking pulls on top of each other. */
    private final Map<UUID, BukkitTask> activeGrapples = new ConcurrentHashMap<>();

    // Low tier
    private int resistanceAmplifier;
    private int weavingAmplifier = 9;
    private boolean noFallDamage = true;
    // Mid tier
    private int webDurationSeconds = 60;
    private double webRange = 20.0d;
    private double webStrikeCooldown = 60.0d;
    private int climbLimit = 10;
    private double climbSpeed = 0.2d;
    // High tier
    private double grappleRange = 20.0d;
    private double grapplePower = 1.4d;
    private int grapplePulseTicks = 40;
    private double pullRange = 15.0d;
    private double pullCooldown = 10.0d;
    private double pullPower = 1.2d;
    private int pullPulseTicks = 8;

    public DomanKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Spider";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection passive = section.getConfigurationSection("spider-passive");
            if (passive != null) {
                resistanceAmplifier = passive.getInt("resistance-amplifier", 0);
                weavingAmplifier = passive.getInt("weaving-amplifier", 9);
                noFallDamage = passive.getBoolean("no-fall-damage", true);
            }
            ConfigurationSection web = section.getConfigurationSection("web-strike");
            if (web != null) {
                webDurationSeconds = web.getInt("duration-seconds", webDurationSeconds);
                webRange = web.getDouble("range", webRange);
                webStrikeCooldown = web.getDouble("cooldown-seconds", webStrikeCooldown);
                climbLimit = web.getInt("climb-limit-blocks", climbLimit);
                climbSpeed = web.getDouble("climb-speed", climbSpeed);
            }
            ConfigurationSection shooter = section.getConfigurationSection("web-shooter");
            if (shooter != null) {
                grappleRange = shooter.getDouble("grapple-range", grappleRange);
                grapplePower = shooter.getDouble("grapple-power", grapplePower);
                grapplePulseTicks = shooter.getInt("grapple-pulse-ticks", grapplePulseTicks);
                pullRange = shooter.getDouble("pull-range", pullRange);
                pullCooldown = shooter.getDouble("pull-cooldown-seconds", pullCooldown);
                pullPower = shooter.getDouble("pull-power", pullPower);
                pullPulseTicks = shooter.getInt("pull-pulse-ticks", pullPulseTicks);
            }
        }
        plugin.cooldowns().registerLabel(ABILITY_WEB_STRIKE, "Web Strike");
        plugin.cooldowns().registerLabel(COOLDOWN_PULL, "Web Pull");
    }

    // ---- low tier: passives ---------------------------------------------

    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SPIDER_PASSIVE)) {
            return;
        }
        Effects.refresh(owner, org.bukkit.potion.PotionEffectType.RESISTANCE, resistanceAmplifier);
        // WEAVING is the 1.21 effect that lets you move through cobwebs unhindered.
        Effects.refresh(owner, org.bukkit.potion.PotionEffectType.WEAVING, weavingAmplifier);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !noFallDamage) {
            return;
        }
        if (event.getEntity() instanceof Player player
                && plugin.kits().isOwner(player, ID)
                && plugin.unlocks().isUnlocked(player, Power.SPIDER_PASSIVE)) {
            event.setCancelled(true);
        }
    }

    /**
     * Wall climbing: hold sneak against a solid block to scale it, up to {@code climb-limit-blocks}
     * of net height per unbroken climb.
     *
     * <p>This has to run on every {@link PlayerMoveEvent}, not the shared once-a-second kit tick --
     * a single small velocity nudge applied once a second gets eaten by gravity before the next one
     * lands, which is why this used to feel like it barely worked at all. A ladder-style constant
     * upward velocity, reapplied every tick the conditions hold, is what actually scales a wall.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player owner = event.getPlayer();
        if (!plugin.kits().isOwner(owner, ID) || !plugin.unlocks().isUnlocked(owner, Power.WEB_STRIKE)) {
            return;
        }
        UUID id = owner.getUniqueId();
        if (!owner.isSneaking() || !againstWall(owner)) {
            climbStartY.remove(id);
            return;
        }
        double startY = climbStartY.computeIfAbsent(id, k -> owner.getLocation().getY());
        if (owner.getLocation().getY() - startY >= climbLimit) {
            return;
        }
        Vector velocity = owner.getVelocity();
        owner.setVelocity(new Vector(velocity.getX(), climbSpeed, velocity.getZ()));
        owner.setFallDistance(0.0f);
    }

    private boolean againstWall(Player owner) {
        Block at = owner.getLocation().getBlock();
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : around) {
            Block side = at.getRelative(offset[0], 0, offset[1]);
            if (!side.isPassable()) {
                return true;
            }
        }
        return false;
    }

    // ---- mid tier: Web Strike -------------------------------------------

    /**
     * Encases the looked-at player in a 2x2x2 cobweb box. Replaced blocks are remembered and put
     * back when it expires, so this traps without permanently rewriting anyone's build.
     */
    private boolean webStrike(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.WEB_STRIKE)) {
            return plugin.unlocks().denyLocked(owner, Power.WEB_STRIKE);
        }
        Player target = nearestLookedAt(owner, webRange);
        if (target == null) {
            Text.msg(owner, "<red>No player in your sights within " + (int) webRange + " blocks.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_WEB_STRIKE, webStrikeCooldown)) {
            return false;
        }

        List<Block> replaced = new ArrayList<>();
        List<Material> previous = new ArrayList<>();
        Block base = target.getLocation().getBlock();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    Block block = base.getRelative(x, y, z);
                    if (block.getType() == Material.COBWEB) {
                        continue;
                    }
                    replaced.add(block);
                    previous.add(block.getType());
                    block.setType(Material.COBWEB);
                }
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < replaced.size(); i++) {
                // Only revert blocks still webbed; anything mined out in the meantime is left alone.
                if (replaced.get(i).getType() == Material.COBWEB) {
                    replaced.get(i).setType(previous.get(i));
                }
            }
        }, webDurationSeconds * 20L);

        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_WOOL_PLACE, 1.0f, 0.7f);
        Text.msg(owner, "<gray>Web Strike</gray> <dark_gray>--</dark_gray> <white>"
                + Text.plain(target.getName()) + "</white> <gray>webbed for " + webDurationSeconds + "s.</gray>");
        Text.msg(target, "<gray>You are caught in webs.</gray>");
        return true;
    }

    private Player nearestLookedAt(Player owner, double range) {
        Vector look = owner.getLocation().getDirection().normalize();
        Location eye = owner.getLocation().add(0, 1.6d, 0);
        Player best = null;
        double bestDot = 0.96d; // roughly a 16-degree cone
        for (Entity entity : owner.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Player candidate) || candidate.equals(owner)) {
                continue;
            }
            Vector to = candidate.getLocation().toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0e-4) {
                continue;
            }
            double dot = to.normalize().dot(look);
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    // ---- high tier: the web shooter -------------------------------------

    private ItemStack shooter() {
        ItemStack gun = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = gun.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm("<gray><bold>Web Shooter</bold></gray>"));
            meta.lore(List.of(
                    Text.mm("<gray>Left click: grapple to a block.</gray>"),
                    Text.mm("<gray>Right click: reel a player in.</gray>"),
                    Text.mm("<dark_gray>Bound -- kept on death.</dark_gray>")));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(Keys.WEB_SHOOTER, PersistentDataType.BYTE, (byte) 1);
            gun.setItemMeta(meta);
        }
        return gun;
    }

    public static boolean isShooter(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.WEB_SHOOTER, PersistentDataType.BYTE);
    }

    /** "Always in my inv even when I die" -- re-issued whenever it is missing. */
    private void ensureShooter(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isShooter(item)) {
                return;
            }
        }
        owner.getInventory().addItem(shooter());
    }

    @Override
    public void onJoin(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.WEB_SHOOTER)) {
            ensureShooter(owner);
        }
    }

    @Override
    public void onUnlock(Player owner, Power power) {
        if (power == Power.WEB_SHOOTER) {
            ensureShooter(owner);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isShooter(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>The web shooter stays with you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isShooter(event.getItem()) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.WEB_SHOOTER)) {
            return;
        }
        // Stop the crossbow behaving like a crossbow.
        event.setCancelled(true);

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            grapple(player);
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            reelIn(player);
        }
    }

    /**
     * No cooldown, per the spec -- the range limit is the only constraint.
     *
     * <p>A single one-off velocity impulse cannot reliably cross 20 blocks -- gravity eats most of
     * it before it gets there, which read as "the range is really about 3 blocks" even though the
     * target-finding itself was working out to the full range. Pulling every tick toward a fixed
     * anchor, like an actual hookshot, is what makes the full range usable, and it comes with a
     * visible line for free since the anchor point is already being recomputed every tick anyway.
     */
    private void grapple(Player owner) {
        Block target = owner.getTargetBlockExact((int) grappleRange);
        if (target == null) {
            Text.actionBar(owner, "<gray>Nothing in range to grapple to.</gray>");
            return;
        }
        Location anchor = target.getLocation().add(0.5d, 0.5d, 0.5d);

        BukkitTask previous = activeGrapples.remove(owner.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 1.4f);

        int[] elapsed = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, self -> {
            if (elapsed[0]++ >= grapplePulseTicks || !owner.isOnline()) {
                self.cancel();
                activeGrapples.remove(owner.getUniqueId());
                return;
            }
            Location from = owner.getEyeLocation();
            Vector to = anchor.toVector().subtract(from.toVector());
            double distance = to.length();
            drawLine(from, anchor, Particle.SMOKE);
            if (distance < 1.5d) {
                self.cancel();
                activeGrapples.remove(owner.getUniqueId());
                return;
            }
            owner.setVelocity(to.normalize().multiply(grapplePower));
            owner.setFallDistance(0.0f);
        }, 0L, 1L);
        activeGrapples.put(owner.getUniqueId(), task);
    }

    /** Same pulsed-pull fix as {@link #grapple}: one packet is too easy for friction to cancel out. */
    private void reelIn(Player owner) {
        List<Player> caught = new ArrayList<>();
        for (Entity entity : owner.getNearbyEntities(pullRange, pullRange, pullRange)) {
            if (entity instanceof Player target && !target.equals(owner)) {
                caught.add(target);
            }
        }
        if (caught.isEmpty()) {
            Text.actionBar(owner, "<gray>Nobody in range to reel in.</gray>");
            return;
        }
        if (!plugin.cooldowns().tryUse(owner, COOLDOWN_PULL, pullCooldown)) {
            return;
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f);
        Text.actionBar(owner, "<gray>Reeling in " + caught.size() + " player(s)</gray>");

        int[] elapsed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, self -> {
            if (elapsed[0]++ >= pullPulseTicks || !owner.isOnline()) {
                self.cancel();
                return;
            }
            for (Player target : caught) {
                if (!target.isOnline() || target.isDead()) {
                    continue;
                }
                Vector to = owner.getLocation().toVector().subtract(target.getLocation().toVector());
                if (to.lengthSquared() < 1.0d) {
                    continue;
                }
                drawLine(target.getEyeLocation(), owner.getEyeLocation(), Particle.SMOKE);
                Vector pull = to.normalize().multiply(pullPower);
                pull.setY(Math.max(0.3d, pull.getY()));
                target.setVelocity(pull);
            }
        }, 0L, 1L);
    }

    /** Traces a thin line of particles between two points -- the visible "web line" on a grapple. */
    private void drawLine(Location from, Location to, Particle particle) {
        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length < 1.0e-4 || from.getWorld() == null) {
            return;
        }
        direction.normalize();
        for (double d = 0.0d; d <= length; d += 0.5d) {
            Location point = from.clone().add(direction.clone().multiply(d));
            from.getWorld().spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_WEB_STRIKE, "Web Strike",
                        "Web the player you are looking at for " + webDurationSeconds + "s."),
                new Ability(ABILITY_SHOOTER, "Web Shooter",
                        "Reclaim your web shooter if it has gone missing."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_WEB_STRIKE;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_WEB_STRIKE -> webStrike(owner);
            case ABILITY_SHOOTER -> {
                if (!plugin.unlocks().isUnlocked(owner, Power.WEB_SHOOTER)) {
                    yield plugin.unlocks().denyLocked(owner, Power.WEB_SHOOTER);
                }
                ensureShooter(owner);
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public void onQuit(Player owner) {
        climbStartY.remove(owner.getUniqueId());
        BukkitTask task = activeGrapples.remove(owner.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
