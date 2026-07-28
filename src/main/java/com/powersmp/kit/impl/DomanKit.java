package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
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
    /** Web shooters pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    // Low tier
    private int resistanceAmplifier;
    private boolean noFallDamage = true;
    /**
     * Cobweb slow is friction applied every tick a hitbox overlaps a cobweb block -- there is no
     * potion effect or attribute that removes it (WEAVING does not; it is the Bogged debuff that
     * spawns cobwebs around whoever it hits, the opposite of what was wanted, and was actively
     * harmful when self-applied). The only way to cancel friction the server already applied is to
     * push back against it, every tick, for as long as the block underneath is still a cobweb.
     */
    private double webImmunityVelocityMultiplier = 5.0d;
    // Mid tier
    private int webDurationSeconds = 60;
    private double webRange = 32.0d;
    private double webStrikeCooldown = 60.0d;
    private int climbLimit = 20;
    private double climbSpeed = 0.2d;
    // High tier
    private double grappleRange = 32.0d;
    private double grapplePower = 1.4d;
    private int grapplePulseTicks = 40;
    private double pullRange = 24.0d;
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
                webImmunityVelocityMultiplier =
                        passive.getDouble("web-immunity-velocity-multiplier", webImmunityVelocityMultiplier);
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
        if (!plugin.kits().isOwner(owner, ID)) {
            return;
        }
        if (plugin.unlocks().isUnlocked(owner, Power.SPIDER_PASSIVE)) {
            cancelWebSlow(owner);
        }
        if (!plugin.unlocks().isUnlocked(owner, Power.WEB_STRIKE)) {
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

    /**
     * Pushes back against whatever cobweb friction the server just applied. Checks feet and head so
     * a cobweb anywhere in the hitbox counts, not only the block exactly at the player's feet.
     */
    private void cancelWebSlow(Player owner) {
        Location at = owner.getLocation();
        boolean inWeb = at.getBlock().getType() == Material.COBWEB
                || at.clone().add(0, 1, 0).getBlock().getType() == Material.COBWEB;
        if (!inWeb) {
            return;
        }
        owner.setVelocity(owner.getVelocity().multiply(webImmunityVelocityMultiplier));
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
        target.getWorld().spawnParticle(Particle.ITEM_COBWEB, target.getLocation().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.05);
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
            Enchants.applyVanishing(meta);
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

    // ---- "kept on death", for real this time ----------------------------
    // The lore already claimed this, but only onDrop existed -- death and chest storage were
    // both unprotected, so the shooter dropped on death like any other item and ensureShooter()
    // would then hand out a second one on rejoin. Mirrors techknight's mace.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (isShooter(drop)) {
                deathStash.put(player.getUniqueId(), drop.clone());
                it.remove();
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ItemStack stashed = deathStash.remove(player.getUniqueId());
        // Curse of Vanishing means there is usually nothing to restore -- ensureShooter() is the
        // fallback that actually hands it back in that case.
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                if (plugin.unlocks().isUnlocked(player, Power.WEB_SHOOTER)) {
                    ensureShooter(player);
                }
                return;
            }
            HashMap<Integer, ItemStack> leftover = new HashMap<>(player.getInventory().addItem(stashed));
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stashed);
            }
            Text.msg(player, "<gray>Your web shooter came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (isShooter(event.getCurrentItem()) || isShooter(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING && isShooter(event.getOldCursor())) {
            event.setCancelled(true);
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
     *
     * <p>If a player is in his sights, the grapple locks onto <em>them</em> and follows them every
     * pulse -- {@code getTargetBlockExact} only ever sees blocks, so aiming at someone standing on a
     * hill used to grapple to the hill under their feet instead of the person. Only when nobody is
     * in his sights does it fall back to a fixed block.
     */
    private void grapple(Player owner) {
        Player targetPlayer = nearestLookedAt(owner, grappleRange);
        Location fixedAnchor = null;
        if (targetPlayer == null) {
            Block block = owner.getTargetBlockExact((int) grappleRange);
            if (block == null) {
                Text.actionBar(owner, "<gray>Nothing in range to grapple to.</gray>");
                return;
            }
            fixedAnchor = block.getLocation().add(0.5d, 0.5d, 0.5d);
        }
        final Location anchor = fixedAnchor;
        final Player target = targetPlayer;

        BukkitTask previous = activeGrapples.remove(owner.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 1.4f);

        int[] elapsed = {0};
        // Bukkit's scheduler only takes a plain Runnable -- there is no self-referencing lambda
        // overload -- so a task that needs to cancel itself has to be a BukkitRunnable subclass.
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (elapsed[0]++ >= grapplePulseTicks || !owner.isOnline()
                        || (target != null && (!target.isOnline() || target.isDead()))) {
                    cancel();
                    activeGrapples.remove(owner.getUniqueId());
                    return;
                }
                Location currentAnchor = target != null ? target.getEyeLocation() : anchor;
                Location from = owner.getEyeLocation();
                Vector to = currentAnchor.toVector().subtract(from.toVector());
                double distance = to.length();
                drawLine(from, currentAnchor, Particle.SMOKE);
                if (distance < 1.5d) {
                    cancel();
                    activeGrapples.remove(owner.getUniqueId());
                    return;
                }
                owner.setVelocity(to.normalize().multiply(grapplePower));
                owner.setFallDistance(0.0f);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        activeGrapples.put(owner.getUniqueId(), task);
    }

    /**
     * Same pulsed-pull fix as {@link #grapple}: one packet is too easy for friction to cancel out.
     *
     * <p>Previously grabbed <em>every</em> player in {@code pullRange}, which is not "click on the
     * player" at all -- it pulled bystanders through walls and gave no way to choose a target. Now
     * uses the same crosshair lock {@link #nearestLookedAt} already gives the grapple.
     */
    private void reelIn(Player owner) {
        Player target = nearestLookedAt(owner, pullRange);
        if (target == null) {
            Text.actionBar(owner, "<gray>No player in your sights to reel in.</gray>");
            return;
        }
        if (!plugin.cooldowns().tryUse(owner, COOLDOWN_PULL, pullCooldown)) {
            return;
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f);
        Text.actionBar(owner, "<gray>Reeling in " + Text.plain(target.getName()) + "</gray>");

        int[] elapsed = {0};
        // Single locked target, not everyone in range: reelIn() used to pull every player within
        // pullRange indiscriminately, which is not "click on the player" at all. The scheduler only
        // takes a plain Runnable, so a self-cancelling task has to be a BukkitRunnable subclass.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (elapsed[0]++ >= pullPulseTicks || !owner.isOnline()
                        || !target.isOnline() || target.isDead()) {
                    cancel();
                    return;
                }
                Vector to = owner.getLocation().toVector().subtract(target.getLocation().toVector());
                if (to.lengthSquared() < 1.0d) {
                    cancel();
                    return;
                }
                drawLine(target.getEyeLocation(), owner.getEyeLocation(), Particle.SMOKE);
                Vector pull = to.normalize().multiply(pullPower);
                pull.setY(Math.max(0.3d, pull.getY()));
                target.setVelocity(pull);
            }
        }.runTaskTimer(plugin, 0L, 1L);
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
