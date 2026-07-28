package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.combat.ComboTracker;
import com.powersmp.data.PlayerData;
import com.powersmp.item.SpearItem;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * xCR1T1Cx: combo and momentum fighter.
 *
 * <p>All three powers key off sustained aggression: Ka-Chow rewards landing hits quickly on one
 * target, Overdrive rewards not getting hit at all, and the spear rewards kills with permanent
 * upgrades.
 *
 * <p>The spear itself is built on the real {@code Material.SPEAR} -- see {@link SpearItem} for why
 * that used to be a Trident, and why the old right-click-suppression handler here (blocking a
 * Trident's own throw) was removed rather than ported: a real spear has no throw or riptide to
 * suppress in the first place.
 */
public class XCriticKit implements PowerKit, Listener {

    public static final String ID = "xcr1t1cx";

    private static final String ABILITY_SPEAR = "spear";
    private static final String COOLDOWN_KA_CHOW = "ka_chow";
    private static final String COOLDOWN_SPEAR_HIT = "spear_hit";

    private final PowerSMP plugin;
    private final ComboTracker combos = new ComboTracker(3.0d);
    /** Consecutive seconds of sprinting without taking a hit. */
    private final Map<UUID, Integer> sprintStreak = new ConcurrentHashMap<>();
    /** Whether the tier-2 payout has already fired for the current streak. */
    private final Map<UUID, Boolean> tier2Granted = new ConcurrentHashMap<>();

    // Tuning
    private int kaChowHits = 3;
    private int kaChowWitherSeconds = 3;
    private int kaChowWitherAmplifier = 0;
    private double kaChowCooldown = 10.0d;
    private boolean cosmeticLightning;

    private int tier1Seconds = 30;
    private int tier1Speed = 2;
    private int tier2Seconds = 60;
    private int tier2Strength = 1;
    private int tier2Duration = 120;
    private boolean damageStripsTier2;
    private boolean infiniteSaturation = true;

    private final Map<Integer, double[]> spearTiers = new HashMap<>();
    private final Map<Integer, Integer> spearUpgradeKills = new HashMap<>();
    private double spearHitCooldown = 8.0d;
    private boolean countPlayerKills = true;
    private boolean countMobKills = true;
    /** Spears pulled out of death drops, held until the owner respawns. */
    private final Map<UUID, ItemStack> deathStash = new ConcurrentHashMap<>();

    public XCriticKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Momentum";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection ka = section.getConfigurationSection("ka-chow");
        if (ka != null) {
            combos.windowSeconds(ka.getDouble("combo-window-seconds", 3.0d));
            kaChowHits = Math.max(1, ka.getInt("hits-required", kaChowHits));
            kaChowWitherSeconds = ka.getInt("wither-duration-seconds", kaChowWitherSeconds);
            kaChowWitherAmplifier = ka.getInt("wither-amplifier", kaChowWitherAmplifier);
            kaChowCooldown = ka.getDouble("cooldown-seconds", kaChowCooldown);
            cosmeticLightning = ka.getBoolean("cosmetic-lightning-only", false);
        }
        ConfigurationSection over = section.getConfigurationSection("overdrive");
        if (over != null) {
            tier1Seconds = over.getInt("tier1-seconds", tier1Seconds);
            tier1Speed = over.getInt("tier1-speed-amplifier", tier1Speed);
            tier2Seconds = over.getInt("tier2-seconds", tier2Seconds);
            tier2Strength = over.getInt("tier2-strength-amplifier", tier2Strength);
            tier2Duration = over.getInt("tier2-duration-seconds", tier2Duration);
            damageStripsTier2 = over.getBoolean("damage-strips-tier2", false);
            infiniteSaturation = over.getBoolean("infinite-saturation", true);
        }
        ConfigurationSection spear = section.getConfigurationSection("spear-master");
        spearTiers.clear();
        spearUpgradeKills.clear();
        if (spear != null) {
            ConfigurationSection tiers = spear.getConfigurationSection("tiers");
            if (tiers != null) {
                for (String key : tiers.getKeys(false)) {
                    int tier = parseInt(key, -1);
                    if (tier < SpearItem.MIN_TIER || tier > SpearItem.MAX_TIER) {
                        plugin.getLogger().warning("Ignoring spear tier '" + key + "' (expected 3-5)");
                        continue;
                    }
                    spearTiers.put(tier, new double[]{
                            tiers.getDouble(key + ".pull-strength", 1.0d),
                            tiers.getDouble(key + ".stun-seconds", 3.0d)});
                }
            }
            ConfigurationSection upgrades = spear.getConfigurationSection("upgrade-kills");
            if (upgrades != null) {
                for (String key : upgrades.getKeys(false)) {
                    int tier = parseInt(key, -1);
                    if (tier > SpearItem.MIN_TIER && tier <= SpearItem.MAX_TIER) {
                        spearUpgradeKills.put(tier, upgrades.getInt(key, Integer.MAX_VALUE));
                    }
                }
            }
            spearHitCooldown = spear.getDouble("hit-cooldown-seconds", spearHitCooldown);
            countPlayerKills = spear.getBoolean("count-player-kills", true);
            // Was always true with no way to turn it off -- mob kills were silently upgrading
            // Lunge tiers. Defaults to false now.
            countMobKills = spear.getBoolean("count-mob-kills", false);
        }

        plugin.cooldowns().registerLabel(COOLDOWN_KA_CHOW, "Ka-Chow");
        plugin.cooldowns().registerLabel(COOLDOWN_SPEAR_HIT, "Lunge");
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ---- Overdrive ------------------------------------------------------

    @Override
    public void tick(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.OVERDRIVE)) {
            sprintStreak.remove(owner.getUniqueId());
            return;
        }
        UUID id = owner.getUniqueId();

        // "and I have infinite saturation" -- a standing property of the Overdrive tier, not
        // something that only holds while sprinting, so it is pinned before the sprint check.
        if (infiniteSaturation) {
            owner.setFoodLevel(20);
            owner.setSaturation(20.0f);
        }

        if (!owner.isSprinting()) {
            resetStreak(id);
            return;
        }
        int streak = sprintStreak.merge(id, 1, Integer::sum);

        if (streak >= tier1Seconds) {
            // Refreshed each tick, so it lapses on its own the moment the streak breaks.
            Effects.refresh(owner, PotionEffectType.SPEED, tier1Speed);
            if (streak == tier1Seconds) {
                Text.actionBar(owner, "<yellow><bold>OVERDRIVE</bold></yellow> <gray>engaged</gray>");
                owner.playSound(owner.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.6f);
                owner.getWorld().spawnParticle(Particle.CLOUD, owner.getLocation(), 30, 0.3, 0.1, 0.3, 0.1);
            }
        }
        if (streak >= tier2Seconds && !tier2Granted.getOrDefault(id, false)) {
            tier2Granted.put(id, true);
            // A real timed effect, not a refreshed one: it is meant to outlive the streak.
            Effects.apply(owner, PotionEffectType.STRENGTH, tier2Duration * 20, tier2Strength);
            Text.msg(owner, "<gold><bold>OVERDRIVE II</bold></gold> <gray>-- Strength for "
                    + tier2Duration + "s.</gray>");
            owner.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.7f, 1.2f);
            owner.getWorld().spawnParticle(Particle.FLAME, owner.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.05);
        }
    }

    private void resetStreak(UUID id) {
        sprintStreak.remove(id);
        tier2Granted.remove(id);
    }

    /**
     * Taking a hit resets the sprint timer. It deliberately does <em>not</em> strip an already
     * granted Strength II -- that is the recommended reading of the spec's open question, and it is
     * config-switchable via {@code damage-strips-tier2}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        resetStreak(player.getUniqueId());
        // Transient-only: taking a hit drops Overdrive's own Speed, but must not destroy a speed
        // potion xCR1T1Cx drank himself.
        Effects.removeIfTransient(player, PotionEffectType.SPEED);
        if (damageStripsTier2) {
            Effects.removeIfTransient(player, PotionEffectType.STRENGTH);
        }
    }

    // ---- Ka-Chow and the spear's on-hit ---------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (SpearItem.isSpear(weapon) && plugin.unlocks().isUnlocked(player, Power.SPEAR_MASTER)) {
            lunge(player, target, SpearItem.tierOf(weapon));
        }
        if (plugin.unlocks().isUnlocked(player, Power.KA_CHOW)) {
            kaChow(player, target);
        }
    }

    private void kaChow(Player player, LivingEntity target) {
        if (!plugin.cooldowns().isReady(player.getUniqueId(), COOLDOWN_KA_CHOW)) {
            return;
        }
        int hits = combos.hit(player.getUniqueId(), target.getUniqueId());
        if (hits < kaChowHits) {
            return;
        }
        combos.reset(player.getUniqueId());
        plugin.cooldowns().setSeconds(player.getUniqueId(), COOLDOWN_KA_CHOW, kaChowCooldown);

        if (target.getWorld() != null) {
            if (cosmeticLightning) {
                target.getWorld().strikeLightningEffect(target.getLocation());
            } else {
                target.getWorld().strikeLightning(target.getLocation());
            }
        }
        Effects.apply(target, PotionEffectType.WITHER, kaChowWitherSeconds * 20, kaChowWitherAmplifier);
        Text.actionBar(player, "<yellow><bold>KA-CHOW!</bold></yellow>");
    }

    /**
     * Lunge: yanks the target toward the wielder, then locks it down. The pull and the stun are
     * separated by a few ticks so the yank actually lands before the freeze pins the target -- a
     * freeze applied in the same tick would zero the velocity we just set.
     */
    private void lunge(Player player, LivingEntity target, int tier) {
        if (!plugin.cooldowns().tryUseSilently(player.getUniqueId(), COOLDOWN_SPEAR_HIT, spearHitCooldown)) {
            return;
        }
        double[] values = spearTiers.getOrDefault(tier, new double[]{1.0d, 3.0d});
        double pullStrength = values[0];
        double stunSeconds = values[1];

        Vector pull = player.getLocation().toVector()
                .subtract(target.getLocation().toVector());
        if (pull.lengthSquared() > 1.0e-4) {
            pull = pull.normalize().multiply(pullStrength);
            pull.setY(Math.max(0.35d, pull.getY()));
            // Only players have a client to flag -- a mob's movement is server-authoritative
            // already, so there is nothing for vanilla's check to reject.
            if (target instanceof Player targetPlayer) {
                com.powersmp.util.MovementExemption.begin(targetPlayer);
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> com.powersmp.util.MovementExemption.end(targetPlayer), 15L);
            }
            target.setVelocity(pull);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1.0f, 0.8f);
        target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.0);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.isDead() && target.isValid()) {
                // stunSeconds, not freezeSeconds: a stunned target must stay hittable.
                plugin.freeze().stunSeconds(target, stunSeconds);
            }
        }, 5L);

        Text.actionBar(player, "<gold>Lunge " + SpearItem.numeral(tier) + "</gold> <gray>connects</gray>");
    }

    // ---- spear upgrades on kill -----------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)) {
            return;
        }
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (!SpearItem.isSpear(weapon)) {
            return;
        }
        boolean victimIsPlayer = event.getEntity() instanceof Player;
        if (victimIsPlayer ? !countPlayerKills : !countMobKills) {
            return;
        }
        PlayerData data = plugin.data().get(killer.getUniqueId());
        data.spearKills(data.spearKills() + 1);
        plugin.data().markDirty();

        int current = SpearItem.tierOf(weapon);
        int upgraded = current;
        for (int tier = current + 1; tier <= SpearItem.MAX_TIER; tier++) {
            Integer required = spearUpgradeKills.get(tier);
            if (required != null && data.spearKills() >= required) {
                upgraded = tier;
            }
        }
        if (upgraded > current) {
            SpearItem.applyTier(weapon, upgraded);
            killer.getInventory().setItemInMainHand(weapon);
            data.spearTier(upgraded);
            plugin.data().markDirty();
            Text.msg(killer, "<gold>Your spear sharpens: <white>Lunge "
                    + SpearItem.numeral(upgraded) + "</white>.");
            killer.playSound(killer.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
        }
    }

    // ---- "can't be taken away, even if I die" ---------------------------
    // The spear previously had no reissue-on-join at all, unlike every other bound item in this
    // plugin -- if it was ever lost, xCR1T1Cx simply had no way to get it back. It also had none
    // of techknight's mace's drop/death/container guards, which is how a duplicate happens: the
    // original ends up on the ground or in a chest while something else hands out a second one.

    @Override
    public void onJoin(Player owner) {
        ensureSpear(owner);
    }

    private void ensureSpear(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.SPEAR_MASTER)) {
            return;
        }
        for (ItemStack item : owner.getInventory().getContents()) {
            if (SpearItem.isSpear(item)) {
                return;
            }
        }
        PlayerData data = plugin.data().get(owner.getUniqueId());
        ItemStack spear = SpearItem.create(owner.getUniqueId(), data.spearTier());
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(spear);
        if (!leftover.isEmpty()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), spear);
            Text.msg(owner, "<yellow>Your spear was dropped at your feet -- your inventory is full.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.kits().isOwner(player, ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemStack drop = it.next();
            if (SpearItem.isSpear(drop)) {
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
        // Curse of Vanishing means there is usually nothing to restore -- ensureSpear() is the
        // fallback that actually hands it back in that case.
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (stashed == null) {
                ensureSpear(player);
                return;
            }
            HashMap<Integer, ItemStack> leftover = new HashMap<>(player.getInventory().addItem(stashed));
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stashed);
            }
            Text.msg(player, "<gray>Your spear came back with you.</gray>");
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (SpearItem.isSpear(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your spear will not leave you.</red>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (SpearItem.isSpear(event.getCurrentItem()) || SpearItem.isSpear(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() != InventoryType.CRAFTING && SpearItem.isSpear(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }


    // ---- abilities ------------------------------------------------------

    @Override
    public List<Ability> abilities() {
        return List.of(new Ability(ABILITY_SPEAR, "Claim Spear",
                "Hands you your Spear of Momentum if you do not have it."));
    }

    @Override
    public String primaryAbilityId() {
        return ABILITY_SPEAR;
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        if (!ABILITY_SPEAR.equals(abilityId)) {
            return false;
        }
        if (!plugin.unlocks().isUnlocked(owner, Power.SPEAR_MASTER)) {
            return plugin.unlocks().denyLocked(owner, Power.SPEAR_MASTER);
        }
        for (ItemStack item : owner.getInventory().getContents()) {
            if (SpearItem.isSpear(item)) {
                Text.msg(owner, "<red>You already have your spear.");
                return false;
            }
        }
        PlayerData data = plugin.data().get(owner.getUniqueId());
        ItemStack spear = SpearItem.create(owner.getUniqueId(), data.spearTier());
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(spear);
        if (!leftover.isEmpty()) {
            Text.msg(owner, "<red>No room in your inventory.");
            return false;
        }
        Text.msg(owner, "<gold>Spear of Momentum</gold> <gray>(Lunge "
                + SpearItem.numeral(data.spearTier()) + ")</gray> <gray>claimed.</gray>");
        return true;
    }

    @Override
    public void onQuit(Player owner) {
        resetStreak(owner.getUniqueId());
        combos.reset(owner.getUniqueId());
    }
}
