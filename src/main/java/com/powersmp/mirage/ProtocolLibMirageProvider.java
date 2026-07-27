package com.powersmp.mirage;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Real Mirage clones: actual player entities, with the owner's skin, sent to clients as packets.
 *
 * <p>These are client-side only. There is no entity on the server, which is what makes them cheap
 * and lets them wear the owner's real skin -- but it also means everything a server entity gets for
 * free has to be done here: position updates are sent explicitly, and "attackable" is implemented by
 * listening for the client's {@code USE_ENTITY} packet and popping the clone when its id is hit.
 *
 * <p><b>Fragility, stated plainly.</b> Packet layouts are the least stable surface in the game --
 * {@code PLAYER_INFO} changed shape in 1.19.3 and again in 1.20.2, and entity teleport changed in
 * 1.21.2. This class was written against ProtocolLib 5.3.0 but could not be compiled or
 * run in the environment that produced it. Every packet operation is therefore wrapped: the first
 * failure logs the cause once and permanently marks this provider unhealthy, and
 * {@link com.powersmp.kit.impl.MonkeyManKit} falls back to armour stands rather than spamming the
 * console or leaving invisible ghosts behind. If Mirage silently degrades in game, the server log
 * names the exact field that moved.
 */
public class ProtocolLibMirageProvider implements MirageProvider {

    /**
     * Fake entity ids count down from here. Real ids are handed out counting up from small numbers,
     * so starting near the top keeps the two apart for the life of any realistic server.
     */
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(Integer.MAX_VALUE - 1);
    /** Entity metadata index of the skin-layer bitmask on 1.9+ -- 0x7F enables every layer. */
    private static final int SKIN_LAYERS_INDEX = 17;
    private static final byte ALL_SKIN_LAYERS = 0x7F;

    private final Plugin plugin;
    private final ProtocolManager protocol;
    private final Map<Integer, Clone> clones = new ConcurrentHashMap<>();
    private volatile boolean healthy = true;
    private PacketAdapter hitListener;

    public ProtocolLibMirageProvider(Plugin plugin) {
        this.plugin = plugin;
        this.protocol = ProtocolLibrary.getProtocolManager();
        registerHitListener();
    }

    @Override
    public String name() {
        return healthy ? "ProtocolLib (real clones)" : "ProtocolLib (failed -- see log)";
    }

    public boolean isHealthy() {
        return healthy;
    }

    /** One-way latch: a packet layout we cannot write means this backend is done for this run. */
    private void fail(String what, Throwable cause) {
        if (healthy) {
            healthy = false;
            plugin.getLogger().log(Level.WARNING, "Mirage's ProtocolLib backend failed at '" + what
                    + "' and has been disabled for this session; Mirage will use armour stands "
                    + "instead. This usually means a packet field moved in a Minecraft or "
                    + "ProtocolLib update.", cause);
            despawnAll();
        }
    }

    // ---- spawning -------------------------------------------------------

    @Override
    public int spawn(Player owner, int count, double radius, int durationTicks) {
        if (!healthy) {
            return 0;
        }
        List<Clone> spawned = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Clone clone = spawnOne(owner, scatter(owner.getLocation(), radius));
            if (clone == null) {
                break;
            }
            spawned.add(clone);
        }
        if (spawned.isEmpty()) {
            return 0;
        }

