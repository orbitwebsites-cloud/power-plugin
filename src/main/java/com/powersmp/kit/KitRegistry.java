package com.powersmp.kit;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Maps players to kits.
 *
 * <p>Assignment is hardcoded (open question #1: these are fixed, bespoke per-player designs, so a
 * config lookup would be ceremony for no benefit), but {@code assignments:} in kits.yml overrides
 * and extends the hardcoded map so an admin can reassign without a rebuild -- useful for testing a
 * kit on an alt.
 *
 * <p>Lookups go by IGN, cached to UUID on join. Names are the practical key here because that is
 * what the spec is written in; the UUID cache means a name change mid-session does not detach
 * someone from their kit.
 */
public class KitRegistry {

    /** The bespoke designs, per the build spec. */
    private static final Map<String, String> HARDCODED_ASSIGNMENTS = Map.ofEntries(
            Map.entry("mavricc", "mavricc"),
            Map.entry("northofnowhere", "northofnowhere"),
            Map.entry("xcr1t1cx", "xcr1t1cx"),
            Map.entry("kornflakis", "kornflakis"),
            Map.entry("itzmetentx", "itzmetentx"),
            Map.entry("jjlionjxi", "jjlionjxi"),
            Map.entry("domanthegamer", "domanthegamer"),
            Map.entry("sparkkkkkkkk", "sparkkkkkkkk"),
            Map.entry("night_scar3", "night_scar3"),
            Map.entry("marb13_", "marb13"),
            Map.entry("llamachas", "llamachas"),
            Map.entry("monkeyman4167", "monkeyman"),
            Map.entry("techknightgaming", "techknight"),
            Map.entry("ahriahn", "voidwalker"),
            Map.entry("disasterflames", "returnbydeath"),
            Map.entry("_glueman", "theghost"));

    private final Plugin plugin;
    private final Map<String, PowerKit> kitsById = new LinkedHashMap<>();
    /** lowercase IGN -> kit id */
    private final Map<String, String> nameAssignments = new ConcurrentHashMap<>();
    /** UUID -> kit id, both from config UUID keys and from name lookups resolved on join. */
    private final Map<UUID, String> uuidAssignments = new ConcurrentHashMap<>();
    /**
     * UUID -> kit id, in-memory only. Takes priority over the permanent assignment above when
     * present. This is Lucky's whole mechanism: while an override is set, every lookup here --
     * {@code kitOf}, {@code isOwner}, and therefore {@code UnlockManager.isUnlocked} and the shared
     * kit tick/join/quit dispatch in {@code PowerSMP} -- resolves to the rolled kit instead, so the
     * player genuinely becomes that kit for as long as the override lasts, with no other kit needing
     * to know Lucky exists.
     */
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();

    public KitRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(PowerKit kit) {
        kitsById.put(kit.id().toLowerCase(Locale.ROOT), kit);
    }

    public void loadAssignments(ConfigurationSection section) {
        nameAssignments.clear();
        uuidAssignments.clear();
        nameAssignments.putAll(HARDCODED_ASSIGNMENTS);

        if (section != null) {
            for (String key : section.getKeys(false)) {
                String kitId = section.getString(key);
                if (kitId == null || kitId.isBlank()) {
                    continue;
                }
                kitId = kitId.toLowerCase(Locale.ROOT);
                if (!kitsById.containsKey(kitId)) {
                    plugin.getLogger().warning("Assignment for '" + key + "' names unknown kit '"
                            + kitId + "'; known kits are " + kitsById.keySet());
                    continue;
                }
                try {
                    uuidAssignments.put(UUID.fromString(key), kitId);
                } catch (IllegalArgumentException notAUuid) {
                    nameAssignments.put(key.toLowerCase(Locale.ROOT), kitId);
                }
            }
        }
        plugin.getLogger().info("Kit assignments loaded: " + nameAssignments.size()
                + " by name, " + uuidAssignments.size() + " by UUID.");
    }

    /** @return the player's kit, or null if they have none. */
    public PowerKit kitOf(Player player) {
        String kitId = overrides.get(player.getUniqueId());
        if (kitId == null) {
            kitId = uuidAssignments.get(player.getUniqueId());
            if (kitId == null) {
                kitId = nameAssignments.get(player.getName().toLowerCase(Locale.ROOT));
                if (kitId != null) {
                    uuidAssignments.put(player.getUniqueId(), kitId);
                }
            }
        }
        return kitId == null ? null : kitsById.get(kitId);
    }

    public boolean isOwner(Player player, String kitId) {
        PowerKit kit = kitOf(player);
        return kit != null && kit.id().equalsIgnoreCase(kitId);
    }

    public PowerKit byId(String kitId) {
        return kitId == null ? null : kitsById.get(kitId.toLowerCase(Locale.ROOT));
    }

    public Collection<PowerKit> all() {
        return List.copyOf(kitsById.values());
    }

    /** Sets (or replaces) an in-memory kit override for this player. See {@link #overrides}. */
    public void setOverride(UUID uuid, String kitId) {
        overrides.put(uuid, kitId.toLowerCase(Locale.ROOT));
    }

    /** @return the overriding kit id, or null if this player has no active override. */
    public String overrideOf(UUID uuid) {
        return overrides.get(uuid);
    }

    public void clearOverride(UUID uuid) {
        overrides.remove(uuid);
    }
}
