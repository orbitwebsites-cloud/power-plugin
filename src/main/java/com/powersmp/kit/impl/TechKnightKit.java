package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.item.MaceItem;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.menu.RestockMenu;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * TechKnightGaming: Mace Massacre.
 *
 * <p>Four powers: a soulbound mace that levels with kills, a long-cooldown utility restock,
 * on-demand XP bottles, and a passive that ignores an opponent's shield half the time. The
 * shield-ignore has no cooldown and no toggle -- it either fires on a given blocked hit or it
 * does not -- and only triggers with his own mace in hand, on him specifically.
 *
 * <p><b>The levelling ladder.</b> "1 kill density 1 and so on" runs out of vanilla at 5 kills --
 * Density caps at V. Two readings are built:
 * <ul>
 *   <li>{@code LADDER} (default): Density I-V and Wind Burst I-III climb together from kill 1, each
 *       capped at their own level; Breach I-IV unlocks once Density maxes out at kill 5. Stays inside
 *       vanilla enchantment levels and keeps escalating to kill 9.</li>
 *   <li>{@code LITERAL}: Density really does equal the kill count, past the vanilla cap, up to
 *       {@code literal-max-density}. Density scales with fall distance, so this gets absurd fast --
 *       that is the point, but it is worth knowing before switching it on.</li>
 * </ul>
 */
public class TechKnightKit implements PowerKit, Listener {

    public static final String ID = "techknight";

    private static final String ABILITY_RESTOCK = "restock";
    private static final String ABILITY_LOADOUT = "loadout";
    private static final String ABILITY_XP = "xp";
    private static final String ABILITY_EARTHBREAKER = "earthbreaker";
    private static final String ABILITY_FORTIFY = "fortify";
    private static final String ABILITY_REFLECT = "reflect_shield";
    private static final String ABILITY_SHOCKWAVE = "shockwave";
    private static final String ABILITY_OVERLOAD = "overload";
    private static final String ABILITY_DECOY = "decoy";
    private static final String ABILITY_GRAPPLE = "grapple_shot";

