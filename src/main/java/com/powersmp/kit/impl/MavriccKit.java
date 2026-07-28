package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.event.DraconicEvolutionEvent;
import com.powersmp.item.DraconicItems;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.stance.Stance;
import com.powersmp.util.Attributes;
import com.powersmp.util.Crits;
import com.powersmp.util.Effects;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Mavricc: stances, Mushroom Affinity, Mushroom Hunger, and the achievement-gated powers.
 *
 * <p>The stance and food mechanics live in {@code StanceManager} and {@code MushroomHungerService}
 * -- this class wires them to the owner and owns the achievement layer on top.
 */
public class MavriccKit implements PowerKit, Listener {

    public static final String ID = "mavricc";

    private static final String ADV_ALL_NETHER_BIOMES = "minecraft:nether/all_biomes";
    private static final String ADV_STAR_TRADER = "minecraft:adventure/trade_at_world_height";
    private static final String ADV_VERY_VERY_FRIGHTENING = "minecraft:adventure/very_very_frightening";

    private static final String ABILITY_LAUNCH = "wither_launch";
    private static final String ABILITY_RIPTIDE = "sporic_riptide";

    private final PowerSMP plugin;

    /** Crit counter for Sporic of the Sea (red): lightning on every Nth axe crit. */
    private final Map<UUID, Integer> axeCrits = new ConcurrentHashMap<>();

    // Tuning
    private Set<Material> fungusItems = EnumSet.of(Material.RED_MUSHROOM, Material.BROWN_MUSHROOM);
    private boolean grantElytra = true;
    private double launchPower = 1.6d;
    private double launchCooldown = 45.0d;
    private final Map<String, double[]> adaptation = new HashMap<>();
    private int heroAmplifier = 1;
    private int heroAmplifierAffinity = 3;
    private int critsPerLightning = 3;
    private double riptideCooldown = 6.0d;
    private double riptidePower = 2.2d;
    private boolean draconicEnabled = true;
    private Material omeletMaterial = Material.PUMPKIN_PIE;
    private int draconicMaceBreach = 4;
    private boolean draconicMaceUnbreakable = true;
    private boolean reissueDraconicMace = true;
    /** Bound items pulled out of death drops (elytra, draconic mace), held until respawn. */
    private final Map<UUID, List<ItemStack>> deathStash = new ConcurrentHashMap<>();

