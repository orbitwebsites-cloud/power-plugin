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
    private static final Map<String, String> HARDCODED_ASSIGNMENTS = Map.of(
            "mavricc", "mavricc",
            "arhiahn", "arhiahn",
            "xcr1t1cx", "xcr1t1cx",
            "kornflakis", "kornflakis",
            "itzmetentx", "itzmetentx",
            "monkeyman4167", "monkeyman",
            "techknightgaming", "techknight");

    private final Plugin plugin;
    private final Map<String, PowerKit> kitsById = new LinkedHashMap<>();
    /** lowercase IGN -> kit id */
    private final Map<String, String> nameAssignments = new ConcurrentHashMap<>();
    /** UUID -> kit id, both from config UUID keys and from name lookups resolved on join. */
    private final Map<UUID, String> uuidAssignments = new ConcurrentHashMap<>();

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
        String kitId = uuidAssignments.get(player.getUniqueId());
        if (kitId == null) {
            kitId = nameAssignments.get(player.getName().toLowerCase(Locale.ROOT));
            if (kitId != null) {
                uuidAssignments.put(player.getUniqueId(), kitId);
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
}
