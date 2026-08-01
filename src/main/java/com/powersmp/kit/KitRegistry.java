package com.powersmp.kit;

import com.powersmp.PowerSMP;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

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
 *
 * <p>A player can have more than one kit at once -- a comma-separated value in {@code assignments:}
 * (e.g. {@code JustSoopTBH: phantom,lucky,lifestealer}) grants all of them, all the time.
 * {@link #kitsOf} is the real lookup everywhere behaviour is dispatched from; {@link #kitOf} is a
 * convenience for the single-kit case (the vast majority of the roster) and simply returns the
 * first entry.
 */
public class KitRegistry {

    /** The bespoke designs, per the build spec. Most players have exactly one kit here. */
    private static final Map<String, List<String>> HARDCODED_ASSIGNMENTS = Map.ofEntries(
            Map.entry("mavricc", List.of("mavricc")),
            Map.entry("northofnowhere", List.of("northofnowhere")),
            Map.entry("xcr1t1cx", List.of("xcr1t1cx")),
            Map.entry("kornflakis", List.of("kornflakis")),
            Map.entry("itzmetentx", List.of("itzmetentx")),
            Map.entry("jjlionjxi", List.of("jjlionjxi")),
            Map.entry("domanthegamer", List.of("domanthegamer")),
            Map.entry("sparkkkkkkkk", List.of("sparkkkkkkkk")),
            Map.entry("night_scar3", List.of("night_scar3")),
            Map.entry("marb13_", List.of("marb13")),
            Map.entry("llamachas", List.of("llamachas")),
            Map.entry("monkeyman4167", List.of("monkeyman")),
            Map.entry("techknightgaming", List.of("techknight")),
            Map.entry("ahriahn", List.of("voidwalker")),
            Map.entry("disasterflames", List.of("disasterflames")),
            Map.entry("funnysounds", List.of("funnysounds")),
            Map.entry("thepoultryman10", List.of("poultryman")),
            Map.entry("_glueman", List.of("theghost")),
            Map.entry("crazytnt2cool", List.of("crazytnt2cool")),
            Map.entry("idledeathgamble", List.of("idledeathgamble")),
            Map.entry("ldledeathgamble", List.of("idledeathgamble")),
            // Phantom + Life Stealer run all the time; Lucky's own reroll timer overrides both
            // (see #overrides) with a single rolled kit for its duration, then reverts to all three.
            Map.entry("justsooptbh", List.of("phantom", "lifestealer", "lucky")));

    private final PowerSMP plugin;
    private final Map<String, PowerKit> kitsById = new LinkedHashMap<>();
    /** lowercase IGN -> kit ids */
    private final Map<String, List<String>> nameAssignments = new ConcurrentHashMap<>();
    /** UUID -> kit ids, both from config UUID keys and from name lookups resolved on join. */
    private final Map<UUID, List<String>> uuidAssignments = new ConcurrentHashMap<>();
    /**
     * UUID -> single kit id, in-memory only. Takes priority over every permanently assigned kit
     * above when present. This is Lucky's whole mechanism: while an override is set, every lookup
     * here -- {@code kitsOf}, {@code isOwner}, and therefore {@code UnlockManager.isUnlocked} and the
     * shared kit tick/join/quit dispatch in {@code PowerSMP} -- resolves to the rolled kit alone
     * instead of the player's real kit(s), so the player genuinely becomes just that kit for as long
     * as the override lasts, with no other kit needing to know Lucky exists.
     */
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();

    public KitRegistry(PowerSMP plugin) {
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
                String raw = section.getString(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                List<String> kitIds = new ArrayList<>();
                for (String piece : raw.split(",")) {
                    String kitId = piece.trim().toLowerCase(Locale.ROOT);
                    if (kitId.isEmpty()) {
                        continue;
                    }
                    if (!kitsById.containsKey(kitId)) {
                        plugin.getLogger().warning("Assignment for '" + key + "' names unknown kit '"
                                + kitId + "'; known kits are " + kitsById.keySet());
                        continue;
                    }
                    kitIds.add(kitId);
                }
                if (kitIds.isEmpty()) {
                    continue;
                }
                try {
                    uuidAssignments.put(UUID.fromString(key), kitIds);
                } catch (IllegalArgumentException notAUuid) {
                    nameAssignments.put(key.toLowerCase(Locale.ROOT), kitIds);
                }
            }
        }
        plugin.getLogger().info("Kit assignments loaded: " + nameAssignments.size()
                + " by name, " + uuidAssignments.size() + " by UUID.");
    }

    /** @return every kit currently active for this player -- empty if they have none. */
    public List<PowerKit> kitsOf(Player player) {
        String overrideId = overrides.get(player.getUniqueId());
        if (overrideId != null) {
            PowerKit overridden = kitsById.get(overrideId);
            return overridden == null ? List.of() : List.of(overridden);
        }
        return assignedKitsOf(player);
    }

    /** Returns permanent assignments only, ignoring Lucky's temporary override. */
    public List<PowerKit> assignedKitsOf(Player player) {
        List<String> kitIds = uuidAssignments.get(player.getUniqueId());
        if (kitIds == null) {
            kitIds = nameAssignments.get(player.getName().toLowerCase(Locale.ROOT));
            if (kitIds != null) {
                uuidAssignments.put(player.getUniqueId(), kitIds);
            }
        }
        List<String> combinedIds = new ArrayList<>();
        if (kitIds != null) {
            combinedIds.addAll(kitIds);
        }
        for (String granted : plugin.data().get(player.getUniqueId()).grantedKits()) {
            String normalized = granted.toLowerCase(Locale.ROOT);
            if (kitsById.containsKey(normalized) && !combinedIds.contains(normalized)) {
                combinedIds.add(normalized);
            }
        }
        if (combinedIds.isEmpty()) {
            return List.of();
        }
        List<PowerKit> kits = new ArrayList<>(combinedIds.size());
        for (String kitId : combinedIds) {
            PowerKit kit = kitsById.get(kitId);
            if (kit != null) {
                kits.add(kit);
            }
        }
        return kits;
    }

    /** @return the player's first kit, or null if they have none. Most players have exactly one. */
    public PowerKit kitOf(Player player) {
        List<PowerKit> kits = kitsOf(player);
        return kits.isEmpty() ? null : kits.get(0);
    }

    public boolean isOwner(Player player, String kitId) {
        for (PowerKit kit : kitsOf(player)) {
            if (kit.id().equalsIgnoreCase(kitId)) {
                return true;
            }
        }
        return false;
    }

    public PowerKit byId(String kitId) {
        return kitId == null ? null : kitsById.get(kitId.toLowerCase(Locale.ROOT));
    }

    public Collection<PowerKit> all() {
        return List.copyOf(kitsById.values());
    }

    /** Resolves either a kit id or the IGN normally assigned to a single kit. */
    public PowerKit resolveSelector(String selector) {
        if (selector == null) {
            return null;
        }
        String normalized = selector.toLowerCase(Locale.ROOT);
        PowerKit direct = kitsById.get(normalized);
        if (direct != null) {
            return direct;
        }
        List<String> assignment = nameAssignments.get(normalized);
        return assignment != null && assignment.size() == 1 ? kitsById.get(assignment.get(0)) : null;
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
