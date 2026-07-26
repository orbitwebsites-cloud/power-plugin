package com.powersmp.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Generic "hold this entity still" service, shared by The World, the spear's stun, and anything
 * else that needs one.
 *
 * <p><b>How honest this is.</b> Mobs freeze cleanly: AI off, velocity zeroed. Players do not have a
 * server-side freeze in vanilla, so this stacks four things: cancel positional
 * {@link PlayerMoveEvent}s, pin velocity to zero, apply a huge Slowness plus a negative Jump Boost
 * (which is what actually stops the client predicting movement, and is why this looks far less
 * janky than move-cancelling alone), and cancel the actions a frozen player should not be able to
 * take -- dealing or taking damage, using items, breaking or placing blocks, dropping items, firing
 * projectiles, opening containers.
 *
 * <p>Frozen players will still see some stutter and can still look around, chat, and swing their
 * arm. That is a limit of client-side movement prediction, not a bug here -- the client moves first
 * and asks permission afterwards, and every rejection is a visible correction.
 */
public class FreezeUtil implements Listener {

    /** Amplifier at which Jump Boost becomes a jump *penalty*, pinning the player to the ground. */
    private static final int NEGATIVE_JUMP_AMPLIFIER = 128;
    private static final int IMMOBILISING_SLOWNESS = 250;
    private static final float DEFAULT_WALK_SPEED = 0.2f;
    private static final float DEFAULT_FLY_SPEED = 0.1f;
    /** Safety net: if a player somehow drifts further than this, snap them back. */
    private static final double DRIFT_TOLERANCE_SQUARED = 1.0d;

    private final Plugin plugin;
    private final Map<UUID, Frozen> frozen = new ConcurrentHashMap<>();
    private BukkitTask task;

