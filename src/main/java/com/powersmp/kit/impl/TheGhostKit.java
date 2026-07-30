package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * The Ghost: Possession, Spectral Body, and Astral Form.
 *
 * <h2>Possession</h2>
 * There is no vanilla way to hand a mob's movement over to a player, so this fakes it: the mob's own
 * AI is switched off, the player becomes invisible in their own body, and a per-tick task teleports
 * the mob to the player's position. The player is, in effect, remote-piloting the mob's position.
 * Left-click, swap-hands, or the explicit Possessed Attack ability makes the mob swing at whatever
 * is nearest it. Firing Possession
 * again releases it and hands the player their body back; so does the mob dying or player leaving.
 *
 * <h2>Spectral Body</h2>
 * Real per-player collision does not exist in the Bukkit API, so this reuses the same lie X-ray
 * tells: solid blocks near the Ghost are sent to him alone as air, then current Paper input is used
 * to apply server-authoritative movement only while his hitbox crosses one of those blocks. The real
 * world is never touched, and the fake blocks follow him and get put back as he moves away. This
 * depends on the {@code powersmp} group's existing {@code nocheatplus.checks.moving.passable}
 * exemption (the same one flight and x-ray already need); without it, NoCheatPlus will flag him for
 * standing inside a block it still thinks is solid.
 *
 * <h2>Astral Form</h2>
 * "Everyone invisible to me, me invisible to everyone" is two independent one-way effects, which
 * potions cannot express -- Invisibility is the same state for every viewer. {@code hidePlayer} /
 * {@code showPlayer} can, since each call only affects one viewer's client, so this simply hides
 * every online player from the Ghost and the Ghost from every online player, both ways, and undoes it
 * on toggle-off, death, or a fresh join on either side.
 *
 * <p>The real {@code Invisibility} potion effect is applied to the Ghost too, alongside the
 * {@code hidePlayer} trick -- purely so two small server plugins (hidewhileinvis, InvisDeaths) that
 * key off that vanilla effect actually notice he's invisible and do their thing (obfuscate his tab
 * list/chat name, obfuscate death messages naming him). {@code hidePlayer} alone would not trigger
 * them; they have no idea PowerSMP exists, only that the effect is there.
 */
public class TheGhostKit implements PowerKit, Listener {

    public static final String ID = "theghost";

    private static final String ABILITY_POSSESS = "possess";
    private static final String ABILITY_POSSESS_ATTACK = "possess_attack";
    private static final String ABILITY_ASTRAL = "astral";
    private static final String COOLDOWN_POSSESS_ATTACK = "possess_attack";

    private final PowerSMP plugin;

    /** ghost -> possessed mob. */
    private final Map<UUID, UUID> possessedMob = new ConcurrentHashMap<>();
    /** mob -> ghost, the reverse lookup, so a mob cannot be possessed twice at once. */
    private final Set<UUID> possessedMobIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> possessedMobHadAi = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> possessedMobTargets = new ConcurrentHashMap<>();
    private final Map<UUID, PossessionMemory> possessionMemory = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> possessionTasks = new ConcurrentHashMap<>();

    /** Ghosts currently in Astral Form. */
    private final Set<UUID> astralActive = ConcurrentHashMap.newKeySet();

    /** Blocks currently faked as air per player, so the real ones can be put back. */
    private final Map<UUID, Set<Location>> fakedBlocks = new ConcurrentHashMap<>();
    /** Per-tick movement drivers active only while a Spectral Body owner is holding movement input. */
    private final Map<UUID, BukkitTask> phaseTasks = new ConcurrentHashMap<>();

    // Possession
    private double possessRange = 20.0d;
    private double possessAttackRange = 3.0d;
    private double possessAttackDamage = 4.0d;
    private double possessAttackCooldown = 1.0d;

    // Spectral Body
    private int phaseScanRadius = 2;
    private Set<Material> impassableBlocks = EnumSet.of(
            Material.OBSIDIAN, Material.BEDROCK, Material.REINFORCED_DEEPSLATE);