    private final PowerSMP plugin;
    private final RestockMenu menu;
    /** Maces pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();
    private final Set<UUID> activeEarthbreakers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, FortifySession> fortifySessions = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> reflectTasks = new ConcurrentHashMap<>();
    private final Set<UUID> reflectingDamage = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> overloadUntil = new ConcurrentHashMap<>();
    private final Map<UUID, DecoySession> decoys = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> grapples = new ConcurrentHashMap<>();

    // Tuning
    private boolean ladderMode = true;
    private int literalMaxDensity = 10;
    private boolean countPlayerKills = true;
    private boolean countMobKills = true;
    private boolean requireMaceInHand = true;
    private boolean maceUnbreakable = true;

    private double restockCooldown = 18000.0d;
    private final List<ItemStack> restockItems = new ArrayList<>();

    private boolean xpFillInventory = true;
    private int xpMaxStacks = 36;

    private double earthbreakerLeapPower = 1.3d;
    private int earthbreakerDelayTicks = 12;
    private double earthbreakerRadius = 5.0d;
    private double earthbreakerRadiusPerLevel = 0.2d;
    private double earthbreakerDamage = 6.0d;
    private double earthbreakerDamagePerLevel = 0.4d;
    private int earthbreakerScalingCap = 10;
    private double earthbreakerKnockup = 0.8d;
    private double earthbreakerCooldown = 25.0d;

    private boolean shieldBreakEnabled = true;
    private double shieldBreakChance = 0.5d;

    private int fortifyDurationTicks = 160;
    private int fortifyResistanceAmplifier = 1;
    private double fortifyKnockbackResistance = 0.75d;
    private double fortifyCooldown = 30.0d;

    private int reflectDurationTicks = 200;
    private double reflectRatio = 0.5d;
    private double reflectCooldown = 40.0d;

    private double shockwaveRange = 10.0d;
    private double shockwaveHalfAngleDegrees = 45.0d;
    private double shockwaveDamage = 6.0d;
    private double shockwaveKnockup = 0.9d;
    private double shockwaveCooldown = 20.0d;

    private int overloadWindowTicks = 400;
    private double overloadDamageMultiplier = 2.0d;
    private int overloadWitherTicks = 100;
    private int overloadWitherAmplifier;
    private double overloadCooldown = 45.0d;

    private int decoyDurationTicks = 200;
    private double decoyTauntRadius = 16.0d;
    private double decoyCooldown = 30.0d;

    private double grappleRange = 24.0d;
    private double grapplePower = 1.35d;
    private int grapplePulseTicks = 30;
    private double grappleCooldown = 15.0d;

    public TechKnightKit(PowerSMP plugin) {
        this.plugin = plugin;
        this.menu = new RestockMenu(plugin);
    }

    /** Registered as a listener by the plugin's main class. */
    public RestockMenu menu() {
        return menu;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mace Massacre";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection mace = section.getConfigurationSection("mace");
        if (mace != null) {
            ladderMode = !"LITERAL".equalsIgnoreCase(mace.getString("mode", "LADDER"));
            literalMaxDensity = Math.max(1, mace.getInt("literal-max-density", literalMaxDensity));
            countPlayerKills = mace.getBoolean("count-player-kills", true);
            countMobKills = mace.getBoolean("count-mob-kills", true);
            requireMaceInHand = mace.getBoolean("require-mace-in-hand", true);
            maceUnbreakable = mace.getBoolean("unbreakable", true);
        }

        ConfigurationSection restock = section.getConfigurationSection("restock");
        restockItems.clear();
        if (restock != null) {
            restockCooldown = restock.getDouble("cooldown-seconds", restockCooldown);
            menu.slots(restock.getInt("slots", 7));
            for (String entry : restock.getStringList("default-items")) {
                ItemStack parsed = parseItem(entry);
                if (parsed != null) {
                    restockItems.add(parsed);
                }
            }
        }

        ConfigurationSection xp = section.getConfigurationSection("xp-bottles");
        if (xp != null) {
            xpFillInventory = xp.getBoolean("fill-inventory", true);
            xpMaxStacks = Math.max(1, xp.getInt("max-stacks", xpMaxStacks));
        }

        ConfigurationSection earthbreaker = section.getConfigurationSection("earthbreaker");
        if (earthbreaker != null) {
            earthbreakerLeapPower = earthbreaker.getDouble("leap-power", earthbreakerLeapPower);
            earthbreakerDelayTicks = earthbreaker.getInt("delay-ticks", earthbreakerDelayTicks);
            earthbreakerRadius = earthbreaker.getDouble("radius", earthbreakerRadius);
            earthbreakerRadiusPerLevel = earthbreaker.getDouble("radius-per-level", earthbreakerRadiusPerLevel);
            earthbreakerDamage = earthbreaker.getDouble("damage", earthbreakerDamage);
            earthbreakerDamagePerLevel = earthbreaker.getDouble("damage-per-level", earthbreakerDamagePerLevel);
            earthbreakerScalingCap = Math.max(0, earthbreaker.getInt("scaling-cap", earthbreakerScalingCap));
            earthbreakerKnockup = earthbreaker.getDouble("knockup-power", earthbreakerKnockup);
            earthbreakerCooldown = earthbreaker.getDouble("cooldown-seconds", earthbreakerCooldown);
        }

        ConfigurationSection shieldBreak = section.getConfigurationSection("shield-break");
        if (shieldBreak != null) {
            shieldBreakEnabled = shieldBreak.getBoolean("enabled", true);
            shieldBreakChance = Math.max(0.0d, Math.min(1.0d,
                    shieldBreak.getDouble("chance", shieldBreakChance)));
        }

        ConfigurationSection fortify = section.getConfigurationSection("fortify");
        if (fortify != null) {
            fortifyDurationTicks = Math.max(1,
                    fortify.getInt("duration-seconds", fortifyDurationTicks / 20) * 20);
            fortifyResistanceAmplifier = Math.max(0,
                    fortify.getInt("resistance-amplifier", fortifyResistanceAmplifier));
            fortifyKnockbackResistance = Math.max(0.0d, Math.min(1.0d,
                    fortify.getDouble("knockback-resistance", fortifyKnockbackResistance)));
            fortifyCooldown = Math.max(0.0d,
                    fortify.getDouble("cooldown-seconds", fortifyCooldown));
        }

        ConfigurationSection reflect = section.getConfigurationSection("reflect-shield");
        if (reflect != null) {
            reflectDurationTicks = Math.max(1,
                    reflect.getInt("duration-seconds", reflectDurationTicks / 20) * 20);
            reflectRatio = Math.max(0.0d,
                    reflect.getDouble("damage-ratio", reflectRatio));
            reflectCooldown = Math.max(0.0d,
                    reflect.getDouble("cooldown-seconds", reflectCooldown));
        }

        ConfigurationSection shockwave = section.getConfigurationSection("shockwave");
        if (shockwave != null) {
            shockwaveRange = Math.max(1.0d,
                    shockwave.getDouble("range", shockwaveRange));
            shockwaveHalfAngleDegrees = Math.max(1.0d, Math.min(180.0d,
                    shockwave.getDouble("half-angle-degrees", shockwaveHalfAngleDegrees)));
            shockwaveDamage = Math.max(0.0d,
                    shockwave.getDouble("damage", shockwaveDamage));
            shockwaveKnockup = Math.max(0.0d,
                    shockwave.getDouble("knockup-power", shockwaveKnockup));
            shockwaveCooldown = Math.max(0.0d,
                    shockwave.getDouble("cooldown-seconds", shockwaveCooldown));
        }

        ConfigurationSection overload = section.getConfigurationSection("overload");
        if (overload != null) {
            overloadWindowTicks = Math.max(1,
                    overload.getInt("window-seconds", overloadWindowTicks / 20) * 20);
            overloadDamageMultiplier = Math.max(1.0d,
                    overload.getDouble("damage-multiplier", overloadDamageMultiplier));
            overloadWitherTicks = Math.max(0,
                    overload.getInt("wither-seconds", overloadWitherTicks / 20) * 20);
            overloadWitherAmplifier = Math.max(0,
                    overload.getInt("wither-amplifier", overloadWitherAmplifier));
            overloadCooldown = Math.max(0.0d,
                    overload.getDouble("cooldown-seconds", overloadCooldown));
        }

        ConfigurationSection decoy = section.getConfigurationSection("decoy");
        if (decoy != null) {
            decoyDurationTicks = Math.max(1,
                    decoy.getInt("duration-seconds", decoyDurationTicks / 20) * 20);
            decoyTauntRadius = Math.max(1.0d,
                    decoy.getDouble("taunt-radius", decoyTauntRadius));
            decoyCooldown = Math.max(0.0d,
                    decoy.getDouble("cooldown-seconds", decoyCooldown));
        }

        ConfigurationSection grapple = section.getConfigurationSection("grapple-shot");
        if (grapple != null) {
            grappleRange = Math.max(1.0d,
                    grapple.getDouble("range", grappleRange));
            grapplePower = Math.max(0.0d,
                    grapple.getDouble("pull-power", grapplePower));
            grapplePulseTicks = Math.max(1,
                    grapple.getInt("pulse-ticks", grapplePulseTicks));
            grappleCooldown = Math.max(0.0d,
                    grapple.getDouble("cooldown-seconds", grappleCooldown));
        }

        plugin.cooldowns().registerLabel(ABILITY_RESTOCK, "Restock");
        // Five hours is far longer than a server uptime; without this a restart is a free use.
        plugin.cooldowns().registerPersistent(ABILITY_RESTOCK);
        plugin.cooldowns().registerLabel(ABILITY_EARTHBREAKER, "Earthbreaker");
        plugin.cooldowns().registerLabel(ABILITY_FORTIFY, "Fortify");
        plugin.cooldowns().registerLabel(ABILITY_REFLECT, "Reflect Shield");
        plugin.cooldowns().registerLabel(ABILITY_SHOCKWAVE, "Shockwave");
        plugin.cooldowns().registerLabel(ABILITY_OVERLOAD, "Overload");
        plugin.cooldowns().registerLabel(ABILITY_DECOY, "Decoy");
        plugin.cooldowns().registerLabel(ABILITY_GRAPPLE, "Grapple Shot");
    }

