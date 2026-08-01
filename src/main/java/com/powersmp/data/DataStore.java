package com.powersmp.data;

import com.powersmp.item.SpearItem;
import com.powersmp.item.BoundItemListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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
    /**
     * Monotonically identifies snapshots. An autosave queued before shutdown must not overwrite
     * the newer synchronous shutdown snapshot if its scheduler thread happens to run last.
     */
    private volatile long latestSnapshotVersion;

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
            data.spearTier(section.getInt("spear-tier", SpearItem.MAX_TIER));
            data.maceKills(section.getInt("mace-kills", 0));
            data.bloodlustKills(section.getInt("bloodlust-kills", 0));
            data.jackpotChance(section.getInt("jackpot-chance", 14));
            data.jackpotFeverArmed(section.getBoolean("jackpot-fever-armed", false));
            data.mahoragaTamed(section.getBoolean("mahoraga-tamed", false));
            data.adaptationDamage(section.getDouble("adaptation-damage", 0.0d));
            data.stanceConsolidated(section.getBoolean("stance-consolidated", false));
            data.omeletGranted(section.getBoolean("omelet-granted", false));
            data.lastKnownName(section.getString("name", ""));
            data.abilityTrigger(section.getString("ability-trigger", "SNEAK_RIGHT_CLICK"));
            data.primaryAbility(section.getString("primary-ability", ""));
            ConfigurationSection bindings = section.getConfigurationSection("ability-bindings");
            if (bindings != null) {
                for (String trigger : bindings.getKeys(false)) {
                    String ability = bindings.getString(trigger, "");
                    if (!ability.isBlank()) {
                        data.abilityBindings().put(trigger.toUpperCase(java.util.Locale.ROOT), ability);
                    }
                }
            }
            if (data.abilityBindings().isEmpty() && !data.primaryAbility().isBlank()) {
                data.abilityBindings().put(data.abilityTrigger(), data.primaryAbility());
            }
            if (data.abilityBindings().entrySet().removeIf(entry ->
                    isRemovedAbilityBinding(entry.getValue()))) {
                dirty = true;
            }
            for (Map.Entry<String, String> binding : data.abilityBindings().entrySet()) {
                String migrated = migrateDomanAbility(binding.getValue());
                if (!migrated.equals(binding.getValue())) {
                    binding.setValue(migrated);
                    dirty = true;
                }
            }
            String migratedPrimary = migrateDomanAbility(data.primaryAbility());
            if (isRemovedAbilityBinding(migratedPrimary)) {
                data.primaryAbility("");
                dirty = true;
            } else if (!migratedPrimary.equals(data.primaryAbility())) {
                data.primaryAbility(migratedPrimary);
                dirty = true;
            }
            data.realmReturn(section.getString("realm-return", ""));
            data.unlocked().addAll(section.getStringList("unlocked"));
            data.revoked().addAll(section.getStringList("revoked"));
            data.grantedKits().addAll(section.getStringList("granted-kits"));
            boolean removedUnlockedTechAbility =
                    data.unlocked().removeIf(this::isRemovedTechAbility);
            boolean removedRevokedTechAbility =
                    data.revoked().removeIf(this::isRemovedTechAbility);
            if (removedUnlockedTechAbility || removedRevokedTechAbility) {
                dirty = true;
            }
            migratePowerId(data.unlocked(), "permanent_strength", "infernal_vitality");
            migratePowerId(data.unlocked(), "dash", "shadow_bomb");
            migratePowerId(data.unlocked(), "density_mace", "cutlass_master");
            migratePowerId(data.unlocked(), "combat_vitality", "cutlass_master");
            migratePowerId(data.revoked(), "permanent_strength", "infernal_vitality");
            migratePowerId(data.revoked(), "dash", "shadow_bomb");
            migratePowerId(data.revoked(), "density_mace", "cutlass_master");
            migratePowerId(data.revoked(), "combat_vitality", "cutlass_master");
            migratePowerId(data.unlocked(), "limit_break", "blood_bound");
            migratePowerId(data.unlocked(), "ascended_flight", "tracking");
            migratePowerId(data.unlocked(), "final_burst", "bloodlust");
            migratePowerId(data.revoked(), "limit_break", "blood_bound");
            migratePowerId(data.revoked(), "ascended_flight", "tracking");
            migratePowerId(data.revoked(), "final_burst", "bloodlust");

            // Items are stored as Base64 of ItemStack#serializeAsBytes rather than through YAML's
            // ConfigurationSerializable path, which loses newer data components.
            for (String encoded : section.getStringList("restock-loadout")) {
                try {
                    ItemStack item =
                            ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                    if (!BoundItemListener.isMace(item)) {
                        BoundItemListener.purgeMaces(item);
                        data.restockLoadout().add(item);
                    }
                } catch (Throwable ex) {
                    plugin.getLogger().warning("Dropping an unreadable restock item for " + rawUuid);
                }
            }
            for (String encoded : section.getStringList("shadow-storage")) {
                try {
                    data.shadowStorage().add(ItemStack.deserializeBytes(
                            Base64.getDecoder().decode(encoded)));
                } catch (Throwable ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not load an item from shadow storage for " + uuid, ex);
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

    private void migratePowerId(java.util.Set<String> ids, String legacy, String current) {
        if (ids.remove(legacy)) {
            ids.add(current);
            dirty = true;
        }
    }

    private boolean isRemovedTechAbility(String id) {
        return id != null && (id.equalsIgnoreCase("earthbreaker")
                || id.equalsIgnoreCase("reflect_shield")
                || id.equalsIgnoreCase("decoy")
                || id.equalsIgnoreCase("strength")
                || id.equalsIgnoreCase("fortify")
                || id.equalsIgnoreCase("shockwave")
                || id.equalsIgnoreCase("overload"));
    }

    private boolean isRemovedAbilityBinding(String id) {
        return isRemovedTechAbility(id) || (id != null && id.equalsIgnoreCase("dash"));
    }

    private String migrateDomanAbility(String id) {
        if (id == null) {
            return "";
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "kamehameha", "ascended_flight" -> "blood_trail";
            case "final_burst" -> "blood_chain";
            case "instant_exchange" -> "divine_dogs";
            default -> id;
        };
    }

    /**
     * Builds and writes synchronously, blocking the calling thread until the disk write completes.
     * Used on shutdown, where the process may exit right after {@code onDisable} returns -- an async
     * write here could simply never finish.
     */
    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = buildYaml();
        dirty = false;
        long version = ++latestSnapshotVersion;
        writeToDisk(yaml, version);
    }

    /**
     * Same data, but the actual disk write happens off the main thread. Building the
     * {@link YamlConfiguration} still has to happen synchronously first -- it reads live
     * {@link ItemStack} objects out of the cache, and those are not safe to touch from another
     * thread -- but that part is pure in-memory bookkeeping and cheap. The write is the part that
     * can stall waiting on the disk, and on a small server that stall lands on the one core running
     * the whole game tick. Used by the periodic autosave, where a save that finishes a few
     * milliseconds late is a total non-issue; {@link #save()} is still what shutdown calls.
     */
    public void saveAsync() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = buildYaml();
        dirty = false;
        long version = ++latestSnapshotVersion;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(yaml, version));
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerData data : cache.values()) {
            String path = "players." + data.uuid();
            yaml.set(path + ".name", data.lastKnownName());
            yaml.set(path + ".ability-trigger", data.abilityTrigger());
            if (!data.primaryAbility().isBlank()) {
                yaml.set(path + ".primary-ability", data.primaryAbility());
            }
            for (Map.Entry<String, String> binding : data.abilityBindings().entrySet()) {
                if (!binding.getValue().isBlank()) {
                    yaml.set(path + ".ability-bindings." + binding.getKey(), binding.getValue());
                }
            }
            yaml.set(path + ".stance", data.stance());
            yaml.set(path + ".kills", data.kills());
            yaml.set(path + ".spear-kills", data.spearKills());
            yaml.set(path + ".spear-tier", data.spearTier());
            yaml.set(path + ".mace-kills", data.maceKills());
            yaml.set(path + ".bloodlust-kills", data.bloodlustKills());
            yaml.set(path + ".jackpot-chance", data.jackpotChance());
            yaml.set(path + ".jackpot-fever-armed", data.jackpotFeverArmed());
            yaml.set(path + ".mahoraga-tamed", data.mahoragaTamed());
            yaml.set(path + ".adaptation-damage", data.adaptationDamage());
            yaml.set(path + ".stance-consolidated", data.stanceConsolidated());
            yaml.set(path + ".omelet-granted", data.omeletGranted());
            yaml.set(path + ".unlocked", new ArrayList<>(data.unlocked()));
            yaml.set(path + ".revoked", new ArrayList<>(data.revoked()));
            yaml.set(path + ".granted-kits", new ArrayList<>(data.grantedKits()));
            if (!data.realmReturn().isBlank()) {
                yaml.set(path + ".realm-return", data.realmReturn());
            }

            List<String> loadout = new ArrayList<>();
            for (ItemStack item : data.restockLoadout()) {
                if (item != null && !item.getType().isAir()
                        && !BoundItemListener.isMace(item)) {
                    BoundItemListener.purgeMaces(item);
                    loadout.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
                }
            }
            yaml.set(path + ".restock-loadout", loadout);

            List<String> shadowStorage = new ArrayList<>();
            for (ItemStack item : data.shadowStorage()) {
                if (item != null && !item.getType().isAir()) {
                    shadowStorage.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
                }
            }
            yaml.set(path + ".shadow-storage", shadowStorage);

            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : data.cooldowns().entrySet()) {
                if (entry.getValue() > now) {
                    yaml.set(path + ".cooldowns." + entry.getKey(), entry.getValue());
                }
            }
        }
        return yaml;
    }

    private synchronized void writeToDisk(YamlConfiguration yaml, long version) {
        // A newer snapshot has already been built. Let that write win even if scheduler ordering
        // delivers this older autosave after it.
        if (version < latestSnapshotVersion) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create data folder " + parent);
            }
            File temporary = new File(parent, file.getName() + ".tmp");
            File backup = new File(parent, file.getName() + ".bak");
            yaml.save(temporary);
            if (file.exists()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // Keep the snapshot eligible for the next autosave instead of silently declaring
            // unsaved player progress clean after a transient filesystem failure.
            dirty = true;
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
