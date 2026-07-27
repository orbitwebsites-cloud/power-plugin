package com.powersmp.domain;

import com.powersmp.PowerSMP;
import com.powersmp.util.Attributes;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * The Illusory Realm: a sealed arena world, plus every rule that applies while someone is inside it.
 *
 * <p>This lives outside {@code kit.impl} because it is not really an ability -- it is a small
 * sub-game with its own world, its own rule set and its own lifecycle, and half of what it does is
 * suppressing behaviour that belongs to <em>other</em> kits. {@code VoidwalkerKit} only decides when
 * to open one and who gets pulled in.
 *
 * <h2>Where the arena comes from</h2>
 * A dedicated void world ({@link VoidChunkGenerator}) is created once and kept. Each active domain
 * claims a numbered slot in it, hundreds of blocks from its neighbours, and the box is stamped in
 * and wiped out again around the fight. Nothing is left behind between uses, and two domains can run
 * at once without seeing each other.
 *
 * <h2>Why the restrictions are listeners and not checks</h2>
 * "Totems do not work", "enderpearls do not work", "chorus fruit are disabled" and "the box cannot
 * be broken" are all things a player does to the server, not things the plugin does to a player --
 * so each one is a cancel on the relevant event, filtered to participants. "All powers are disabled"
 * is the exception: rather than 13 kits each learning about domains, {@code UnlockManager} asks this
 * class whether a player is inside, and every {@code isUnlocked} check in the plugin goes false at
 * once.
 *
 * <h2>Getting people out again</h2>
 * The failure everyone remembers is being stranded in an arena world after a crash. Return
 * locations are written to {@code data.yml} the moment someone is pulled in, so a disconnect, a
 * death, a plugin disable or a server crash all still have a way home -- the join handler evacuates
 * anyone it finds standing in the realm without an active domain.
 */
public class IllusoryRealm implements Listener {

    private static final String DEFAULT_WORLD = "powersmp_illusory_realm";

    /** Why a domain ended, which is only used to word the closing message. */
    private enum Ending {
        TIME, LAST_STANDING, ABORTED
    }

    private final PowerSMP plugin;
    private final Random random = new Random();

    /** Domains currently running. Small -- realistically one, occasionally two. */
    private final List<Domain> domains = new CopyOnWriteArrayList<>();
    /** participant -> their domain, so every listener below is an O(1) map hit. */
    private final Map<UUID, Domain> byParticipant = new ConcurrentHashMap<>();
    private final Set<Integer> usedSlots = ConcurrentHashMap.newKeySet();

    private World world;
    private boolean worldUnavailable;
    /**
     * Set while this class is applying the domain's own effects, so the amplifier cap below does not
     * cancel the very effects it exists to protect. Safe as a plain field: potion application and
     * event dispatch are both main-thread only.
     */
    private boolean applyingOwnEffects;

    // ---- config ----------------------------------------------------------
    private String worldName = DEFAULT_WORLD;
    private boolean preGenerate = true;
    private int durationSeconds = 60;
    private int entryDelayTicks = 20;
    private int blindnessTicks = 20;
    private double gatherRadius = 20.0d;
    private int minParticipants = 2;
    private double bonusHeartsPerVictim = 2.0d;

    private int interiorSize = 20;
    private int interiorHeight = 10;
    private int baseY = 64;
    private int slotSpacing = 256;
    private Material shellMaterial = Material.BLACK_CONCRETE;
    private List<Material> terrainMaterials = List.of(
            Material.BLACKSTONE, Material.DEEPSLATE, Material.BASALT, Material.OBSIDIAN);
    private int terrainClusters = 10;
    private int floatingPlatforms = 3;
    private boolean interiorLight = true;

    private boolean disablePowers = true;
    private boolean disableTotems = true;
    private boolean disablePearls = true;
    private boolean disableEnderChests = true;
    private boolean disableChorusFruit = true;
    private boolean unbreakable = true;
    private boolean blockOtherEffects;

    /** effect -> highest amplifier obtainable inside. Also the set handed out on entry. */
    private Map<PotionEffectType, Integer> granted = new LinkedHashMap<>();