        BukkitTask walker = Bukkit.getScheduler().runTaskTimer(plugin, () -> walk(spawned), 10L, 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            walker.cancel();
            for (Clone clone : spawned) {
                despawn(clone, false);
            }
        }, durationTicks);

        return spawned.size();
    }

    private Clone spawnOne(Player owner, Location where) {
        int entityId = NEXT_ENTITY_ID.decrementAndGet();
        UUID cloneId = UUID.randomUUID();
        Clone clone = new Clone(entityId, cloneId, owner.getUniqueId(), where);

        try {
            WrappedGameProfile profile = new WrappedGameProfile(cloneId, owner.getName());
            // The skin. Paper hands us the owner's signed texture property directly, so the clone
            // renders with their real skin rather than a default one.
            for (ProfileProperty property : owner.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    profile.getProperties().put("textures", new WrappedSignedProperty(
                            "textures", property.getValue(), property.getSignature()));
                }
            }

            sendPlayerInfoAdd(owner, profile);
            sendSpawn(owner, clone);
            sendSkinLayers(owner, entityId);
        } catch (Throwable ex) {
            fail("spawn", ex);
            return null;
        }

        clones.put(entityId, clone);

        // Drop the clone off the tab list shortly after spawning. It has to be listed briefly or
        // the client discards the skin, but leaving it there would show phantom players online.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (clone.alive) {
                sendPlayerInfoRemove(owner, cloneId);
            }
        }, 40L);

        return clone;
    }

    private void sendPlayerInfoAdd(Player viewer, WrappedGameProfile profile) throws Exception {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED));
        PlayerInfoData data = new PlayerInfoData(
                profile.getUUID(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL, profile,
                WrappedChatComponent.fromText(profile.getName()));
        // Index 1 is the 1.19.3+ list; index 0 is the legacy one ProtocolLib keeps for old servers.
        packet.getPlayerInfoDataLists().write(1, List.of(data));
        protocol.sendServerPacket(viewer, packet);
    }

    private void sendPlayerInfoRemove(Player viewer, UUID cloneId) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getUUIDLists().write(0, List.of(cloneId));
            protocol.sendServerPacket(viewer, packet);
        } catch (Throwable ex) {
            fail("player-info-remove", ex);
        }
    }

    private void sendSpawn(Player viewer, Clone clone) throws Exception {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, clone.entityId);
        packet.getUUIDs().write(0, clone.uuid);
        packet.getEntityTypeModifier().write(0, EntityType.PLAYER);
        packet.getDoubles().write(0, clone.location.getX());
        packet.getDoubles().write(1, clone.location.getY());
        packet.getDoubles().write(2, clone.location.getZ());
        packet.getBytes().write(0, angleToByte(clone.location.getPitch()));
        packet.getBytes().write(1, angleToByte(clone.location.getYaw()));
        protocol.sendServerPacket(viewer, packet);
    }

    private void sendSkinLayers(Player viewer, int entityId) throws Exception {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, entityId);
        WrappedDataValue layers = new WrappedDataValue(
                SKIN_LAYERS_INDEX,
                WrappedDataWatcher.Registry.get(Byte.class),
                ALL_SKIN_LAYERS);
        packet.getDataValueCollectionModifier().write(0, List.of(layers));
        protocol.sendServerPacket(viewer, packet);
    }

    // ---- movement -------------------------------------------------------

    /** Wanders each clone a short distance and turns it to face the way it moved. */
    private void walk(List<Clone> group) {
        if (!healthy) {
            return;
        }
        for (Clone clone : group) {
            if (!clone.alive) {
                continue;
            }
            Player owner = Bukkit.getPlayer(clone.owner);
            if (owner == null) {
                continue;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double dx = (random.nextDouble() - 0.5d) * 1.6d;
            double dz = (random.nextDouble() - 0.5d) * 1.6d;
            Location next = clone.location.clone().add(dx, 0.0d, dz);
            if (!next.getBlock().isPassable()) {
                continue;
            }
            next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            clone.location = next;

            try {
                PacketContainer teleport = protocol.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
                teleport.getIntegers().write(0, clone.entityId);
                teleport.getDoubles().write(0, next.getX());
                teleport.getDoubles().write(1, next.getY());
                teleport.getDoubles().write(2, next.getZ());
                teleport.getBytes().write(0, angleToByte(next.getYaw()));
                teleport.getBytes().write(1, angleToByte(next.getPitch()));
                protocol.sendServerPacket(owner, teleport);

                PacketContainer head = protocol.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
                head.getIntegers().write(0, clone.entityId);
                head.getBytes().write(0, angleToByte(next.getYaw()));
                protocol.sendServerPacket(owner, head);
            } catch (Throwable ex) {
                // Movement is the least critical part; a standing decoy still works.
                plugin.getLogger().log(Level.WARNING,
                        "Mirage clones could not be moved; they will stand still.", ex);
                return;
            }
        }
    }

    // ---- being attacked -------------------------------------------------

    /**
     * A packet entity cannot be hit server-side, so the client's swing is intercepted directly: if
     * the id it attacked belongs to a clone, that clone pops. This is what gives the decoys their
     * point -- an attacker spends a real swing on a fake target.
     */
    private void registerHitListener() {
        hitListener = new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!healthy) {
                    return;
                }
                try {
                    int entityId = event.getPacket().getIntegers().read(0);
                    Clone clone = clones.get(entityId);
                    if (clone == null) {
                        return;
                    }
                    event.setCancelled(true);
                    // Back onto the main thread: packets arrive on a netty thread.
                    Bukkit.getScheduler().runTask(plugin, () -> despawn(clone, true));
                } catch (Throwable ex) {
                    fail("use-entity", ex);
                }
            }
        };
        protocol.addPacketListener(hitListener);
    }

    private void despawn(Clone clone, boolean popped) {
        if (!clone.alive) {
            return;
        }
        clone.alive = false;
        clones.remove(clone.entityId);

        Player owner = Bukkit.getPlayer(clone.owner);
        if (owner != null) {
            try {
                PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                destroy.getIntLists().write(0, List.of(clone.entityId));
                protocol.sendServerPacket(owner, destroy);
            } catch (Throwable ex) {
                fail("entity-destroy", ex);
            }
            sendPlayerInfoRemove(owner, clone.uuid);

            if (popped && clone.location.getWorld() != null) {
                clone.location.getWorld().spawnParticle(
                        Particle.CLOUD, clone.location.clone().add(0, 1, 0), 20, 0.3d, 0.6d, 0.3d, 0.02d);
                clone.location.getWorld().playSound(
                        clone.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.4f);
            }
        }
    }

    @Override
    public void despawnAll() {
        for (Clone clone : new ArrayList<>(clones.values())) {
            despawn(clone, false);
        }
        clones.clear();
    }

    /** Called on plugin disable so ProtocolLib is not left holding a listener into a dead plugin. */
    public void shutdown() {
        despawnAll();
        if (hitListener != null) {
            try {
                protocol.removePacketListener(hitListener);
            } catch (Throwable ignored) {
                // Shutting down anyway.
            }
            hitListener = null;
        }
    }

    // ---- helpers --------------------------------------------------------

    private Location scatter(Location origin, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * Math.PI * 2.0d;
        double distance = 1.0d + random.nextDouble() * Math.max(0.0d, radius - 1.0d);
        Location where = origin.clone().add(
                Math.cos(angle) * distance, 0.0d, Math.sin(angle) * distance);
        where.setYaw(origin.getYaw() + (float) ((random.nextDouble() - 0.5d) * 90.0d));
        where.setPitch(0.0f);
        return where;
    }

    /** Minecraft encodes rotation as a byte over 256 steps rather than 360 degrees. */
    private static byte angleToByte(float degrees) {
        return (byte) (degrees * 256.0f / 360.0f);
    }

    private static final class Clone {
        private final int entityId;
        private final UUID uuid;
        private final UUID owner;
        private volatile Location location;
        private volatile boolean alive = true;

        private Clone(int entityId, UUID uuid, UUID owner, Location location) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.owner = owner;
            this.location = location;
        }
    }
}