    public TheGhostKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "The Ghost";
    }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            ConfigurationSection possession = section.getConfigurationSection("possession");
            if (possession != null) {
                possessRange = possession.getDouble("range", possessRange);
                possessAttackRange = possession.getDouble("attack-range", possessAttackRange);
                possessAttackDamage = possession.getDouble("attack-damage", possessAttackDamage);
                possessAttackCooldown = possession.getDouble("attack-cooldown-seconds", possessAttackCooldown);
            }
            ConfigurationSection spectral = section.getConfigurationSection("spectral-body");
            if (spectral != null) {
                phaseScanRadius = Math.max(1, Math.min(4, spectral.getInt("scan-radius", phaseScanRadius)));
                List<String> names = spectral.getStringList("impassable-blocks");
                if (!names.isEmpty()) {
                    Set<Material> parsed = EnumSet.noneOf(Material.class);
                    for (String name : names) {
                        Material material = Material.matchMaterial(name);
                        if (material != null) {
                            parsed.add(material);
                        }
                    }
                    if (!parsed.isEmpty()) {
                        impassableBlocks = parsed;
                    }
                }
            }
        }
        plugin.cooldowns().registerLabel(COOLDOWN_POSSESS_ATTACK, "Possessed Attack");
    }

    // ---- Possession ---------------------------------------------------

    private boolean possess(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.POSSESSION)) {
            return plugin.unlocks().denyLocked(owner, Power.POSSESSION);
        }
        if (possessedMob.containsKey(owner.getUniqueId())) {
            release(owner, "<gray>You return to your body.</gray>");
            return true;
        }

        Mob target = findPossessTarget(owner, possessRange);
        if (target == null) {
            Text.msg(owner, "<red>No mob in your sights within " + (int) possessRange + " blocks.");
            return false;
        }
        if (possessedMobIds.contains(target.getUniqueId())) {
            Text.msg(owner, "<red>Something already has hold of that one.");
            return false;
        }

        UUID ownerId = owner.getUniqueId();
        possessionMemory.put(ownerId, new PossessionMemory(owner.getGameMode(),
                owner.getLocation().clone(), owner.getPotionEffect(PotionEffectType.INVISIBILITY)));
        possessedMob.put(ownerId, target.getUniqueId());
        possessedMobIds.add(target.getUniqueId());
        possessedMobHadAi.put(target.getUniqueId(), target.hasAI());
        if (target.getTarget() != null) {
            possessedMobTargets.put(target.getUniqueId(), target.getTarget().getUniqueId());
        }

        target.setAI(false);
        target.setTarget(null);
        // Keep the player in their own body: invisibility preserves normal walking controls and
        // prevents spectator flight while the possessed mob mirrors their position.
        Effects.apply(owner, PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0);
        owner.teleport(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!owner.isOnline() || !target.getUniqueId().equals(possessedMob.get(ownerId))) {
                    cancel();
                    return;
                }
                if (target.isDead() || !target.isValid()) {
                    release(owner, "<red>Your host has died -- you return to your body.</red>");
                    cancel();
                    return;
                }
                // The mob is warped to the free-moving spectator body, including its look
                // direction. Unlike setSpectatorTarget(), this leaves WASD and flight input live.
                target.teleport(owner.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }.runTaskTimer(plugin, 1L, 1L);
        possessionTasks.put(ownerId, task);

        Text.msg(owner, "<dark_purple>You possess</dark_purple> <white>"
                + Text.plain(prettifyType(target)) + "</white><dark_purple>.</dark_purple>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
        target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0.0, 1.0, 0.0),
                30, 0.4, 0.6, 0.4, 0.02);
        return true;
    }

    /** Ends a possession and hands the player their body back. {@code message} may be null (offline). */
    private void release(Player owner, String message) {
        UUID ownerId = owner.getUniqueId();
        UUID mobId = possessedMob.remove(ownerId);
        if (mobId != null) {
            possessedMobIds.remove(mobId);
            if (Bukkit.getEntity(mobId) instanceof Mob mob) {
                mob.setAI(possessedMobHadAi.getOrDefault(mobId, true));
                UUID targetId = possessedMobTargets.remove(mobId);
                if (targetId != null && Bukkit.getEntity(targetId) instanceof LivingEntity target
                        && target.isValid() && !target.isDead()) {
                    mob.setTarget(target);
                }
            }
            possessedMobHadAi.remove(mobId);
            possessedMobTargets.remove(mobId);
        }
        BukkitTask task = possessionTasks.remove(ownerId);
        if (task != null) {
            task.cancel();
        }
        PossessionMemory memory = possessionMemory.remove(ownerId);
        if (memory != null) {
            owner.setGameMode(memory.gameMode());
            Effects.remove(owner, PotionEffectType.INVISIBILITY);
            if (memory.previousInvisibility() != null) {
                owner.addPotionEffect(memory.previousInvisibility());
            }
            owner.teleport(memory.location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (owner.isOnline()) {
                owner.getWorld().spawnParticle(Particle.SOUL, owner.getLocation().add(0.0, 1.0, 0.0),
                        20, 0.3, 0.5, 0.3, 0.02);
            }
        }
        if (message != null && owner.isOnline()) {
            Text.msg(owner, message);
        }
    }

    /** A swing while possessing -- spectators cannot throw a real attack, so this fakes one by hand. */
    private boolean possessAttack(Player owner, LivingEntity mob, boolean reportMiss) {
        if (!plugin.cooldowns().tryUseSilently(owner.getUniqueId(), COOLDOWN_POSSESS_ATTACK, possessAttackCooldown)) {
            return false;
        }
        LivingEntity victim = null;
        double closest = possessAttackRange * possessAttackRange;
        for (Entity nearby : mob.getNearbyEntities(possessAttackRange, possessAttackRange, possessAttackRange)) {
            if (nearby.equals(mob) || nearby.equals(owner) || !(nearby instanceof LivingEntity candidate)
                    || !mob.hasLineOfSight(candidate)) {
                continue;
            }
            double distanceSquared = candidate.getLocation().distanceSquared(mob.getLocation());
            if (distanceSquared < closest) {
                closest = distanceSquared;
                victim = candidate;
            }
        }
        if (victim == null) {
            if (reportMiss) {
                Text.actionBar(owner, "<gray>No target within " + possessAttackRange + " blocks.</gray>");
            }
            return false;
        }
        victim.damage(possessAttackDamage, mob);
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 1.8f);
        victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0.0, 1.0, 0.0),
                3, 0.2, 0.2, 0.2, 0.0);
        return true;
    }

    private boolean possessAttack(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.POSSESSION)) {
            return plugin.unlocks().denyLocked(owner, Power.POSSESSION);
        }
        UUID mobId = possessedMob.get(owner.getUniqueId());
        if (mobId == null || !(Bukkit.getEntity(mobId) instanceof LivingEntity mob)
                || mob.isDead() || !mob.isValid()) {
            Text.msg(owner, "<red>You are not possessing a living mob.</red>");
            return false;
        }
        return possessAttack(owner, mob, true);
    }

    private Mob findPossessTarget(Player owner, double range) {
        Vector look = owner.getEyeLocation().getDirection().normalize();
        Location eye = owner.getEyeLocation();
        Mob best = null;
        double bestDot = 0.9d;
        for (Entity entity : owner.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Mob candidate) || !owner.hasLineOfSight(candidate)) {
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

    private String prettifyType(Entity entity) {
        return Text.prettify(entity.getType().name().toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        UUID mobId = possessedMob.get(player.getUniqueId());
        if (mobId == null) {
            return;
        }
        event.setCancelled(true);
        if (Bukkit.getEntity(mobId) instanceof LivingEntity mob) {
            possessAttack(player, mob, false);
        }
    }

    /** Left-click is the natural attack button and remains available in the free spectator body. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPossessedSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        UUID mobId = possessedMob.get(player.getUniqueId());
        if (mobId != null && Bukkit.getEntity(mobId) instanceof LivingEntity mob) {
            possessAttack(player, mob, false);
        }
    }

    // ---- Spectral Body --------------------------------------------------

    /**
     * Starts server-authoritative phasing when movement input begins. Fake air blocks make the wall
     * visually traversable, but they do not change server collision; teleporting only the small
     * portion of a movement step that intersects a phaseable block is what makes the passive real.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Player owner = event.getPlayer();
        if (!hasMovement(event.getInput()) || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.SPECTRAL_BODY)
                || possessedMob.containsKey(owner.getUniqueId())) {
            return;
        }
        startPhaseTask(owner);
    }

    private void startPhaseTask(Player owner) {
        UUID ownerId = owner.getUniqueId();
        if (phaseTasks.containsKey(ownerId)) {
            return;
        }
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!owner.isOnline() || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.SPECTRAL_BODY)) {
                    phaseTasks.remove(ownerId);
                    cancel();
                    return;
                }
                // Keep one lightweight driver alive for the owner. Input events report state
                // changes, not every held-key tick, so cancelling while idle can miss the first
                // server-corrected movement packet at a wall.
                if (owner.isDead() || possessedMob.containsKey(ownerId)
                        || owner.getGameMode() == GameMode.SPECTATOR
                        || !hasMovement(owner.getCurrentInput())) {
                    return;
                }
                phaseStep(owner, owner.getCurrentInput());
            }
        }.runTaskTimer(plugin, 1L, 1L);
        phaseTasks.put(ownerId, task);
    }

    private void phaseStep(Player owner, Input input) {
        Vector forward = owner.getLocation().getDirection();
        forward.setY(0.0d);
        if (forward.lengthSquared() < 1.0e-6) {
            forward.setZ(1.0d);
        } else {
            forward.normalize();
        }
        Vector right = new Vector(-forward.getZ(), 0.0d, forward.getX());
        Vector movement = new Vector();
        if (input.isForward()) {
            movement.add(forward);
        }
        if (input.isBackward()) {
            movement.subtract(forward);
        }
        if (input.isRight()) {
            movement.add(right);
        }
        if (input.isLeft()) {
            movement.subtract(right);
        }
        if (movement.lengthSquared() > 1.0e-6) {
            double speed = input.isSneak() ? 0.12d : input.isSprint() ? 0.32d : 0.22d;
            movement.normalize().multiply(speed);
        }
        if (input.isJump()) {
            movement.setY(0.22d);
        } else if (input.isSneak()) {
            movement.setY(-0.22d);
        }
        if (movement.lengthSquared() < 1.0e-6) {
            return;
        }

        BoundingBox currentBox = owner.getBoundingBox();
        BoundingBox nextBox = currentBox.clone().shift(movement);
        int currentCollision = collisionState(owner, currentBox);
        int nextCollision = collisionState(owner, nextBox);
        if (nextCollision == 2) {
            Text.actionBar(owner, "<dark_gray>That material resists your Spectral Body.</dark_gray>");
            return;
        }
        // Open-air movement remains vanilla. We intervene only while entering, crossing, or
        // leaving an actual solid block, which avoids turning normal walking into teleport spam.
        if (currentCollision == 1 || nextCollision == 1) {
            Location next = owner.getLocation().add(movement);
            owner.teleport(next, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /**
     * @return 0 for clear, 1 for phaseable solid, 2 for a configured impassable material.
     */
    private int collisionState(Player owner, BoundingBox box) {
        final double epsilon = 1.0e-5;
        int minX = (int) Math.floor(box.getMinX() + epsilon);
        int maxX = (int) Math.floor(box.getMaxX() - epsilon);
        int minY = (int) Math.floor(box.getMinY() + epsilon);
        int maxY = (int) Math.floor(box.getMaxY() - epsilon);
        int minZ = (int) Math.floor(box.getMinZ() + epsilon);
        int maxZ = (int) Math.floor(box.getMaxZ() - epsilon);
        boolean solid = false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = owner.getWorld().getBlockAt(x, y, z);
                    // isPassable alone treats every stair/slab as a full cube. The shape overlap
                    // keeps ordinary movement over partial blocks from accidentally engaging
                    // phasing when the player's real hitbox is not touching their solid portion.
                    // Paper exposes this VoxelShape in coordinates local to the block (0..1),
                    // while an entity BoundingBox is in world coordinates. Compare like with like.
                    BoundingBox localBox = box.clone().shift(-x, -y, -z);
                    if (block.isPassable() || !block.getCollisionShape().overlaps(localBox)) {
                        continue;
                    }
                    if (impassableBlocks.contains(block.getType())) {
                        return 2;
                    }
                    solid = true;
                }
            }
        }
        return solid ? 1 : 0;
    }

    private static boolean hasMovement(Input input) {
        return input != null && (input.isForward() || input.isBackward() || input.isLeft()
                || input.isRight() || input.isJump() || input.isSneak());
    }

    private void stopPhaseTask(Player owner) {
        BukkitTask task = phaseTasks.remove(owner.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Rebuilds the "phase bubble" around the Ghost every time he moves: solid, non-impassable blocks
     * nearby are faked as air to him alone; anything faked earlier that is now out of range gets its
     * real block sent back. Tied to movement rather than the shared once-a-second tick for the same
     * reason wall-climbing had to be -- a slow refresh cannot keep up with someone actually walking.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player owner = event.getPlayer();
        if (!plugin.kits().isOwner(owner, ID) || !plugin.unlocks().isUnlocked(owner, Power.SPECTRAL_BODY)) {
            return;
        }
        // PlayerMoveEvent also fires on a pure camera turn with the feet planted. Rescanning a
        // (2*radius+1)^3 block box -- up to 729 blocks at the max radius -- on every single one of
        // those, for a power that is always on once unlocked, is exactly the kind of thing that reads
        // as constant server lag rather than a one-off cost. Only an actual position change needs a
        // rescan; looking around is free.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        updatePhaseBubble(owner);
    }

    private void updatePhaseBubble(Player owner) {
        Set<Location> faked = fakedBlocks.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>());
        Set<Location> shouldBeFaked = new HashSet<>();
        Block center = owner.getLocation().getBlock();

        for (int x = -phaseScanRadius; x <= phaseScanRadius; x++) {
            for (int y = -phaseScanRadius; y <= phaseScanRadius; y++) {
                for (int z = -phaseScanRadius; z <= phaseScanRadius; z++) {
                    Block block = center.getRelative(x, y, z);
                    if (block.isPassable() || impassableBlocks.contains(block.getType())) {
                        continue;
                    }
                    shouldBeFaked.add(block.getLocation());
                }
            }
        }

        org.bukkit.block.data.BlockData air = Bukkit.createBlockData(Material.AIR);
        for (Location location : shouldBeFaked) {
            if (faked.add(location)) {
                owner.sendBlockChange(location, air);
                // Only on blocks newly entered, not every block already in the bubble -- otherwise
                // standing still near a wall would spam particles every single move event.
                owner.getWorld().spawnParticle(Particle.SOUL, location.clone().add(0.5, 0.5, 0.5),
                        2, 0.2, 0.2, 0.2, 0.0);
            }
        }
        faked.removeIf(location -> {
            if (shouldBeFaked.contains(location)) {
                return false;
            }
            owner.sendBlockChange(location, location.getBlock().getBlockData());
            return true;
        });
    }

    private void restorePhaseBubble(Player owner) {
        Set<Location> faked = fakedBlocks.remove(owner.getUniqueId());
        if (faked == null || !owner.isOnline()) {
            return;
        }
        for (Location location : faked) {
            owner.sendBlockChange(location, location.getBlock().getBlockData());
        }
    }

    /** A ghost floating through terrain should not suffocate in it or take fall damage phasing through a floor. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.SPECTRAL_BODY)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.SUFFOCATION || cause == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    // ---- Astral Form ------------------------------------------------------

    private boolean toggleAstral(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.ASTRAL_FORM)) {
            return plugin.unlocks().denyLocked(owner, Power.ASTRAL_FORM);
        }
        UUID id = owner.getUniqueId();
        if (astralActive.remove(id)) {
            setMutualVisibility(owner, true);
            Effects.remove(owner, PotionEffectType.INVISIBILITY);
            Text.msg(owner, "<gray>You return to the material world.</gray>");
            owner.playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.9f);
            owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0.0, 1.0, 0.0),
                    25, 0.4, 0.6, 0.4, 0.03);
            return true;
        }
        astralActive.add(id);
        setMutualVisibility(owner, false);
        // hidePlayer already does the actual hiding; this is only so hidewhileinvis/InvisDeaths --
        // neither of which know PowerSMP exists -- see the standard effect and act on it too.
        Effects.applyInfinite(owner, PotionEffectType.INVISIBILITY, 0);
        Text.msg(owner, "<dark_purple>You slip into the astral plane.</dark_purple> "
                + "<gray>No one can see you, and you can see no one.</gray>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.4f);
        owner.getWorld().spawnParticle(Particle.REVERSE_PORTAL, owner.getLocation().add(0.0, 1.0, 0.0),
                40, 0.4, 0.6, 0.4, 0.05);
        return true;
    }

    /** {@code visible = true} undoes it; {@code false} hides the Ghost and everyone else from each other. */
    private void setMutualVisibility(Player owner, boolean visible) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(owner)) {
                continue;
            }
            if (visible) {
                other.showPlayer(plugin, owner);
                owner.showPlayer(plugin, other);
            } else {
                other.hidePlayer(plugin, owner);
                owner.hidePlayer(plugin, other);
            }
        }
    }

    /** Covers someone joining while a Ghost is already in Astral Form -- both sides start hidden. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOtherJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(joined) || !plugin.kits().isOwner(online, ID)
                    || !astralActive.contains(online.getUniqueId())) {
                continue;
            }
            online.hidePlayer(plugin, joined);
            joined.hidePlayer(plugin, online);
        }
    }

    /**
     * Dying while astral would otherwise respawn him permanently invisible to everyone.
     *
     * <p>{@code MONITOR} is deliberate here, not just habit: hidewhileinvis and InvisDeaths (see the
     * class doc) both read {@code PotionEffectType.INVISIBILITY} off this same
     * {@link PlayerDeathEvent} at their own, earlier priority to decide whether to obfuscate the
     * death message. Stripping the effect any sooner would erase it out from under them before they
     * get to look.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (possessedMob.containsKey(player.getUniqueId())) {
            release(player, null);
        }
        if (astralActive.remove(player.getUniqueId())) {
            setMutualVisibility(player, true);
            Effects.remove(player, PotionEffectType.INVISIBILITY);
        }
    }

    // ---- abilities ---------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_POSSESS, "Possession",
                        "Take control of the mob you are looking at. Left-click or use the attack ability, "
                                + "use again to let go."),
                new Ability(ABILITY_POSSESS_ATTACK, "Possessed Attack",
                        "Make your current host attack the nearest living target."),
                new Ability(ABILITY_ASTRAL, "Astral Form",
                        "Toggle mutual invisibility with every other player."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_POSSESS;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_POSSESS -> possess(owner);
            case ABILITY_POSSESS_ATTACK -> possessAttack(owner);
            case ABILITY_ASTRAL -> toggleAstral(owner);
            default -> false;
        };
    }

    @Override
    public void onJoin(Player owner) {
        // Astral state is intentionally session-only, but the vanilla effect is persistent.
        astralActive.remove(owner.getUniqueId());
        setMutualVisibility(owner, true);
        Effects.remove(owner, PotionEffectType.INVISIBILITY);
        restorePhaseBubble(owner);
        startPhaseTask(owner);
    }

    @Override
    public void onQuit(Player owner) {
        if (possessedMob.containsKey(owner.getUniqueId())) {
            release(owner, null);
        }
        if (astralActive.remove(owner.getUniqueId())) {
            setMutualVisibility(owner, true);
            Effects.remove(owner, PotionEffectType.INVISIBILITY);
        }
        stopPhaseTask(owner);
        restorePhaseBubble(owner);
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.POSSESSION && possessedMob.containsKey(owner.getUniqueId())) {
            release(owner, "<gray>The possession ends.</gray>");
        } else if (power == Power.SPECTRAL_BODY) {
            stopPhaseTask(owner);
            restorePhaseBubble(owner);
        } else if (power == Power.ASTRAL_FORM && astralActive.remove(owner.getUniqueId())) {
            setMutualVisibility(owner, true);
            Effects.remove(owner, PotionEffectType.INVISIBILITY);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.kits().isOwner(player, ID)) {
                continue;
            }
            if (possessedMob.containsKey(player.getUniqueId())) {
                release(player, null);
            }
            if (astralActive.remove(player.getUniqueId())) {
                setMutualVisibility(player, true);
                Effects.remove(player, PotionEffectType.INVISIBILITY);
            }
            stopPhaseTask(player);
            restorePhaseBubble(player);
        }
    }

    /** What a possession has to hand back when it ends. */
    private record PossessionMemory(GameMode gameMode, Location location,
                                    PotionEffect previousInvisibility) {
    }
}