    /** Parses {@code "ENDER_PEARL:16"}. */
    private ItemStack parseItem(String entry) {
        String[] parts = entry.split(":", 2);
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null) {
            plugin.getLogger().warning("Unknown restock item '" + entry + "' in kits.yml");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Bad amount in restock entry '" + entry + "'; using 1");
            }
        }
        return new ItemStack(material, amount);
    }

    // ---- the mace -------------------------------------------------------

    private MaceItem.Levels levelsFor(int kills) {
        if (!ladderMode) {
            return new MaceItem.Levels(Math.min(kills, literalMaxDensity), 0, 0);
        }
        int density = Math.min(kills, 5);
        int breach = clamp(kills - 5, 0, 4);
        // Wind Burst now climbs alongside Density from kill 1 (capped at III, same as before) --
        // it used to wait for Density and Breach to both max out first, gating it behind kill 10.
        int windBurst = Math.min(kills, 3);
        return new MaceItem.Levels(density, breach, windBurst);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Gives the mace if it is missing, or re-syncs its level if it has drifted from player data. */
    public void ensureMace(Player owner) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        int kills = data.maceKills();

        for (ItemStack item : owner.getInventory().getContents()) {
            if (owner.getUniqueId().equals(MaceItem.ownerOf(item))) {
                if (MaceItem.killsOf(item) != kills) {
                    MaceItem.apply(item, kills, levelsFor(kills));
                }
                return;
            }
        }
        ItemStack mace = MaceItem.create(owner.getUniqueId(), kills, levelsFor(kills), maceUnbreakable);
        // Never drop a soulbound replacement: the next shared tick retries once room exists.
        owner.getInventory().addItem(mace);
    }

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.MACE_MASSACRE)) {
            ensureMace(owner);
        }
    }

    @Override
    public void onJoin(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.MACE_MASSACRE)) {
            ensureMace(owner);
        }
    }

    /** Every kill levels the mace. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.MACE_MASSACRE)) {
            return;
        }
        boolean victimIsPlayer = event.getEntity() instanceof Player;
        if (victimIsPlayer ? !countPlayerKills : !countMobKills) {
            return;
        }
        ItemStack held = killer.getInventory().getItemInMainHand();
        if (requireMaceInHand && !killer.getUniqueId().equals(MaceItem.ownerOf(held))) {
            return;
        }

        PlayerData data = plugin.data().get(killer.getUniqueId());
        int before = data.maceKills();
        data.maceKills(before + 1);
        plugin.data().markDirty();

        MaceItem.Levels was = levelsFor(before);
        MaceItem.Levels now = levelsFor(data.maceKills());

        ItemStack mace = killer.getUniqueId().equals(MaceItem.ownerOf(held)) ? held : findMace(killer);
        if (mace != null) {
            MaceItem.apply(mace, data.maceKills(), now);
        }
        if (!was.equals(now)) {
            Text.msg(killer, "<gold>Massacre</gold> <gray>-- " + describe(now) + "</gray>");
            killer.playSound(killer.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.5f);
            killer.getWorld().spawnParticle(Particle.CRIT, killer.getLocation().add(0, 1, 0), 25, 0.3, 0.3, 0.3, 0.25);
        } else {
            Text.actionBar(killer, "<gray>Massacre: " + data.maceKills() + " kills</gray>");
        }
    }

    /**
     * Passive, no cooldown: half the time, hitting a blocking player with the soulbound mace
     * ignores the shield entirely -- same as an axe disabling a shield, except any weapon works
     * here because it only triggers off <em>this specific mace</em>, not the weapon type. Only
     * fires for TechKnightGaming himself, and only while his actual mace is in hand.
     *
     * <p>Shield blocking is resolved by the server before this event ever reaches a plugin, so the
     * damage Bukkit hands us here is already reduced. There is no public API to recover the
     * pre-block number, so on a trigger the mace's own attack-damage attribute is reapplied
     * directly -- a full, unblocked hit in all but name -- and {@code clearActiveItem()} drops the
     * target out of their block stance, which is what actually makes it read as "ignored" rather
     * than just doing more damage while they are still visibly guarding.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldBreak(EntityDamageByEntityEvent event) {
        if (!shieldBreakEnabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player killer) || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(killer, Power.MACE_MASSACRE)) {
            return;
        }
        if (!killer.getUniqueId().equals(
                MaceItem.ownerOf(killer.getInventory().getItemInMainHand()))) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || !victim.isBlocking()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= shieldBreakChance) {
            return;
        }

        victim.clearActiveItem();
        event.setDamage(Attributes.valueOf(killer, Attributes.ATTACK_DAMAGE, event.getDamage()));

        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 0.8f);
        Text.actionBar(killer, "<gold>Shield ignored.</gold>");
        Text.actionBar(victim, "<red>Your shield did nothing.</red>");
    }

    private String describe(MaceItem.Levels levels) {
        List<String> parts = new ArrayList<>();
        if (levels.density() > 0) {
            parts.add("Density " + MaceItem.numeral(levels.density()));
        }
        if (levels.breach() > 0) {
            parts.add("Breach " + MaceItem.numeral(levels.breach()));
        }
        if (levels.windBurst() > 0) {
            parts.add("Wind Burst " + MaceItem.numeral(levels.windBurst()));
        }
        return parts.isEmpty() ? "no enchantments yet" : String.join(", ", parts);
    }

    private ItemStack findMace(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (owner.getUniqueId().equals(MaceItem.ownerOf(item))) {
                return item;
            }
        }
        return null;
    }

    // ---- "can't be taken away, even if I die" ---------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        // With keepInventory on, drops are empty and the mace never leaves -- nothing to do.
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (MaceItem.isSoulbound(drop)) {
                deathStash.put(player.getUniqueId(), drop.clone());
                it.remove();
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ItemStack stashed = deathStash.remove(player.getUniqueId());
        // Curse of Vanishing means the mace usually never reaches getDrops() in the first place --
        // there is nothing in the stash to restore -- so ensureMace() is the fallback that actually
        // hands it back in that case. Respawn inventory is not populated until after this event
        // resolves, so both paths wait a tick.
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                if (plugin.unlocks().isUnlocked(player, Power.MACE_MASSACRE)) {
                    ensureMace(player);
                }
                return;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stashed);
            if (!leftover.isEmpty()) {
                Text.msg(player, "<yellow>Your mace is waiting -- free an inventory slot.");
            }
            Text.msg(player, "<gray>Your mace came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (MaceItem.isSoulbound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your mace will not leave you.</red>");
        }
    }

    /** Stops the mace being stashed in a chest, given away, or dropped into another inventory. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return; // The player's own inventory screen -- rearranging is fine.
        }
        if (MaceItem.isSoulbound(event.getCurrentItem()) || MaceItem.isSoulbound(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING
                && MaceItem.isSoulbound(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- Earthbreaker -----------------------------------------------------

    /**
     * Leap, then slam. A fixed delay rather than watching for landing -- same call as the spear's
     * lunge-then-stun -- keeps this simple and correct even if he leaps off a ledge or into water,
     * where "have I landed yet" gets genuinely ambiguous.
     *
     * <p>Radius and damage scale with the same {@code maceKills} counter Density already rides, up to
     * {@code scaling-cap}, so this grows alongside the rest of the kit instead of being a flat number
     * bolted on next to it.
     */
    private boolean earthbreaker(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.EARTHBREAKER)) {
            return plugin.unlocks().denyLocked(owner, Power.EARTHBREAKER);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_EARTHBREAKER, earthbreakerCooldown)) {
            return false;
        }

        // Vanilla's own movement check does not know the leap is deliberate and will snap him back
        // mid-air without this. Ends in slam(), which fires exactly when the leap's hang time ends.
        com.powersmp.util.MovementExemption.begin(owner);
        activeEarthbreakers.add(owner.getUniqueId());
        owner.setVelocity(new Vector(0.0d, earthbreakerLeapPower, 0.0d));
        owner.setFallDistance(0.0f);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.6f);

        Bukkit.getScheduler().runTaskLater(plugin, () -> slam(owner), earthbreakerDelayTicks);
        return true;
    }

    private void slam(Player owner) {
        activeEarthbreakers.remove(owner.getUniqueId());
        com.powersmp.util.MovementExemption.end(owner);
        if (!owner.isOnline() || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.EARTHBREAKER)) {
            return;
        }
        int level = Math.min(earthbreakerScalingCap, plugin.data().get(owner.getUniqueId()).maceKills());
        double radius = earthbreakerRadius + level * earthbreakerRadiusPerLevel;
        double damage = earthbreakerDamage + level * earthbreakerDamagePerLevel;

        for (Entity nearby : owner.getNearbyEntities(radius, radius, radius)) {
            if (nearby.equals(owner) || !(nearby instanceof LivingEntity target)) {
                continue;
            }
            target.damage(damage, owner);
            Vector knockup = target.getVelocity();
            target.setVelocity(new Vector(knockup.getX(), Math.max(earthbreakerKnockup, knockup.getY()), knockup.getZ()));
            // Only a player has a client whose movement report vanilla scrutinises.
            if (target instanceof Player targetPlayer) {
                com.powersmp.util.MovementExemption.begin(targetPlayer);
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> com.powersmp.util.MovementExemption.end(targetPlayer), 15L);
            }
        }

        owner.getWorld().spawnParticle(Particle.EXPLOSION, owner.getLocation(),
                Math.max(1, (int) radius), 0.0, 0.0, 0.0, 0.0);
        for (double angle = 0.0d; angle < 360.0d; angle += 15.0d) {
            double radians = Math.toRadians(angle);
            owner.getWorld().spawnParticle(Particle.CLOUD,
                    owner.getLocation().add(Math.cos(radians) * radius, 0.1d, Math.sin(radians) * radius),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f);
        Text.actionBar(owner, "<gold><bold>EARTHBREAKER</bold></gold>");
    }

    // ---- Fortify --------------------------------------------------------

    private boolean fortify(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.FORTIFY)) {
            return plugin.unlocks().denyLocked(owner, Power.FORTIFY);
        }
        if (fortifySessions.containsKey(owner.getUniqueId())) {
            Text.actionBar(owner, "<gray>Fortify is already active.</gray>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_FORTIFY, fortifyCooldown)) {
            return false;
        }
        UUID id = owner.getUniqueId();
        FortifySession session = new FortifySession(owner.getPotionEffect(PotionEffectType.RESISTANCE));
        fortifySessions.put(id, session);
        Effects.apply(owner, PotionEffectType.RESISTANCE,
                fortifyDurationTicks + 5, fortifyResistanceAmplifier);
        Attributes.set(owner, Attributes.KNOCKBACK_RESISTANCE,
                Keys.TECH_FORTIFY_KNOCKBACK, fortifyKnockbackResistance);
        owner.getWorld().spawnParticle(Particle.BLOCK, owner.getLocation().add(0.0d, 1.0d, 0.0d),
                50, 0.5d, 0.8d, 0.5d, Material.IRON_BLOCK.createBlockData());
        owner.playSound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.7f);
        Text.msg(owner, "<gray><bold>FORTIFY</bold></gray> <gray>-- armour systems locked.</gray>");
        session.task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> finishFortify(owner), fortifyDurationTicks);
        return true;
    }

    private void finishFortify(Player owner) {
        FortifySession session = fortifySessions.remove(owner.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null && !session.task.isCancelled()) {
            session.task.cancel();
        }
        Attributes.clear(owner, Attributes.KNOCKBACK_RESISTANCE, Keys.TECH_FORTIFY_KNOCKBACK);
        owner.removePotionEffect(PotionEffectType.RESISTANCE);
        if (session.previousResistance != null) {
            owner.addPotionEffect(session.previousResistance);
        }
    }

    // ---- Reflect Shield -------------------------------------------------

    private boolean reflectShield(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.REFLECT_SHIELD)) {
            return plugin.unlocks().denyLocked(owner, Power.REFLECT_SHIELD);
        }
        if (reflectTasks.containsKey(owner.getUniqueId())) {
            Text.actionBar(owner, "<aqua>Reflect Shield is already active.</aqua>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_REFLECT, reflectCooldown)) {
            return false;
        }
        UUID id = owner.getUniqueId();
        owner.getWorld().spawnParticle(Particle.ENCHANT, owner.getLocation().add(0.0d, 1.0d, 0.0d),
                50, 0.8d, 1.0d, 0.8d, 0.4d);
        owner.playSound(owner.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.4f);
        Text.msg(owner, "<aqua><bold>REFLECT SHIELD</bold></aqua> <gray>-- retaliation online.</gray>");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> reflectTasks.remove(id), reflectDurationTicks);
        reflectTasks.put(id, task);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReflectedDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner)
                || reflectingDamage.contains(owner.getUniqueId())
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.REFLECT_SHIELD)
                || !reflectTasks.containsKey(owner.getUniqueId())) {
            return;
        }
        LivingEntity attacker = damageSource(event.getDamager());
        if (attacker == null || attacker.equals(owner)) {
            return;
        }
        double reflected = event.getFinalDamage() * reflectRatio;
        if (reflected <= 0.0d) {
            return;
        }
        reflectingDamage.add(attacker.getUniqueId());
        try {
            attacker.damage(reflected, owner);
        } finally {
            reflectingDamage.remove(attacker.getUniqueId());
        }
        attacker.getWorld().spawnParticle(Particle.CRIT, attacker.getLocation().add(0.0d, 1.0d, 0.0d),
                15, 0.3d, 0.5d, 0.3d, 0.2d);
        owner.playSound(owner.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.7f, 1.8f);
    }

    private LivingEntity damageSource(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    // ---- Shockwave ------------------------------------------------------

    private boolean shockwave(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SHOCKWAVE)) {
            return plugin.unlocks().denyLocked(owner, Power.SHOCKWAVE);
        }
        if (!owner.isOnGround()) {
            Text.msg(owner, "<red>Shockwave must be fired from the ground.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_SHOCKWAVE, shockwaveCooldown)) {
            return false;
        }
        Vector forward = owner.getEyeLocation().getDirection().setY(0.0d);
        if (forward.lengthSquared() < 1.0e-4) {
            forward = new Vector(0.0d, 0.0d, 1.0d);
        }
        forward.normalize();
        double minimumDot = Math.cos(Math.toRadians(shockwaveHalfAngleDegrees));
        int hit = 0;
        for (Entity nearby : owner.getNearbyEntities(shockwaveRange, 4.0d, shockwaveRange)) {
            if (!(nearby instanceof LivingEntity target) || target.equals(owner)
                    || !owner.hasLineOfSight(target)) {
                continue;
            }
            Vector toward = target.getLocation().toVector()
                    .subtract(owner.getLocation().toVector()).setY(0.0d);
            double distance = toward.length();
            if (distance < 1.0e-4 || distance > shockwaveRange
                    || toward.normalize().dot(forward) < minimumDot) {
                continue;
            }
            target.damage(shockwaveDamage, owner);
            Vector velocity = target.getVelocity();
            velocity.setY(Math.max(shockwaveKnockup, velocity.getY()));
            target.setVelocity(velocity);
            exemptMovement(target, 15L);
            hit++;
        }
        for (double distance = 1.0d; distance <= shockwaveRange; distance += 0.65d) {
            Location point = owner.getLocation().add(forward.clone().multiply(distance));
            owner.getWorld().spawnParticle(Particle.CLOUD, point.add(0.0d, 0.15d, 0.0d),
                    4, distance * 0.05d, 0.08d, distance * 0.05d, 0.03d);
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.4f);
        Text.actionBar(owner, "<gold><bold>SHOCKWAVE</bold></gold> <gray>" + hit + " hit</gray>");
        return true;
    }

    // ---- Overload -------------------------------------------------------

    private boolean overload(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.OVERLOAD)) {
            return plugin.unlocks().denyLocked(owner, Power.OVERLOAD);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_OVERLOAD, overloadCooldown)) {
            return false;
        }
        overloadUntil.put(owner.getUniqueId(),
                System.currentTimeMillis() + overloadWindowTicks * 50L);
        owner.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                owner.getLocation().add(0.0d, 1.0d, 0.0d), 45,
                0.5d, 0.8d, 0.5d, 0.08d);
        owner.playSound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.6f);
        Text.msg(owner, "<yellow><bold>OVERLOAD</bold></yellow> <gray>-- next mace hit empowered.</gray>");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOverloadHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player owner)
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.OVERLOAD)
                || !owner.getUniqueId().equals(
                        MaceItem.ownerOf(owner.getInventory().getItemInMainHand()))
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        Long until = overloadUntil.get(owner.getUniqueId());
        if (until == null) {
            return;
        }
        if (until < System.currentTimeMillis()) {
            overloadUntil.remove(owner.getUniqueId());
            return;
        }
        overloadUntil.remove(owner.getUniqueId());
        event.setDamage(event.getDamage() * overloadDamageMultiplier);
        Effects.apply(target, PotionEffectType.WITHER,
                overloadWitherTicks, overloadWitherAmplifier);
        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                target.getLocation().add(0.0d, 1.0d, 0.0d), 35,
                0.4d, 0.7d, 0.4d, 0.12d);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 1.6f);
    }

    // ---- Decoy ----------------------------------------------------------

    private boolean decoy(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.DECOY)) {
            return plugin.unlocks().denyLocked(owner, Power.DECOY);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_DECOY, decoyCooldown)) {
            return false;
        }
        removeDecoy(owner.getUniqueId());
        ArmorStand stand = owner.getWorld().spawn(owner.getLocation(), ArmorStand.class, spawned -> {
            spawned.setInvisible(true);
            spawned.setInvulnerable(true);
            spawned.setGravity(false);
            spawned.setArms(true);
            spawned.setBasePlate(false);
            spawned.setCollidable(false);
            spawned.customName(Text.mm("<aqua>Tech Decoy</aqua>"));
            spawned.setCustomNameVisible(true);
            spawned.getEquipment().setArmorContents(owner.getInventory().getArmorContents());
            spawned.getEquipment().setItemInMainHand(
                    owner.getInventory().getItemInMainHand().clone());
        });
        UUID ownerId = owner.getUniqueId();
        DecoySession session = new DecoySession(stand);
        decoys.put(ownerId, session);
        session.task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if ((elapsed += 10) > decoyDurationTicks || !owner.isOnline()
                        || !stand.isValid() || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.DECOY)) {
                    cancel();
                    removeDecoy(ownerId);
                    return;
                }
                for (Entity nearby : stand.getNearbyEntities(
                        decoyTauntRadius, decoyTauntRadius, decoyTauntRadius)) {
                    if (nearby instanceof Mob mob) {
                        mob.setTarget(stand);
                    }
                }
                stand.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        stand.getLocation().add(0.0d, 1.0d, 0.0d),
                        4, 0.25d, 0.5d, 0.25d, 0.02d);
            }
        }.runTaskTimer(plugin, 0L, 10L);
        owner.playSound(owner.getLocation(), Sound.ENTITY_ARMOR_STAND_PLACE, 1.0f, 1.5f);
        Text.msg(owner, "<aqua><bold>DECOY DEPLOYED</bold></aqua>");
        return true;
    }

    private void removeDecoy(UUID ownerId) {
        DecoySession session = decoys.remove(ownerId);
        if (session == null) {
            return;
        }
        if (session.task != null && !session.task.isCancelled()) {
            session.task.cancel();
        }
        ArmorStand stand = session.stand;
        if (stand.isValid()) {
            for (Entity nearby : stand.getNearbyEntities(
                    decoyTauntRadius, decoyTauntRadius, decoyTauntRadius)) {
                if (nearby instanceof Mob mob && stand.equals(mob.getTarget())) {
                    mob.setTarget(null);
                }
            }
            stand.remove();
        }
    }

    // ---- Grapple Shot ---------------------------------------------------

    private boolean grappleShot(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.GRAPPLE_SHOT)) {
            return plugin.unlocks().denyLocked(owner, Power.GRAPPLE_SHOT);
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        RayTraceResult entityTrace = owner.getWorld().rayTraceEntities(
                eye, direction, grappleRange, 0.6d,
                entity -> entity instanceof LivingEntity && !entity.equals(owner));
        Entity target = entityTrace == null ? null : entityTrace.getHitEntity();
        if (target != null && !owner.hasLineOfSight(target)) {
            target = null;
        }
        Location anchor = null;
        if (target == null) {
            org.bukkit.block.Block block = owner.getTargetBlockExact((int) Math.ceil(grappleRange));
            if (block != null) {
                anchor = block.getLocation().add(0.5d, 0.5d, 0.5d);
            }
        }
        if (target == null && anchor == null) {
            Text.msg(owner, "<red>No grapple target in range.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_GRAPPLE, grappleCooldown)) {
            return false;
        }
        UUID id = owner.getUniqueId();
        BukkitTask previous = grapples.remove(id);
        if (previous != null) {
            previous.cancel();
            MovementExemption.end(owner);
        }
        final Entity lockedTarget = target;
        final Location fixedAnchor = anchor;
        MovementExemption.begin(owner);
        owner.playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        BukkitTask task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (++elapsed > grapplePulseTicks || !owner.isOnline()
                        || !plugin.kits().isOwner(owner, ID)
                        || !plugin.unlocks().isUnlocked(owner, Power.GRAPPLE_SHOT)
                        || (lockedTarget != null && !lockedTarget.isValid())) {
                    stopGrapple(owner, this);
                    return;
                }
                Location destination = lockedTarget == null
                        ? fixedAnchor : lockedTarget.getLocation().add(0.0d, 1.0d, 0.0d);
                Vector pull = destination.toVector().subtract(owner.getLocation().toVector());
                if (pull.lengthSquared() < 2.25d) {
                    stopGrapple(owner, this);
                    return;
                }
                drawGrapple(owner.getEyeLocation(), destination);
                owner.setVelocity(pull.normalize().multiply(grapplePower));
                owner.setFallDistance(0.0f);
            }
        }.runTaskTimer(plugin, 0L, 1L);
        grapples.put(id, task);
        return true;
    }

    private void stopGrapple(Player owner, BukkitRunnable runnable) {
        runnable.cancel();
        grapples.remove(owner.getUniqueId());
        MovementExemption.end(owner);
    }

    private void drawGrapple(Location from, Location to) {
        Vector line = to.toVector().subtract(from.toVector());
        double length = line.length();
        if (length < 1.0e-4) {
            return;
        }
        line.normalize();
        for (double distance = 0.0d; distance <= length; distance += 0.65d) {
            from.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                    from.clone().add(line.clone().multiply(distance)),
                    1, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    private void exemptMovement(LivingEntity entity, long ticks) {
        if (entity instanceof Player player) {
            MovementExemption.begin(player);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> MovementExemption.end(player), ticks);
        }
    }

    @Override
    public void onQuit(Player owner) {
        if (activeEarthbreakers.remove(owner.getUniqueId())) {
            com.powersmp.util.MovementExemption.end(owner);
        }
        UUID id = owner.getUniqueId();
        finishFortify(owner);
        BukkitTask reflect = reflectTasks.remove(id);
        if (reflect != null) {
            reflect.cancel();
        }
        overloadUntil.remove(id);
        removeDecoy(id);
        BukkitTask grapple = grapples.remove(id);
        if (grapple != null) {
            grapple.cancel();
            MovementExemption.end(owner);
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.EARTHBREAKER && activeEarthbreakers.remove(owner.getUniqueId())) {
            com.powersmp.util.MovementExemption.end(owner);
        } else if (power == Power.FORTIFY) {
            finishFortify(owner);
        } else if (power == Power.REFLECT_SHIELD) {
            BukkitTask task = reflectTasks.remove(owner.getUniqueId());
            if (task != null) {
                task.cancel();
            }
        } else if (power == Power.OVERLOAD) {
            overloadUntil.remove(owner.getUniqueId());
        } else if (power == Power.DECOY) {
            removeDecoy(owner.getUniqueId());
        } else if (power == Power.GRAPPLE_SHOT) {
            BukkitTask task = grapples.remove(owner.getUniqueId());
            if (task != null) {
                task.cancel();
                MovementExemption.end(owner);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                onQuit(player);
            }
        }
        decoys.keySet().forEach(this::removeDecoy);
        reflectTasks.values().forEach(BukkitTask::cancel);
        grapples.values().forEach(BukkitTask::cancel);
        fortifySessions.clear();
        reflectTasks.clear();
        grapples.clear();
        overloadUntil.clear();
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_RESTOCK, "Restock",
                        "Refill your kit. " + (int) (restockCooldown / 3600) + "h cooldown."),
                new Ability(ABILITY_LOADOUT, "Restock Loadout",
                        "Choose what Restock gives you -- " + menu.slots() + " slots, anything you like."),
                new Ability(ABILITY_XP, "XP Bottles",
                        "Fill your inventory with experience bottles. No cooldown."),
                new Ability(ABILITY_EARTHBREAKER, "Earthbreaker",
                        "Leap up and slam down, damaging and launching everyone nearby."),
                new Ability(ABILITY_FORTIFY, "Fortify",
                        "Resistance II and heavy knockback resistance for "
                                + fortifyDurationTicks / 20 + "s."),
                new Ability(ABILITY_REFLECT, "Reflect Shield",
                        "Reflect " + (int) Math.round(reflectRatio * 100.0d)
                                + "% of incoming damage for " + reflectDurationTicks / 20 + "s."),
                new Ability(ABILITY_SHOCKWAVE, "Shockwave",
                        "Send a damaging, upward-launching wave in front of you."),
                new Ability(ABILITY_OVERLOAD, "Overload",
                        "Your next mace hit within " + overloadWindowTicks / 20
                                + "s deals double damage and Wither I."),
                new Ability(ABILITY_DECOY, "Decoy",
                        "Deploy an armour hologram that taunts nearby mobs."),
                new Ability(ABILITY_GRAPPLE, "Grapple Shot",
                        "Pull yourself toward a block or entity up to "
                                + (int) grappleRange + " blocks away."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_RESTOCK;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case ABILITY_RESTOCK -> restock(owner);
            case ABILITY_LOADOUT -> openLoadout(owner);
            case ABILITY_XP -> xpBottles(owner);
            case ABILITY_EARTHBREAKER -> earthbreaker(owner);
            case ABILITY_FORTIFY -> fortify(owner);
            case ABILITY_REFLECT -> reflectShield(owner);
            case ABILITY_SHOCKWAVE -> shockwave(owner);
            case ABILITY_OVERLOAD -> overload(owner);
            case ABILITY_DECOY -> decoy(owner);
            case ABILITY_GRAPPLE -> grappleShot(owner);
            default -> false;
        };
    }

    private boolean openLoadout(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RESTOCK)) {
            return plugin.unlocks().denyLocked(owner, Power.RESTOCK);
        }
        menu.open(owner);
        return true;
    }

    private boolean restock(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.RESTOCK)) {
            return plugin.unlocks().denyLocked(owner, Power.RESTOCK);
        }
        // A loadout the player set themselves always wins over the server default.
        List<ItemStack> kit = plugin.data().get(owner.getUniqueId()).restockLoadout();
        if (kit.isEmpty()) {
            kit = restockItems;
        }
        if (kit.isEmpty()) {
            Text.msg(owner, "<red>Your restock kit is empty. Set it with <white>/power loadout</white>.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_RESTOCK, restockCooldown)) {
            return false;
        }
        int delivered = 0;
        int dropped = 0;
        for (ItemStack template : kit) {
            Map<Integer, ItemStack> leftover = owner.getInventory().addItem(template.clone());
            delivered++;
            for (ItemStack overflow : leftover.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), overflow);
                dropped++;
            }
        }
        owner.playSound(owner.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.2f);
        owner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, owner.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.0);
        Text.msg(owner, "<green>Restocked</green> <gray>-- " + delivered + " item type(s)"
                + (dropped > 0 ? ", " + dropped + " dropped at your feet (full inventory)" : "") + ".</gray>");
        return true;
    }

    private boolean xpBottles(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.INFINITE_XP)) {
            return plugin.unlocks().denyLocked(owner, Power.INFINITE_XP);
        }
        int stacks = 0;
        if (xpFillInventory) {
            for (int slot = 0; slot < owner.getInventory().getStorageContents().length; slot++) {
                if (stacks >= xpMaxStacks) {
                    break;
                }
                ItemStack existing = owner.getInventory().getItem(slot);
                if (existing == null || existing.getType().isAir()) {
                    owner.getInventory().setItem(slot, new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
                    stacks++;
                }
            }
        } else {
            owner.getInventory().addItem(new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
            stacks = 1;
        }
        if (stacks == 0) {
            Text.msg(owner, "<red>Your inventory is full.");
            return false;
        }
        owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        owner.getWorld().spawnParticle(Particle.ENCHANT, owner.getLocation().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 1.0);
        Text.msg(owner, "<green>+" + stacks + "</green> <gray>stack(s) of experience bottles.</gray>");
        return true;
    }

    private static final class FortifySession {
        private final PotionEffect previousResistance;
        private BukkitTask task;

        private FortifySession(PotionEffect previousResistance) {
            this.previousResistance = previousResistance;
        }
    }

    private static final class DecoySession {
        private final ArmorStand stand;
        private BukkitTask task;

        private DecoySession(ArmorStand stand) {
            this.stand = stand;
        }
    }
}