    public MavriccKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Mycelial";
    }

    public void reload(ConfigurationSection mavricc) {
        if (mavricc == null) {
            return;
        }
        ConfigurationSection wings = mavricc.getConfigurationSection("wither-wings");
        if (wings != null) {
            Set<Material> parsed = EnumSet.noneOf(Material.class);
            for (String name : wings.getStringList("fungus-items")) {
                Material material = Material.matchMaterial(name);
                if (material == null) {
                    plugin.getLogger().warning("Unknown fungus item '" + name + "' in kits.yml");
                } else {
                    parsed.add(material);
                }
            }
            if (!parsed.isEmpty()) {
                fungusItems = parsed;
            }
            grantElytra = wings.getBoolean("grant-elytra", true);
            launchPower = wings.getDouble("launch-power", launchPower);
            launchCooldown = wings.getDouble("launch-cooldown-seconds", launchCooldown);
        }

        ConfigurationSection adapt = mavricc.getConfigurationSection("dimensional-adaptation");
        adaptation.clear();
        if (adapt != null) {
            for (Stance stance : Stance.values()) {
                String key = stance.configKey();
                adaptation.put(key, new double[]{
                        adapt.getDouble(key + ".scale", 1.0d),
                        adapt.getDouble(key + ".health-bonus", 0.0d)});
            }
            // Consolidation collapses the three stances, so it needs its own size and health.
            adaptation.put("consolidated", new double[]{
                    adapt.getDouble("consolidated.scale", 1.4d),
                    adapt.getDouble("consolidated.health-bonus", 10.0d)});
        }

        ConfigurationSection mind = mavricc.getConfigurationSection("sporic-mind-control");
        if (mind != null) {
            heroAmplifier = mind.getInt("hero-amplifier", heroAmplifier);
            heroAmplifierAffinity = mind.getInt("hero-amplifier-affinity", heroAmplifierAffinity);
        }
        ConfigurationSection sea = mavricc.getConfigurationSection("sporic-of-the-sea");
        if (sea != null) {
            critsPerLightning = Math.max(1, sea.getInt("red-crits-per-lightning", critsPerLightning));
            riptideCooldown = sea.getDouble("blue-riptide-cooldown-seconds", riptideCooldown);
            riptidePower = sea.getDouble("blue-riptide-power", riptidePower);
        }
        draconicEnabled = mavricc.getBoolean("draconic-evolution.enabled", true);
        ConfigurationSection draconic = mavricc.getConfigurationSection("draconic-evolution");
        if (draconic != null) {
            Material parsed = Material.matchMaterial(
                    draconic.getString("omelet-material", "PUMPKIN_PIE"));
            if (parsed != null && parsed.isEdible()) {
                omeletMaterial = parsed;
            } else if (parsed != null) {
                plugin.getLogger().warning("omelet-material '" + parsed
                        + "' is not edible; keeping " + omeletMaterial);
            }
            draconicMaceBreach = draconic.getInt("mace-breach-level", draconicMaceBreach);
            draconicMaceUnbreakable = draconic.getBoolean("mace-unbreakable", true);
            reissueDraconicMace = draconic.getBoolean("mace-reissue-if-lost", true);
        }

        plugin.cooldowns().registerLabel(ABILITY_LAUNCH, "Wither Wings");
        plugin.cooldowns().registerLabel(ABILITY_RIPTIDE, "Riptide");
    }

    // ---- lifecycle ------------------------------------------------------

    @Override
    public void onJoin(Player owner) {
        // Attribute modifiers persist in player NBT, so wipe ours before re-deriving them.
        plugin.stances().clearAttributes(owner);
        clearAdaptation(owner);
        plugin.food().scanPlayer(owner);
        if (plugin.unlocks().isUnlocked(owner, Power.WITHER_WINGS)) {
            ensureElytra(owner);
        }
        if (reissueDraconicMace && plugin.data().get(owner.getUniqueId()).stanceConsolidated()) {
            grantDraconicMace(owner);
        }
    }

    @Override
    public void onQuit(Player owner) {
        plugin.stances().clearAttributes(owner);
        clearAdaptation(owner);
        axeCrits.remove(owner.getUniqueId());
    }

    @Override
    public void onDisable() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                plugin.stances().clearAttributes(player);
                clearAdaptation(player);
            }
        }
    }

    @Override
    public void onUnlock(Player owner, Power power) {
        if (power == Power.WITHER_WINGS) {
            ensureElytra(owner);
        }
    }

    @Override
    public void tick(Player owner) {
        plugin.stances().apply(owner);

        boolean affinity = plugin.stances().hasAffinity(owner);
        Stance stance = plugin.stances().stanceOf(owner);

        if (plugin.unlocks().isUnlocked(owner, Power.DIMENSIONAL_ADAPTATION)) {
            applyAdaptation(owner, stance);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.SPORIC_MIND_CONTROL)) {
            Effects.applyInfinite(owner, org.bukkit.potion.PotionEffectType.HERO_OF_THE_VILLAGE,
                    affinity ? heroAmplifierAffinity : heroAmplifier);
        }
        if (plugin.unlocks().isUnlocked(owner, Power.SPORIC_OF_THE_SEA)
                && plugin.stances().isActive(owner, Stance.GREEN)) {
            Effects.refresh(owner, org.bukkit.potion.PotionEffectType.CONDUIT_POWER, 0);
        }
        if (grantElytra && plugin.unlocks().isUnlocked(owner, Power.WITHER_WINGS)) {
            ensureElytra(owner);
        }
    }

    // ---- Dimensional Adaptation -----------------------------------------

    private void applyAdaptation(Player owner, Stance stance) {
        double[] values = plugin.stances().isConsolidated(owner)
                ? adaptation.get("consolidated")
                : adaptation.get(stance.configKey());
        if (values == null) {
            return;
        }
        // SCALE is multiplicative around 1.0, so the modifier is the delta from normal size.
        Attributes.set(owner, Attributes.SCALE, Keys.ADAPTATION_SCALE, values[0] - 1.0d);
        Attributes.set(owner, Attributes.MAX_HEALTH, Keys.ADAPTATION_HEALTH, values[1]);

        // Shrinking max health below current health leaves the health bar over-full until the next
        // damage tick; clamp it here so the display is honest immediately.
        double max = Attributes.valueOf(owner, Attributes.MAX_HEALTH, 20.0d);
        if (owner.getHealth() > max) {
            owner.setHealth(Math.max(1.0d, max));
        }
    }

    private void clearAdaptation(Player owner) {
        Attributes.clear(owner, Attributes.SCALE, Keys.ADAPTATION_SCALE);
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.ADAPTATION_HEALTH);
    }

    // ---- Wither Wings ---------------------------------------------------

    /**
     * Custom trigger: no vanilla advancement matches "kill the Wither while holding fungus", so the
     * kill is caught directly and the killer's hands and head are checked at that moment.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Wither)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        if (!holdingFungus(killer)) {
            Text.msg(killer, "<gray>The wither dies, but you had no fungus to absorb it with.</gray>");
            return;
        }
        plugin.unlocks().unlock(killer, Power.WITHER_WINGS);
    }

    private boolean holdingFungus(Player player) {
        ItemStack[] candidates = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet()};
        for (ItemStack item : candidates) {
            if (item != null && fungusItems.contains(item.getType())) {
                return true;
            }
        }
        return false;
    }

    /** Re-issues the bound elytra if it has gone missing, so "permanent" actually means permanent. */
    private void ensureElytra(Player owner) {
        if (!grantElytra) {
            return;
        }
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isBoundElytra(item)) {
                return;
            }
        }
        ItemStack chest = owner.getInventory().getChestplate();
        if (isBoundElytra(chest)) {
            return;
        }
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        meta.displayName(Text.mm("<dark_purple>Sporeic Wither Wings</dark_purple>"));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(Keys.BOUND_ELYTRA, PersistentDataType.BYTE, (byte) 1);
        Enchants.applyVanishing(meta);
        elytra.setItemMeta(meta);

        if (chest == null || chest.getType().isAir()) {
            owner.getInventory().setChestplate(elytra);
        } else {
            owner.getInventory().addItem(elytra);
        }
    }

    private boolean isBoundElytra(ItemStack item) {
        if (item == null || item.getType() != Material.ELYTRA) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE);
    }

    private boolean isBound(ItemStack item) {
        return isBoundElytra(item) || DraconicItems.isDraconicMace(item);
    }

    // ---- "can't be taken away, even if I die" ---------------------------
    // Neither bound item (the elytra, the draconic mace) had drop/death/container guards -- only
    // the reissue-on-join. That combination is how a duplicate happens: the original ends up on
    // the ground or in a chest while ensureElytra()/grantDraconicMace(), seeing nothing bound in
    // inventory, hand out a second one. Mirrors techknight's mace protection.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        List<ItemStack> stashed = new ArrayList<>();
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (isBound(drop)) {
                stashed.add(drop.clone());
                it.remove();
            }
        }
        if (!stashed.isEmpty()) {
            deathStash.put(player.getUniqueId(), stashed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> stashed = deathStash.remove(player.getUniqueId());
        // Curse of Vanishing means there is usually nothing to restore from drops -- the elytra
        // would self-heal via tick() anyway, but the draconic mace only otherwise reissues on
        // join, so both get an immediate fallback here rather than waiting.
        org.bukkit.Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null || stashed.isEmpty()) {
                if (plugin.unlocks().isUnlocked(player, Power.WITHER_WINGS)) {
                    ensureElytra(player);
                }
                if (reissueDraconicMace && plugin.data().get(player.getUniqueId()).stanceConsolidated()) {
                    grantDraconicMace(player);
                }
                return;
            }
            for (ItemStack item : stashed) {
                HashMap<Integer, ItemStack> leftover =
                        new HashMap<>(player.getInventory().addItem(item));
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
            Text.msg(player, "<gray>Your bound items came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isBound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>That will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (isBound(event.getCurrentItem()) || isBound(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING && isBound(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ---- advancement-gated powers ---------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        String key = event.getAdvancement().getKey().toString().toLowerCase(Locale.ROOT);
        switch (key) {
            case ADV_ALL_NETHER_BIOMES -> plugin.unlocks().unlock(player, Power.DIMENSIONAL_ADAPTATION);
            case ADV_STAR_TRADER -> plugin.unlocks().unlock(player, Power.SPORIC_MIND_CONTROL);
            case ADV_VERY_VERY_FRIGHTENING -> plugin.unlocks().unlock(player, Power.SPORIC_OF_THE_SEA);
            default -> {
                // Not one of ours.
            }
        }
    }

    // ---- Sporic of the Sea (red): lightning on every Nth axe crit ---------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAxeCrit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.SPORIC_OF_THE_SEA)) {
            return;
        }
        if (!plugin.stances().isActive(player, Stance.RED)) {
            return;
        }
        Material weapon = player.getInventory().getItemInMainHand().getType();
        if (!weapon.name().endsWith("_AXE")) {
            return;
        }
        if (!Crits.isCriticalMelee(player)) {
            return;
        }
        int count = axeCrits.merge(player.getUniqueId(), 1, Integer::sum);
        if (count < critsPerLightning) {
            return;
        }
        axeCrits.put(player.getUniqueId(), 0);
        Entity target = event.getEntity();
        if (target.getWorld() != null) {
            target.getWorld().strikeLightning(target.getLocation());
        }
    }

    // ---- Draconic Evolution (stub) --------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonEggPickup(EntityPickupItemEvent event) {
        if (!draconicEnabled || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getItem().getItemStack().getType() != Material.DRAGON_EGG) {
            return;
        }
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        org.bukkit.Bukkit.getPluginManager()
                .callEvent(new DraconicEvolutionEvent(player, event.getItem().getItemStack()));
        plugin.unlocks().unlock(player, Power.DRACONIC_EVOLUTION);
        grantOmelet(player);
    }

    /** The egg becomes one omelet, once. Eating it is what actually consolidates the stances. */
    private void grantOmelet(Player owner) {
        PlayerData data = plugin.data().get(owner.getUniqueId());
        if (data.omeletGranted() || data.stanceConsolidated()) {
            return;
        }
        data.omeletGranted(true);
        plugin.data().markDirty();

        ItemStack omelet = DraconicItems.omelet(omeletMaterial);
        if (!owner.getInventory().addItem(omelet).isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), omelet);
        }
        Text.msg(owner, "<dark_purple>The egg cracks into a <light_purple>Dragon Omelet</light_purple>. "
                + "Eat it to fuse your stances.</dark_purple>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.2f);
    }

    /** Eating the omelet is the one-way door: all three stances from here on, plus the mace. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOmeletEaten(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!draconicEnabled || !plugin.kits().isOwner(player, ID)
                || !DraconicItems.isOmelet(event.getItem())) {
            return;
        }
        PlayerData data = plugin.data().get(player.getUniqueId());
        if (data.stanceConsolidated()) {
            return;
        }
        data.stanceConsolidated(true);
        plugin.data().markDirty();

        grantDraconicMace(player);

        Text.msg(player, "<gradient:#c77dff:#7b2cbf><bold>DRACONIC EVOLUTION</bold></gradient> "
                + "<gray>-- red, blue and green are one. Every perk, all at once.</gray>");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 0.6f, 1.6f);
        player.getWorld().spawnParticle(org.bukkit.Particle.DRAGON_BREATH,
                player.getLocation().add(0, 1, 0), 80, 0.6d, 1.0d, 0.6d, 0.05d);
    }

    /** Re-issued if lost, matching how the bound elytra behaves. */
    private void grantDraconicMace(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (DraconicItems.isDraconicMace(item)) {
                return;
            }
        }
        ItemStack mace = DraconicItems.mace(draconicMaceBreach, draconicMaceUnbreakable);
        if (!owner.getInventory().addItem(mace).isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), mace);
        }
    }

    /**
     * Strips the slam. Vanilla bakes the fall-distance bonus into the mace's attack, so it is
     * subtracted back out here rather than capping the total -- capping would eat Strength and
     * Breach along with it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDraconicMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!DraconicItems.isDraconicMace(player.getInventory().getItemInMainHand())) {
            return;
        }
        double bonus = DraconicItems.slamBonus(player.getFallDistance());
        if (bonus > 0.0d) {
            event.setDamage(Math.max(1.0d, event.getDamage() - bonus));
        }
    }

    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(ABILITY_LAUNCH, "Wither Wings Launch",
                        "Launch yourself skyward and start gliding."),
                new Ability(ABILITY_RIPTIDE, "Sporic Riptide",
                        "Blue stance, in water: hurl yourself where you are looking."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_LAUNCH;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId) {
            case ABILITY_LAUNCH -> launch(owner);
            case ABILITY_RIPTIDE -> riptide(owner);
            default -> false;
        };
    }

    private boolean launch(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.WITHER_WINGS)) {
            return plugin.unlocks().denyLocked(owner, Power.WITHER_WINGS);
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_LAUNCH, launchCooldown)) {
            return false;
        }
        owner.setVelocity(owner.getVelocity().setY(launchPower));
        owner.setFallDistance(0.0f);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8f, 1.6f);
        owner.getWorld().spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, owner.getLocation(), 30, 0.3, 0.1, 0.3, 0.05);
        // Kick off gliding a moment later, once they are clear of the ground.
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (owner.isOnline() && !owner.isOnGround() && !owner.isInWater()) {
                owner.setGliding(true);
            }
        }, 6L);
        return true;
    }

    private boolean riptide(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SPORIC_OF_THE_SEA)) {
            return plugin.unlocks().denyLocked(owner, Power.SPORIC_OF_THE_SEA);
        }
        if (!plugin.stances().isActive(owner, Stance.BLUE)) {
            Text.msg(owner, "<red>Sporic Riptide only works in <aqua>blue</aqua> stance.");
            return false;
        }
        if (!owner.isInWater()) {
            Text.msg(owner, "<red>You need to be in water.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, ABILITY_RIPTIDE, riptideCooldown)) {
            return false;
        }
        owner.setVelocity(owner.getLocation().getDirection().multiply(riptidePower));
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_2, 1.0f, 1.0f);
        owner.getWorld().spawnParticle(org.bukkit.Particle.BUBBLE_COLUMN_UP, owner.getLocation(), 40, 0.3, 0.3, 0.3, 0.1);
        return true;
    }

}