    public IllusoryRealm(PowerSMP plugin) {
        this.plugin = plugin;
        granted.put(PotionEffectType.SPEED, 0);
        granted.put(PotionEffectType.FIRE_RESISTANCE, 0);
        granted.put(PotionEffectType.STRENGTH, 0);
        granted.put(PotionEffectType.WEAVING, 0);
    }

    // ---- config ----------------------------------------------------------

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        worldName = section.getString("world-name", worldName);
        preGenerate = section.getBoolean("pre-generate", preGenerate);
        durationSeconds = Math.max(5, section.getInt("duration-seconds", durationSeconds));
        entryDelayTicks = Math.max(0, section.getInt("entry-delay-ticks", entryDelayTicks));
        blindnessTicks = Math.max(0, section.getInt("entry-blindness-ticks", blindnessTicks));
        gatherRadius = section.getDouble("gather-radius", gatherRadius);
        minParticipants = Math.max(1, section.getInt("min-participants", minParticipants));
        bonusHeartsPerVictim = section.getDouble("bonus-hearts-per-victim", bonusHeartsPerVictim);

        ConfigurationSection arena = section.getConfigurationSection("arena");
        if (arena != null) {
            interiorSize = Math.max(6, Math.min(64, arena.getInt("size", interiorSize)));
            interiorHeight = Math.max(4, Math.min(64, arena.getInt("height", interiorHeight)));
            baseY = arena.getInt("floor-y", baseY);
            slotSpacing = Math.max(interiorSize + 32, arena.getInt("slot-spacing", slotSpacing));
            interiorLight = arena.getBoolean("interior-light", interiorLight);
            terrainClusters = Math.max(0, arena.getInt("terrain-clusters", terrainClusters));
            floatingPlatforms = Math.max(0, arena.getInt("floating-platforms", floatingPlatforms));
            Material shell = Material.matchMaterial(arena.getString("shell-block", ""));
            if (shell != null && shell.isBlock()) {
                shellMaterial = shell;
            }
            List<Material> parsed = new ArrayList<>();
            for (String name : arena.getStringList("terrain-blocks")) {
                Material material = Material.matchMaterial(name);
                if (material != null && material.isBlock()) {
                    parsed.add(material);
                }
            }
            if (!parsed.isEmpty()) {
                terrainMaterials = List.copyOf(parsed);
            }
        }

        ConfigurationSection rules = section.getConfigurationSection("rules");
        if (rules != null) {
            disablePowers = rules.getBoolean("disable-powers", disablePowers);
            disableTotems = rules.getBoolean("disable-totems", disableTotems);
            disablePearls = rules.getBoolean("disable-enderpearls", disablePearls);
            disableEnderChests = rules.getBoolean("disable-enderchests", disableEnderChests);
            disableChorusFruit = rules.getBoolean("disable-chorus-fruit", disableChorusFruit);
            unbreakable = rules.getBoolean("unbreakable-box", unbreakable);
            blockOtherEffects = rules.getBoolean("block-all-other-effects", blockOtherEffects);
        }