    public FreezeUtil(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Idempotent -- safe to call again after a reload has cancelled the plugin's tasks. */
    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            unfreeze(id);
        }
    }

    public boolean isFrozen(UUID id) {
        return frozen.containsKey(id);
    }

    /**
     * Freezes for {@code durationTicks} with damage blocked in both directions -- "time is stopped",
     * which is what The World wants.
     */
    public void freeze(Entity entity, long durationTicks) {
        freeze(entity, durationTicks, true);
    }

    /**
     * Freezes for {@code durationTicks}. Re-freezing an already-frozen entity extends it.
     *
     * @param blockIncomingDamage true for a time-stop, where the target cannot be hurt either. False
     *     for a combat stun like the spear's -- there, the target must stay hittable, or the stun
     *     would protect the person it lands on, which is exactly backwards. A frozen entity can
     *     never <em>deal</em> damage regardless of this flag.
     */
    public void freeze(Entity entity, long durationTicks, boolean blockIncomingDamage) {
        if (entity == null || entity.isDead()) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + durationTicks * 50L;
        Frozen existing = frozen.get(entity.getUniqueId());
        if (existing != null) {
            existing.expiresAt = Math.max(existing.expiresAt, expiresAt);
            existing.blockIncomingDamage |= blockIncomingDamage;
            return;
        }

        boolean hadAi = true;
        if (entity instanceof Mob mob) {
            hadAi = mob.hasAI();
            mob.setAI(false);
            mob.setTarget(null);
        }
        Frozen record = new Frozen(entity.getLocation().clone(), expiresAt, hadAi, blockIncomingDamage);
        frozen.put(entity.getUniqueId(), record);
        entity.setVelocity(new Vector(0, 0, 0));

        if (entity instanceof Player player) {
            // The important part. Zeroing walk and fly speed tells the *client* it cannot move, so
            // it never predicts movement we then have to reject. Move-cancelling alone is what
            // causes rubber-banding; this removes most of it.
            record.walkSpeed = player.getWalkSpeed();
            record.flySpeed = player.getFlySpeed();
            player.setWalkSpeed(0.0f);
            player.setFlySpeed(0.0f);
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) durationTicks + 5, IMMOBILISING_SLOWNESS, true, false, false));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.JUMP_BOOST, (int) durationTicks + 5, NEGATIVE_JUMP_AMPLIFIER, true, false, false));
        } else {
            // Arrows, fireballs, thrown potions: zero velocity alone just makes them drop. Killing
            // gravity too actually suspends them mid-flight, which is what a time-stop should look
            // like.
            record.hadGravity = entity.hasGravity();
            entity.setGravity(false);
        }
    }

    public void freezeSeconds(Entity entity, double seconds) {
        freeze(entity, Math.round(seconds * 20.0d), true);
    }

    /** Combat stun: cannot move or act, but stays hittable. */
    public void stunSeconds(Entity entity, double seconds) {
        freeze(entity, Math.round(seconds * 20.0d), false);
    }

    public void unfreeze(UUID id) {
        Frozen record = frozen.remove(id);
        if (record == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) {
            return;
        }
        if (entity instanceof Mob mob) {
            mob.setAI(record.hadAi);
        }
        if (entity instanceof Player player) {
            player.setWalkSpeed(record.walkSpeed);
            player.setFlySpeed(record.flySpeed);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        } else {
            entity.setGravity(record.hadGravity);
        }
    }

    private void tick() {
        if (frozen.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Frozen> entry : frozen.entrySet()) {
            if (entry.getValue().expiresAt <= now) {
                expired.add(entry.getKey());
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null || entity.isDead()) {
                expired.add(entry.getKey());
                continue;
            }
            entity.setVelocity(new Vector(0, 0, 0));
            if (entity.getFallDistance() > 0.0f) {
                entity.setFallDistance(0.0f);
            }
            // Hold the fuse: primed TNT caught in a time-stop should not go off during it.
            if (entity instanceof TNTPrimed tnt) {
                tnt.setFuseTicks(tnt.getFuseTicks() + 1);
            }
            Location anchor = entry.getValue().anchor;
            Location current = entity.getLocation();
            if (anchor.getWorld() != null && anchor.getWorld().equals(current.getWorld())
                    && current.distanceSquared(anchor) > DRIFT_TOLERANCE_SQUARED) {
                // Keep their facing -- only the position is being corrected.
                Location corrected = anchor.clone();
                corrected.setYaw(current.getYaw());
                corrected.setPitch(current.getPitch());
                entity.teleport(corrected);
            }
        }
        for (UUID id : expired) {
            unfreeze(id);
        }
    }

    // ---- event blocking -------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        // Looking around is allowed; moving is not.
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (blocksIncomingDamage(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        // A frozen attacker never lands a hit; a frozen victim is only protected in a time-stop.
        if (isFrozen(damager.getUniqueId()) || blocksIncomingDamage(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean blocksIncomingDamage(UUID id) {
        Frozen record = frozen.get(id);
        return record != null && record.blockIncomingDamage;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof LivingEntity shooter
                && isFrozen(shooter.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Do not let someone log out frozen and come back frozen forever. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        unfreeze(event.getPlayer().getUniqueId());
    }

    /**
     * Crash recovery. Walk speed is persisted in player data, so a server that died mid-freeze would
     * otherwise leave someone permanently unable to move. Nothing else legitimately sets it to
     * exactly zero, so a zero on join with no active freeze means we left it there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isFrozen(player.getUniqueId())) {
            return;
        }
        if (player.getWalkSpeed() == 0.0f) {
            player.setWalkSpeed(DEFAULT_WALK_SPEED);
            plugin.getLogger().info("Restored walk speed for " + player.getName()
                    + " (left at zero by an unclean shutdown during a freeze).");
        }
        if (player.getFlySpeed() == 0.0f) {
            player.setFlySpeed(DEFAULT_FLY_SPEED);
        }
    }

    private static final class Frozen {
        private final Location anchor;
        private final boolean hadAi;
        private long expiresAt;
        private boolean blockIncomingDamage;
        /** Restored on unfreeze -- players only. */
        private float walkSpeed = DEFAULT_WALK_SPEED;
        private float flySpeed = DEFAULT_FLY_SPEED;
        /** Restored on unfreeze -- non-players only. */
        private boolean hadGravity = true;

        private Frozen(Location anchor, long expiresAt, boolean hadAi, boolean blockIncomingDamage) {
            this.anchor = anchor;
            this.expiresAt = expiresAt;
            this.hadAi = hadAi;
            this.blockIncomingDamage = blockIncomingDamage;
        }
    }
}
