package com.powersmp.mirage;

import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Zero-dependency Mirage backend: invisible armour stands wearing the owner's player head, with the
 * owner's name floating above them.
 *
 * <p>This is the fallback the spec calls a downgrade, and it is. At a glance and at range the heads
 * and nametags read as players; up close they are obviously stands, and they do not walk or fight.
 * They are left destructible on purpose -- a decoy that pops when hit at least costs the attacker a
 * swing, which is the only real decoy value this approach can offer.
 */
public class ArmorStandMirageProvider implements MirageProvider {

    private final Plugin plugin;
    private final Set<UUID> live = ConcurrentHashMap.newKeySet();

    private boolean drift = true;
    private double driftBlocksPerSecond = 0.6d;
    private boolean wearOwnerArmor = true;

    public ArmorStandMirageProvider(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configure(boolean drift, double driftBlocksPerSecond, boolean wearOwnerArmor) {
        this.drift = drift;
        this.driftBlocksPerSecond = driftBlocksPerSecond;
        this.wearOwnerArmor = wearOwnerArmor;
    }

    @Override
    public String name() {
        return "armour stands (fallback -- no real clones)";
    }

    @Override
    public int spawn(Player owner, int count, double radius, int durationTicks) {
        List<ArmorStand> spawned = new ArrayList<>(count);
        ItemStack head = playerHead(owner);

        for (int i = 0; i < count; i++) {
            Location where = scatter(owner.getLocation(), radius);
            ArmorStand stand = owner.getWorld().spawn(where, ArmorStand.class, s -> {
                s.setVisible(false);
                s.setBasePlate(false);
                s.setArms(true);
                s.setGravity(false);
                s.setPersistent(false);
                s.customName(Text.mm("<white>" + Text.plain(owner.getName()) + "</white>"));
                s.setCustomNameVisible(true);
                s.getPersistentDataContainer()
                        .set(Keys.MIRAGE_CLONE, PersistentDataType.STRING, owner.getUniqueId().toString());
                if (s.getEquipment() != null) {
                    s.getEquipment().setHelmet(head);
                    if (wearOwnerArmor) {
                        // An invisible stand wearing visible armour renders as a floating armour set
                        // under a player head -- which is very close to a player silhouette, and far
                        // more convincing at a distance than a bare head on nothing.
                        s.getEquipment().setChestplate(copy(owner.getInventory().getChestplate()));
                        s.getEquipment().setLeggings(copy(owner.getInventory().getLeggings()));
                        s.getEquipment().setBoots(copy(owner.getInventory().getBoots()));
                        s.getEquipment().setItemInMainHand(
                                copy(owner.getInventory().getItemInMainHand()));
                    }
                    // Critical: the decoy wears copies of real gear. Without zeroed drop chances,
                    // breaking a clone would duplicate the owner's armour on the ground.
                    s.getEquipment().setHelmetDropChance(0.0f);
                    s.getEquipment().setChestplateDropChance(0.0f);
                    s.getEquipment().setLeggingsDropChance(0.0f);
                    s.getEquipment().setBootsDropChance(0.0f);
                    s.getEquipment().setItemInMainHandDropChance(0.0f);
                    s.getEquipment().setItemInOffHandDropChance(0.0f);
                }
            });
            spawned.add(stand);
            live.add(stand.getUniqueId());
        }

        BukkitTask driftTask = null;
        if (drift && driftBlocksPerSecond > 0.0d) {
            driftTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> drift(spawned), 10L, 10L);
        }
        BukkitTask finalDriftTask = driftTask;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (finalDriftTask != null) {
                finalDriftTask.cancel();
            }
            for (ArmorStand stand : spawned) {
                remove(stand);
            }
        }, durationTicks);

        return spawned.size();
    }

    /** Nudges each stand a little so they are not obviously statues. */
    private void drift(List<ArmorStand> stands) {
        double step = driftBlocksPerSecond / 2.0d; // task runs twice a second
        for (ArmorStand stand : stands) {
            if (stand == null || !stand.isValid()) {
                continue;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location to = stand.getLocation().clone();
            to.add((random.nextDouble() - 0.5d) * 2.0d * step, 0.0d,
                    (random.nextDouble() - 0.5d) * 2.0d * step);
            to.setYaw(to.getYaw() + (float) ((random.nextDouble() - 0.5d) * 40.0d));
            if (to.getBlock().isPassable()) {
                stand.teleport(to);
            }
        }
    }

    private Location scatter(Location origin, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2.0d;
        double distance = 1.0d + random.nextDouble() * Math.max(0.0d, radius - 1.0d);
        Location where = origin.clone().add(Math.cos(angle) * distance, 0.0d, Math.sin(angle) * distance);
        where.setYaw(origin.getYaw() + (float) ((random.nextDouble() - 0.5d) * 60.0d));
        where.setPitch(0.0f);
        return where;
    }

    /** Clones a piece of the owner's kit for a decoy; the copy is cosmetic and never drops. */
    private ItemStack copy(ItemStack source) {
        return source == null || source.getType().isAir() ? null : source.clone();
    }

    private ItemStack playerHead(Player owner) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            head.setItemMeta(skull);
        }
        return head;
    }

    private void remove(ArmorStand stand) {
        if (stand != null) {
            live.remove(stand.getUniqueId());
            if (stand.isValid()) {
                stand.remove();
            }
        }
    }

    @Override
    public void despawnAll() {
        for (UUID id : live) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        live.clear();
    }
}