        ConfigurationSection effects = section.getConfigurationSection("granted-effects");
        if (effects != null) {
            Map<PotionEffectType, Integer> parsed = new LinkedHashMap<>();
            for (String key : effects.getKeys(false)) {
                PotionEffectType type = matchEffect(key);
                if (type == null) {
                    plugin.getLogger().warning("Illusory Realm: unknown potion effect '" + key + "'");
                    continue;
                }
                parsed.put(type, Math.max(0, effects.getInt(key, 0)));
            }
            if (!parsed.isEmpty()) {
                granted = parsed;
            }
        }
    }

    /** Looks an effect up by name across the spellings 1.21.x has used. */
    private static PotionEffectType matchEffect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        try {
            PotionEffectType byKey = org.bukkit.Registry.EFFECT.get(
                    org.bukkit.NamespacedKey.minecraft(cleaned));
            if (byKey != null) {
                return byKey;
            }
        } catch (Throwable ignored) {
            // Registry shape moved between versions; fall through to the legacy lookup.
        }
        try {
            return PotionEffectType.getByName(cleaned.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    public int entryDelayTicks() {
        return entryDelayTicks;
    }

    public int blindnessTicks() {
        return blindnessTicks;
    }

    public double gatherRadius() {
        return gatherRadius;
    }

    public int minParticipants() {
        return minParticipants;
    }

    // ---- world -----------------------------------------------------------

    /** Kicks off world creation shortly after startup so the first use is not the one that pays. */
    public void start() {
        if (preGenerate) {
            Bukkit.getScheduler().runTaskLater(plugin, this::ensureWorld, 100L);
        }
    }

    /**
     * @return the arena world, creating it if this is the first call, or null if it could not be
     *     made -- in which case the ability declines rather than throwing.
     */
    private World ensureWorld() {
        if (world != null) {
            return world;
        }
        if (worldUnavailable) {
            return null;
        }
        World existing = Bukkit.getWorld(worldName);
        if (existing == null) {
            try {
                existing = new WorldCreator(worldName)
                        .generator(new VoidChunkGenerator())
                        .environment(World.Environment.NORMAL)
                        .generateStructures(false)
                        .createWorld();
            } catch (Throwable ex) {
                plugin.getLogger().severe("Could not create the Illusory Realm world: " + ex);
                worldUnavailable = true;
                return null;
            }
        }
        if (existing == null) {
            worldUnavailable = true;
            plugin.getLogger().severe("Illusory Realm world '" + worldName + "' could not be created.");
            return null;
        }
        configureWorld(existing);
        world = existing;
        return world;
    }

    /** Nothing about this world should behave like a world -- no weather, no mobs, no clock. */
    private void configureWorld(World target) {
        try {
            target.setAutoSave(false);
            target.setDifficulty(Difficulty.NORMAL);
            target.setStorm(false);
            target.setThundering(false);
            target.setTime(18000L);
            target.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            target.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            target.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            target.setGameRule(GameRule.DO_FIRE_TICK, false);
            target.setGameRule(GameRule.MOB_GRIEFING, false);
            target.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Illusory Realm: could not fully configure the arena world: " + ex);
        }
    }

    public boolean available() {
        return !worldUnavailable;
    }

    // ---- state queries ---------------------------------------------------

    public boolean isInside(Player player) {
        return player != null && byParticipant.containsKey(player.getUniqueId());
    }

    /**
     * The single choke point for "all powers are disabled". {@code UnlockManager} calls this, so
     * every {@code isUnlocked} check across all 14 kits goes false the moment someone is pulled in.
     */
    public boolean powersSuppressed(Player player) {
        return disablePowers && isInside(player);
    }

    /** True while this player's own domain is running -- used to stop him re-opening one. */
    public boolean hasOpenDomain(Player owner) {
        for (Domain domain : domains) {
            if (domain.owner.equals(owner.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    // ---- opening ---------------------------------------------------------

    /**
     * Pulls everyone in.
     *
     * @param owner        the Voidwalker
     * @param participants everyone being taken, owner included
     * @param onClose      run when the domain ends, whatever ended it -- this is where the caller
     *                     starts its cooldown, since the spec counts from the end and not the start
     * @return false if the domain could not be opened at all
     */
    public boolean open(Player owner, List<Player> participants, Runnable onClose) {
        World arena = ensureWorld();
        if (arena == null) {
            Text.msg(owner, "<red>The Illusory Realm could not be opened -- the arena world is unavailable.");
            return false;
        }
        int slot = claimSlot();
        Domain domain = new Domain(owner.getUniqueId(), slot, slot * slotSpacing, 0, onClose);

        for (Player player : participants) {
            domain.returns.put(player.getUniqueId(), player.getLocation().clone());
            domain.alive.add(player.getUniqueId());
        }
        domains.add(domain);
        for (UUID uuid : domain.returns.keySet()) {
            byParticipant.put(uuid, domain);
        }

        buildArena(arena, domain, participants.size());

        int index = 0;
        for (Player player : participants) {
            // Persisted before the teleport, so even a crash mid-teleport leaves a way home.
            plugin.data().get(player.getUniqueId()).realmReturn(serialize(player.getLocation()));
            plugin.data().markDirty();

            Location spawn = domain.spawnPoints.get(index % domain.spawnPoints.size());
            player.teleport(spawn);
            applyEntryState(player);
            player.showTitle(Title.title(
                    Text.mm("<dark_purple><bold>ILLUSORY REALM</bold></dark_purple>"),
                    Text.mm("<gray>No powers. No escape. " + durationSeconds + " seconds.</gray>")));
            player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.7f, 0.4f);
            index++;
        }

        // "For each player (not including the user) caught in the domain, the user gains 2 max
        // hearts for the duration." Applied before the heal so the new hearts come in filled.
        int victims = Math.max(0, participants.size() - 1);
        if (victims > 0 && bonusHeartsPerVictim > 0.0d) {
            Attributes.set(owner, Attributes.MAX_HEALTH, Keys.REALM_BONUS_HEALTH,
                    victims * bonusHeartsPerVictim * 2.0d);
            healToFull(owner);
            Text.msg(owner, "<light_purple>+" + (int) (victims * bonusHeartsPerVictim)
                    + " hearts</light_purple> <gray>for " + victims + " caught.</gray>");
        }

        domain.endAt = System.currentTimeMillis() + durationSeconds * 1000L;
        domain.startedWith = participants.size();
        domain.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> supervise(domain), 20L, 20L);
        return true;
    }

    private int claimSlot() {
        for (int slot = 0; slot < 64; slot++) {
            if (usedSlots.add(slot)) {
                return slot;
            }
        }
        return 0;
    }

    // ---- arena construction ----------------------------------------------

    /**
     * Stamps the box in.
     *
     * <p>Spawn points are chosen first so the scenery can be told to stay away from them -- burying
     * someone inside a pillar the instant they arrive would be a memorable bug.
     */
    private void buildArena(World arena, Domain domain, int participants) {
        int x0 = domain.originX;
        int z0 = domain.originZ;
        int x1 = x0 + interiorSize - 1;
        int z1 = z0 + interiorSize - 1;
        int yRoof = baseY + interiorHeight + 1;

        double centreX = x0 + interiorSize / 2.0d;
        double centreZ = z0 + interiorSize / 2.0d;
        double ring = Math.max(2.0d, interiorSize / 2.0d - 3.0d);
        int points = Math.max(2, participants);
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            double x = centreX + Math.cos(angle) * ring;
            double z = centreZ + Math.sin(angle) * ring;
            Location spawn = new Location(arena, x, baseY + 1.0d, z);
            // Face the middle, so everyone arrives looking at each other.
            spawn.setYaw((float) (Math.toDegrees(Math.atan2(centreZ - z, x - centreX)) - 90.0d));
            spawn.setPitch(0.0f);
            domain.spawnPoints.add(spawn);
        }

        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = baseY; y <= yRoof; y++) {
                    boolean shell = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1
                            || y == baseY || y == yRoof;
                    Block block = arena.getBlockAt(x, y, z);
                    block.setType(shell ? shellMaterial : Material.AIR, false);
                }
            }
        }

        for (int i = 0; i < terrainClusters; i++) {
            int cx = x0 + 2 + random.nextInt(Math.max(1, interiorSize - 4));
            int cz = z0 + 2 + random.nextInt(Math.max(1, interiorSize - 4));
            if (tooCloseToSpawn(domain, cx, cz)) {
                continue;
            }
            int radius = 1 + random.nextInt(2);
            int height = 1 + random.nextInt(3);
            Material material = terrainMaterials.get(random.nextInt(terrainMaterials.size()));
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    int columnHeight = Math.max(1, height - Math.abs(dx) - Math.abs(dz));
                    for (int dy = 1; dy <= columnHeight; dy++) {
                        setIfInside(arena, x0, z0, x1, z1, cx + dx, baseY + dy, cz + dz, material);
                    }
                }
            }
        }

        for (int i = 0; i < floatingPlatforms; i++) {
            int cx = x0 + 3 + random.nextInt(Math.max(1, interiorSize - 6));
            int cz = z0 + 3 + random.nextInt(Math.max(1, interiorSize - 6));
            int y = baseY + 3 + random.nextInt(Math.max(1, interiorHeight - 4));
            Material material = terrainMaterials.get(random.nextInt(terrainMaterials.size()));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    setIfInside(arena, x0, z0, x1, z1, cx + dx, y, cz + dz, material);
                }
            }
        }

        if (interiorLight) {
            Material light = Material.matchMaterial("LIGHT");
            if (light != null) {
                // Invisible light sources under the roof: the box still reads as pitch black, but
                // a sealed room with no light at all is unplayable.
                for (int x = x0 + 2; x <= x1; x += 5) {
                    for (int z = z0 + 2; z <= z1; z += 5) {
                        arena.getBlockAt(x, yRoof - 1, z).setType(light, false);
                    }
                }
            }
        }
    }

    private boolean tooCloseToSpawn(Domain domain, int x, int z) {
        for (Location spawn : domain.spawnPoints) {
            double dx = spawn.getX() - x;
            double dz = spawn.getZ() - z;
            if (dx * dx + dz * dz < 16.0d) {
                return true;
            }
        }
        return false;
    }

    private void setIfInside(World arena, int x0, int z0, int x1, int z1,
                             int x, int y, int z, Material material) {
        if (x < x0 || x > x1 || z < z0 || z > z1 || y > baseY + interiorHeight) {
            return;
        }
        arena.getBlockAt(x, y, z).setType(material, false);
    }

    /** Wipes the whole volume back to air so nothing accumulates between fights. */
    private void clearArena(Domain domain) {
        if (world == null) {
            return;
        }
        int x0 = domain.originX;
        int z0 = domain.originZ;
        int x1 = x0 + interiorSize - 1;
        int z1 = z0 + interiorSize - 1;
        int yRoof = baseY + interiorHeight + 1;
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = baseY; y <= yRoof; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    // ---- entry / exit state ----------------------------------------------

    /**
     * "All players are healed to full hp; all effects are cleared, they are then given speed 1, fire
     * res, strength 1, and weaving all for 60 seconds."
     */
    private void applyEntryState(Player player) {
        applyingOwnEffects = true;
        try {
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            healToFull(player);
            player.setFireTicks(0);
            for (Map.Entry<PotionEffectType, Integer> entry : granted.entrySet()) {
                player.addPotionEffect(new PotionEffect(
                        entry.getKey(), durationSeconds * 20, entry.getValue(), true, false, true));
            }
        } finally {
            applyingOwnEffects = false;
        }
    }

    private void healToFull(Player player) {
        double max = Attributes.valueOf(player, Attributes.MAX_HEALTH, 20.0d);
        player.setHealth(Math.max(0.5d, max));
    }

    /**
     * Takes the domain's fingerprints off a player: the granted effects, the Voidwalker's bonus
     * hearts, and their entry in the participant map. Safe to call for someone already released.
     */
    private void clearRealmState(Player player) {
        applyingOwnEffects = true;
        try {
            for (PotionEffectType type : granted.keySet()) {
                player.removePotionEffect(type);
            }
        } finally {
            applyingOwnEffects = false;
        }
        Attributes.clear(player, Attributes.MAX_HEALTH, Keys.REALM_BONUS_HEALTH);
        double max = Attributes.valueOf(player, Attributes.MAX_HEALTH, 20.0d);
        if (player.getHealth() > max) {
            player.setHealth(max);
        }
        plugin.data().get(player.getUniqueId()).realmReturn("");
        plugin.data().markDirty();
    }

    // ---- supervision and closing -----------------------------------------

    /** Runs once a second per domain: expiry, last-one-standing, and containment. */
    private void supervise(Domain domain) {
        if (domain.closing) {
            return;
        }
        if (System.currentTimeMillis() >= domain.endAt) {
            close(domain, Ending.TIME);
            return;
        }

        int standing = 0;
        for (UUID uuid : new ArrayList<>(domain.returns.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (world == null || !player.getWorld().equals(world)) {
                // Pulled out by something else (an admin, another plugin). Let them go rather than
                // dragging them back -- but stop counting them, or the domain never ends.
                release(uuid, false);
                continue;
            }
            if (!domain.alive.contains(uuid)) {
                continue;
            }
            containWithin(domain, player);
            standing++;
        }

        if (domain.startedWith >= minParticipants && standing <= 1) {
            close(domain, Ending.LAST_STANDING);
            return;
        }
        if (standing == 0) {
            close(domain, Ending.ABORTED);
            return;
        }

        long remaining = (domain.endAt - System.currentTimeMillis()) / 1000L;
        if (remaining == 30L || remaining == 10L || (remaining <= 5L && remaining > 0L)) {
            for (UUID uuid : domain.returns.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    Text.actionBar(player, "<dark_purple>Illusory Realm</dark_purple> <gray>--</gray> <white>"
                            + remaining + "s</white>");
                }
            }
        }
    }

    /** Belt and braces: if anything puts a player outside the box, put them back on a spawn point. */
    private void containWithin(Domain domain, Player player) {
        Location at = player.getLocation();
        int x0 = domain.originX - 1;
        int z0 = domain.originZ - 1;
        int x1 = domain.originX + interiorSize;
        int z1 = domain.originZ + interiorSize;
        boolean outside = at.getX() < x0 || at.getX() > x1 + 1
                || at.getZ() < z0 || at.getZ() > z1 + 1
                || at.getY() < baseY || at.getY() > baseY + interiorHeight + 2;
        if (outside && !domain.spawnPoints.isEmpty()) {
            player.teleport(domain.spawnPoints.get(0));
            Text.actionBar(player, "<dark_purple>There is no outside.</dark_purple>");
        }
    }

    private void close(Domain domain, Ending ending) {
        if (domain.closing) {
            return;
        }
        domain.closing = true;
        if (domain.task != null) {
            domain.task.cancel();
        }

        String message = switch (ending) {
            case TIME -> "<gray>The Illusory Realm fades.</gray>";
            case LAST_STANDING -> "<gray>Only one remains. The Illusory Realm fades.</gray>";
            case ABORTED -> "<gray>The Illusory Realm collapses.</gray>";
        };

        for (UUID uuid : new ArrayList<>(domain.returns.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                Text.msg(player, message);
            }
            release(uuid, true);
        }

        clearArena(domain);
        usedSlots.remove(domain.slot);
        domains.remove(domain);

        if (domain.onClose != null) {
            try {
                domain.onClose.run();
            } catch (Exception ex) {
                plugin.getLogger().warning("Illusory Realm close handler threw: " + ex);
            }
        }
    }

    /**
     * Removes one player from whatever domain they are in.
     *
     * @param teleportBack false when they are already somewhere else (dead, disconnected, dragged
     *                     out) and moving them again would be wrong
     */
    private void release(UUID uuid, boolean teleportBack) {
        Domain domain = byParticipant.remove(uuid);
        if (domain == null) {
            return;
        }
        Location home = domain.returns.remove(uuid);
        domain.alive.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            // Offline: the return location stays in data.yml and the join handler finishes the job.
            return;
        }
        clearRealmState(player);
        if (teleportBack && home != null && home.getWorld() != null) {
            player.teleport(home);
        }
    }

    /** Ends everything, used on plugin disable and on {@code /powersmp reload}. */
    public void shutdown() {
        for (Domain domain : new ArrayList<>(domains)) {
            close(domain, Ending.ABORTED);
        }
        domains.clear();
        byParticipant.clear();
        usedSlots.clear();
    }

    // ---- rules that apply inside -----------------------------------------

    /** Caps amplifiers: you cannot splash Strength II over the domain's Strength I. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (applyingOwnEffects || !(event.getEntity() instanceof Player player) || !isInside(player)) {
            return;
        }
        PotionEffect incoming = event.getNewEffect();
        if (incoming == null) {
            return;
        }
        Integer cap = granted.get(incoming.getType());
        if (cap == null) {
            if (blockOtherEffects) {
                event.setCancelled(true);
            }
            return;
        }
        if (incoming.getAmplifier() > cap) {
            event.setCancelled(true);
            Text.actionBar(player, "<dark_purple>The realm refuses it.</dark_purple>");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (disableTotems && event.getEntity() instanceof Player player && isInside(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!disablePearls || !(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        if (pearl.getShooter() instanceof Player player && isInside(player)) {
            event.setCancelled(true);
            Text.actionBar(player, "<dark_purple>The realm has no elsewhere.</dark_purple>");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (disableChorusFruit && event.getItem().getType() == Material.CHORUS_FRUIT
                && isInside(event.getPlayer())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<dark_purple>The realm has no elsewhere.</dark_purple>");
        }
    }

    /** Catches any teleport the launch/consume cancels above did not already stop. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!isInside(event.getPlayer())) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        boolean blocked = (disablePearls && cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL)
                || (disableChorusFruit && cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT);
        if (blocked) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isInside(event.getPlayer())) {
            return;
        }
        if (disableEnderChests && event.getBlock().getType() == Material.ENDER_CHEST) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<dark_purple>The realm rejects it.</dark_purple>");
            return;
        }
        if (unbreakable) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (unbreakable && inArenaWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<dark_purple>The realm does not yield.</dark_purple>");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (unbreakable && inArenaWorld(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (unbreakable && inArenaWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    /** Covers endermen, falling blocks, and anything else that edits terrain without breaking it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (unbreakable && inArenaWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    private boolean inArenaWorld(World candidate) {
        return world != null && world.equals(candidate);
    }

    // ---- participant lifecycle -------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Domain domain = byParticipant.get(event.getEntity().getUniqueId());
        if (domain != null) {
            domain.alive.remove(event.getEntity().getUniqueId());
        }
    }

    /** Respawn sends them home rather than to a bed, and takes the domain's state off them. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Domain domain = byParticipant.get(player.getUniqueId());
        if (domain == null) {
            return;
        }
        Location home = domain.returns.get(player.getUniqueId());
        if (home != null && home.getWorld() != null) {
            event.setRespawnLocation(home);
        }
        release(player.getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Domain domain = byParticipant.get(event.getPlayer().getUniqueId());
        if (domain != null) {
            // Their return location is already on disk; the join handler picks it up.
            domain.alive.remove(event.getPlayer().getUniqueId());
            byParticipant.remove(event.getPlayer().getUniqueId());
            domain.returns.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Evacuation. Anyone who logs in standing in the arena world without an active domain -- after a
     * crash, a restart, or a disconnect mid-fight -- is put back where they were pulled from.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String stored = plugin.data().get(player.getUniqueId()).realmReturn();
        boolean stranded = player.getWorld().getName().equalsIgnoreCase(worldName);
        if (!stranded && (stored == null || stored.isBlank())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || isInside(player)) {
                return;
            }
            clearRealmState(player);
            if (!player.getWorld().getName().equalsIgnoreCase(worldName)) {
                return;
            }
            Location home = deserialize(stored);
            if (home == null) {
                World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                home = fallback == null ? null : fallback.getSpawnLocation();
            }
            if (home != null) {
                player.teleport(home);
                Text.msg(player, "<gray>You were returned from the Illusory Realm.</gray>");
            }
        }, 20L);
    }

    // ---- location serialisation ------------------------------------------

    static String serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return location.getWorld().getName() + ";" + location.getX() + ";" + location.getY() + ";"
                + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch();
    }

    static Location deserialize(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(";");
        if (parts.length < 4) {
            return null;
        }
        World target = Bukkit.getWorld(parts[0]);
        if (target == null) {
            return null;
        }
        try {
            Location location = new Location(target,
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]));
            if (parts.length >= 6) {
                location.setYaw(Float.parseFloat(parts[4]));
                location.setPitch(Float.parseFloat(parts[5]));
            }
            return location;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---- one running domain ----------------------------------------------

    private static final class Domain {
        private final UUID owner;
        private final int slot;
        private final int originX;
        private final int originZ;
        private final Runnable onClose;
        /** participant -> where they were standing when they were taken. */
        private final Map<UUID, Location> returns = new LinkedHashMap<>();
        private final Set<UUID> alive = new LinkedHashSet<>();
        private final List<Location> spawnPoints = new ArrayList<>();
        private long endAt;
        private int startedWith;
        private BukkitTask task;
        private boolean closing;

        private Domain(UUID owner, int slot, int originX, int originZ, Runnable onClose) {
            this.owner = owner;
            this.slot = slot;
            this.originX = originX;
            this.originZ = originZ;
            this.onClose = onClose;
        }
    }
}
