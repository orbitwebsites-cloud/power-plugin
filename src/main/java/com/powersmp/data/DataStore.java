package com.powersmp.data;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Flat YAML store keyed by UUID, cached in memory and flushed on an interval and on shutdown.
 *
 * <p>Small enough that rewriting the whole file is cheaper than anything clever, and it stays
 * hand-editable, which matters when an admin needs to fix someone's unlock state.
 */
public class DataStore {

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public DataStore(Plugin plugin, String fileName) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
    }

    public void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String rawUuid : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping malformed UUID in data file: " + rawUuid);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(rawUuid);
            if (section == null) {
                continue;
            }
            PlayerData data = new PlayerData(uuid);
            data.stance(section.getString("stance", "NONE"));
            data.kills(section.getInt("kills", 0));
            data.spearKills(section.getInt("spear-kills", 0));
            data.spearTier(section.getInt("spear-tier", 3));
            data.maceKills(section.getInt("mace-kills", 0));
            data.stanceConsolidated(section.getBoolean("stance-consolidated", false));
            data.omeletGranted(section.getBoolean("omelet-granted", false));
            data.lastKnownName(section.getString("name", ""));
            data.unlocked().addAll(section.getStringList("unlocked"));

            // Items are stored as Base64 of ItemStack#serializeAsBytes rather than through YAML's
            // ConfigurationSerializable path, which loses newer data components.
            for (String encoded : section.getStringList("restock-loadout")) {
                try {
                    data.restockLoadout().add(
                            ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
                } catch (Throwable ex) {
                    plugin.getLogger().warning("Dropping an unreadable restock item for " + rawUuid);
                }
            }

            ConfigurationSection cooldowns = section.getConfigurationSection("cooldowns");
            if (cooldowns != null) {
                long now = System.currentTimeMillis();
                for (String ability : cooldowns.getKeys(false)) {
                    long until = cooldowns.getLong(ability, 0L);
                    // Drop entries that already elapsed while the server was down.
                    if (until > now) {
                        data.cooldowns().put(ability, until);
                    }
                }
            }
            cache.put(uuid, data);
        }
        plugin.getLogger().info("Loaded PowerSMP data for " + cache.size() + " player(s).");
    }

    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerData data : cache.values()) {
            String path = "players." + data.uuid();
            yaml.set(path + ".name", data.lastKnownName());
            yaml.set(path + ".stance", data.stance());
            yaml.set(path + ".kills", data.kills());
            yaml.set(path + ".spear-kills", data.spearKills());
            yaml.set(path + ".spear-tier", data.spearTier());
            yaml.set(path + ".mace-kills", data.maceKills());
            yaml.set(path + ".stance-consolidated", data.stanceConsolidated());
            yaml.set(path + ".omelet-granted", data.omeletGranted());
            yaml.set(path + ".unlocked", new ArrayList<>(data.unlocked()));

            List<String> loadout = new ArrayList<>();
            for (ItemStack item : data.restockLoadout()) {
                if (item != null && !item.getType().isAir()) {
                    loadout.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
                }
            }
            yaml.set(path + ".restock-loadout", loadout);

            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : data.cooldowns().entrySet()) {
                if (entry.getValue() > now) {
                    yaml.set(path + ".cooldowns." + entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create data folder " + parent);
            }
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save PowerSMP data", ex);
        }
    }

    /** Never returns null -- an unknown player gets a fresh, empty record. */
    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, PlayerData::new);
    }

    public Collection<PlayerData> all() {
        return List.copyOf(cache.values());
    }

    /** Call after any mutation, otherwise {@link #save()} is a no-op. */
    public void markDirty() {
        dirty = true;
    }
}
