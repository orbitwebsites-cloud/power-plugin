package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** I_BL0W_STUFF_UP's explosion kit. */
public final class BitesTheDustKit implements PowerKit, Listener {

    public static final String ID = "bites_the_dust";
    private static final String THROW = "sticky_tnt_throw";
    private static final String DETONATE = "sticky_tnt_detonate";
    private static final String STAB = "stab_shot";
    private static final String NUKE = "nuke_shot";

    private final PowerSMP plugin;
    private final NamespacedKey thrownBombKey;
    private final Map<UUID, Stock> stocks = new ConcurrentHashMap<>();
    private final Map<UUID, List<StickyBomb>> bombs = new ConcurrentHashMap<>();

    private int maxStock = 5;
    private double rechargeSeconds = 6.0d;
    private double emptyRefillSeconds = 30.0d;
    private double fuseSeconds = 1.0d;
    private double stickyRadius = 7.0d;
    private double stickyDamage = 20.0d;
    private double stabRange = 60.0d;
    private double stabRadius = 5.0d;
    private double stabDamage = 24.0d;
    private double stabCooldown = 30.0d;
    private double nukeRadius = 12.0d;
    private double nukeDamage = 36.0d;
    private double nukeCooldown = 45.0d;

    public BitesTheDustKit(PowerSMP plugin) {
        this.plugin = plugin;
        thrownBombKey = new NamespacedKey(plugin, "sticky_tnt_projectile");
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Bites the Dust"; }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            maxStock = Math.max(1, section.getInt("sticky-tnt.max-stock", maxStock));
            rechargeSeconds = Math.max(0.1d, section.getDouble("sticky-tnt.recharge-seconds", rechargeSeconds));
            emptyRefillSeconds = Math.max(0.1d, section.getDouble("sticky-tnt.empty-refill-seconds", emptyRefillSeconds));
            fuseSeconds = Math.max(0.0d, section.getDouble("sticky-tnt.fuse-seconds", fuseSeconds));
            stickyRadius = Math.max(1.0d, section.getDouble("sticky-tnt.radius", stickyRadius));
            stickyDamage = Math.max(0.0d, section.getDouble("sticky-tnt.damage", stickyDamage));
            stabRange = Math.max(1.0d, section.getDouble("stab-shot.range", stabRange));
            stabRadius = Math.max(1.0d, section.getDouble("stab-shot.radius", stabRadius));
            stabDamage = Math.max(0.0d, section.getDouble("stab-shot.damage", stabDamage));
            stabCooldown = Math.max(0.0d, section.getDouble("stab-shot.cooldown-seconds", stabCooldown));
            nukeRadius = Math.max(1.0d, section.getDouble("nuke-shot.radius", nukeRadius));
            nukeDamage = Math.max(0.0d, section.getDouble("nuke-shot.damage", nukeDamage));
            nukeCooldown = Math.max(0.0d, section.getDouble("nuke-shot.cooldown-seconds", nukeCooldown));
        }
        plugin.cooldowns().registerLabel(STAB, "Stab Shot");
        plugin.cooldowns().registerLabel(NUKE, "Nuke Shot");
    }

    @Override
    public void tick(Player owner) {
        Stock stock = stocks.computeIfAbsent(owner.getUniqueId(), ignored -> new Stock(maxStock, 0L));
        refreshStock(owner, stock, true);
        List<StickyBomb> planted = bombs.get(owner.getUniqueId());
        if (planted != null) {
            planted.removeIf(bomb -> !updateBomb(bomb));
        }
    }

    private void refreshStock(Player owner, Stock stock, boolean notify) {
        long now = System.currentTimeMillis();
        if (stock.amount < maxStock && now >= stock.nextAt) {
            if (stock.amount == 0) {
                stock.amount = maxStock;
            } else {
                stock.amount++;
            }
            stock.nextAt = stock.amount < maxStock
                    ? now + Math.round(rechargeSeconds * 1000.0d) : 0L;
            if (notify) {
                Text.actionBar(owner, "<gold>Sticky TNT: <white>" + stock.amount + "/"
                        + maxStock + "</white></gold>");
            }
        }
    }

    /** Zero damage preserves the server's normal explosion knockback. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !plugin.kits().isOwner(player, ID)
                || !plugin.unlocks().isUnlocked(player, Power.BLAST_PROOF)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            event.setDamage(0.0d);
        }
    }

    private boolean throwTnt(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.STICKY_TNT)) {
            return plugin.unlocks().denyLocked(owner, Power.STICKY_TNT);
        }
        Stock stock = stocks.computeIfAbsent(owner.getUniqueId(), ignored -> new Stock(maxStock, 0L));
        // Do not make the player wait for the shared two-second tick to notice a ready charge.
        refreshStock(owner, stock, false);
        if (stock.amount <= 0) {
            long remaining = Math.max(0L, stock.nextAt - System.currentTimeMillis());
            Text.msg(owner, "<red>Sticky TNT stock is empty for another <white>"
                    + Math.max(1L, (remaining + 999L) / 1000L) + "s</white>.</red>");
            return false;
        }
        stock.amount--;
        stock.nextAt = System.currentTimeMillis() + Math.round(
                (stock.amount == 0 ? emptyRefillSeconds : rechargeSeconds) * 1000.0d);
        Snowball projectile = owner.launchProjectile(Snowball.class);
        projectile.setItem(new ItemStack(Material.TNT));
        projectile.setVelocity(owner.getEyeLocation().getDirection().multiply(1.6d));
        projectile.getPersistentDataContainer().set(thrownBombKey, PersistentDataType.STRING,
                owner.getUniqueId().toString());
        Text.actionBar(owner, "<gold>Sticky TNT: <white>" + stock.amount + "/" + maxStock + "</white></gold>");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStickyHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)) return;
        String raw = projectile.getPersistentDataContainer().get(thrownBombKey, PersistentDataType.STRING);
        if (raw == null) return;
        Player owner;
        try { owner = Bukkit.getPlayer(UUID.fromString(raw)); }
        catch (IllegalArgumentException ex) { return; }
        if (owner == null) return;
        Entity attached = event.getHitEntity();
        Location location = attached == null ? projectile.getLocation() : attached.getLocation().add(0, 1, 0);
        ArmorStand marker = location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.getEquipment().setHelmet(new ItemStack(Material.TNT));
        });
        Vector offset = attached == null ? new Vector() : location.toVector().subtract(attached.getLocation().toVector());
        bombs.computeIfAbsent(owner.getUniqueId(), ignored -> new ArrayList<>())
                .add(new StickyBomb(marker.getUniqueId(), attached == null ? null : attached.getUniqueId(), offset));
        owner.playSound(location, Sound.BLOCK_HONEY_BLOCK_PLACE, 0.8f, 0.8f);
    }

    private boolean updateBomb(StickyBomb bomb) {
        Entity markerEntity = Bukkit.getEntity(bomb.markerId);
        if (!(markerEntity instanceof ArmorStand marker) || !marker.isValid()) return false;
        if (bomb.targetId != null) {
            Entity target = Bukkit.getEntity(bomb.targetId);
            if (target == null || !target.isValid()) return true;
            marker.teleport(target.getLocation().add(bomb.offset));
        }
        return true;
    }

    private boolean detonate(Player owner) {
        List<StickyBomb> planted = bombs.get(owner.getUniqueId());
        if (planted == null || planted.isEmpty()) {
            Text.msg(owner, "<red>You have no planted Sticky TNT.</red>");
            return false;
        }
        List<StickyBomb> armed = new ArrayList<>(planted);
        planted.clear();
        Text.actionBar(owner, "<dark_red><bold>DETONATING IN 1 SECOND</bold></dark_red>");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (StickyBomb bomb : armed) {
                Entity marker = Bukkit.getEntity(bomb.markerId);
                if (marker == null) continue;
                Location location = marker.getLocation();
                marker.remove();
                explode(owner, location, stickyRadius, stickyDamage);
            }
        }, Math.max(1L, Math.round(fuseSeconds * 20.0d)));
        return true;
    }

    private boolean stab(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.BITES_THE_DUST))
            return plugin.unlocks().denyLocked(owner, Power.BITES_THE_DUST);
        if (!plugin.cooldowns().tryUse(owner, STAB, stabCooldown)) return false;
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, stabRange);
        double distance = hit == null ? stabRange : hit.getHitPosition().distance(eye.toVector());
        for (double d = 0; d <= distance; d += 0.5d) {
            Location point = eye.clone().add(direction.clone().multiply(d));
            owner.getWorld().spawnParticle(Particle.FLAME, point, 2, 0.08, 0.08, 0.08, 0.0);
            owner.getWorld().spawnParticle(Particle.SMOKE, point, 1, 0.05, 0.05, 0.05, 0.0);
        }
        explode(owner, eye.clone().add(direction.multiply(distance)), stabRadius, stabDamage);
        return true;
    }

    private boolean nuke(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.BITES_THE_DUST))
            return plugin.unlocks().denyLocked(owner, Power.BITES_THE_DUST);
        if (!plugin.cooldowns().tryUse(owner, NUKE, nukeCooldown)) return false;
        explode(owner, owner.getLocation(), nukeRadius, nukeDamage);
        return true;
    }

    private void explode(Player owner, Location center, double radius, double maxDamage) {
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.7f);
        center.getWorld().spawnParticle(Particle.EXPLOSION, center, 12, radius / 3, radius / 4, radius / 3, 0.2);
        center.getWorld().spawnParticle(Particle.FLAME, center, 80, radius / 2, radius / 3, radius / 2, 0.1);
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || target.equals(owner)
                    || !TeamRules.canAffect(owner, target)) continue;
            double distance = target.getLocation().distance(center);
            if (distance > radius) continue;
            double scale = Math.max(0.25d, 1.0d - distance / radius);
            target.damage(maxDamage * scale, owner);
            Vector away = target.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() > 0.001d)
                target.setVelocity(away.normalize().multiply(1.5d * scale).setY(0.5d + scale));
        }
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(THROW, "Throw Sticky TNT", "Throw one bomb; stock: 5."),
                new Ability(DETONATE, "Detonate Sticky TNT", "Explode all planted bombs after 1 second."),
                new Ability(STAB, "Stab Shot", "Fire a straight explosive shot in front of you."),
                new Ability(NUKE, "Nuke Shot", "Call a large explosion directly onto yourself."));
    }

    @Override public String primaryAbilityId() { return THROW; }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case THROW -> throwTnt(owner);
            case DETONATE -> detonate(owner);
            case STAB -> stab(owner);
            case NUKE -> nuke(owner);
            default -> false;
        };
    }

    @Override
    public void onQuit(Player owner) {
        // Planted bombs remain in the world and can be detonated after reconnecting.
    }

    @Override
    public void onDisable() {
        // Never leave invisible marker entities behind after /reload or a plugin replacement.
        for (List<StickyBomb> planted : bombs.values()) {
            for (StickyBomb bomb : planted) {
                Entity marker = Bukkit.getEntity(bomb.markerId);
                if (marker != null) marker.remove();
            }
        }
        bombs.clear();
        stocks.clear();
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power != Power.STICKY_TNT && power != Power.BITES_THE_DUST) return;
        List<StickyBomb> planted = bombs.remove(owner.getUniqueId());
        if (planted != null) {
            for (StickyBomb bomb : planted) {
                Entity marker = Bukkit.getEntity(bomb.markerId);
                if (marker != null) marker.remove();
            }
        }
    }

    private static final class Stock {
        private int amount;
        private long nextAt;
        private Stock(int amount, long nextAt) { this.amount = amount; this.nextAt = nextAt; }
    }

    private record StickyBomb(UUID markerId, UUID targetId, Vector offset) { }
}
