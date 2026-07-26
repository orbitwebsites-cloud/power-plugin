package com.powersmp.stance;

import com.powersmp.PowerSMP;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Mavricc's stance system, plus the Mushroom Affinity modifier that sits on top of it.
 *
 * <p>Everything is re-derived from scratch on each kit tick. Potion effects are applied with a
 * duration a few times the tick interval, so switching stance or stepping off mycelium lets the old
 * effects lapse on their own -- there is no "undo the previous stance" path to get wrong. Attribute
 * modifiers cannot work that way (they are permanent until removed, and they persist in player NBT
 * across restarts), so those are keyed, diffed against a cache to avoid re-sending them every
 * second, and explicitly stripped on quit and on plugin disable.
 */
public class StanceManager implements Listener {

    private final PowerSMP plugin;

    private final Set<UUID> withAffinity = ConcurrentHashMap.newKeySet();
    /** Effect types this plugin applied on the last tick, so green's immunity does not eat them. */
    private final Map<UUID, Set<PotionEffectType>> ourEffects = new ConcurrentHashMap<>();
    /** Last value written per attribute key, to skip redundant modifier churn. */
    private final Map<UUID, Map<NamespacedKey, Double>> appliedAttributes = new ConcurrentHashMap<>();

    // Tuning, refreshed from kits.yml.
    private int redStrength = 1;
    private double redArmor = -4.0d;
    private int blueSpeed = 1;
    private int blueWeakness = 0;
    private int blueHaste = 1;
    private double blueBlockReach = 1.0d;
    private double blueEntityReach = 1.0d;
    private int greenResistance = 1;
    private int greenSlowness = 0;
    private double greenKnockback = 1.0d;

    private List<Material> affinityBlocks = List.of(Material.MYCELIUM);
    private List<String> affinityBiomeFragments = List.of("mushroom");
    private double affinityExtraArmor = -2.0d;
    private double affinityAttackSpeed = 1.0d;
    private double affinityHungerMultiplier = 0.5d;
    private double affinityExtraEntityReach = 1.0d;
    private boolean affinityDebuffImmunity = true;

    public StanceManager(PowerSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection mavricc) {
        if (mavricc == null) {
            return;
        }
        ConfigurationSection stances = mavricc.getConfigurationSection("stances");
        if (stances != null) {
            redStrength = stances.getInt("red.strength-amplifier", redStrength);
            redArmor = stances.getDouble("red.armor-points", redArmor);
            blueSpeed = stances.getInt("blue.speed-amplifier", blueSpeed);
            blueWeakness = stances.getInt("blue.weakness-amplifier", blueWeakness);
            blueHaste = stances.getInt("blue.haste-amplifier", blueHaste);
            blueBlockReach = stances.getDouble("blue.bonus-block-reach", blueBlockReach);
            blueEntityReach = stances.getDouble("blue.bonus-entity-reach", blueEntityReach);
            greenResistance = stances.getInt("green.resistance-amplifier", greenResistance);
            greenSlowness = stances.getInt("green.slowness-amplifier", greenSlowness);
            greenKnockback = stances.getDouble("green.knockback-resistance", greenKnockback);
        }
        ConfigurationSection affinity = mavricc.getConfigurationSection("affinity");
        if (affinity != null) {
            affinityBlocks = parseMaterials(affinity.getStringList("blocks"));
            List<String> fragments = affinity.getStringList("biome-key-contains");
            if (!fragments.isEmpty()) {
                affinityBiomeFragments = fragments.stream()
                        .map(s -> s.toLowerCase(Locale.ROOT)).toList();
            }
            affinityExtraArmor = affinity.getDouble("extra-armor-points", affinityExtraArmor);
            affinityAttackSpeed = affinity.getDouble("red-bonus-attack-speed", affinityAttackSpeed);
            affinityHungerMultiplier =
                    affinity.getDouble("blue-hunger-loss-multiplier", affinityHungerMultiplier);
            affinityExtraEntityReach =
                    affinity.getDouble("blue-extra-entity-reach", affinityExtraEntityReach);
            affinityDebuffImmunity = affinity.getBoolean("green-debuff-immunity", affinityDebuffImmunity);
        }
    }

    private List<Material> parseMaterials(List<String> names) {
        if (names == null || names.isEmpty()) {
            return affinityBlocks;
        }
        List<Material> materials = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("Unknown affinity block '" + name + "' in kits.yml");
            } else {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? affinityBlocks : materials;
    }

    // ---- state ----------------------------------------------------------

    public Stance stanceOf(Player player) {
        return Stance.parseOrNone(plugin.data().get(player.getUniqueId()).stance());
    }

    public void setStance(Player player, Stance stance) {
        plugin.data().get(player.getUniqueId()).stance(stance.name());
        plugin.data().markDirty();
        // Old stance effects would linger for their remaining duration otherwise.
        clearStanceEffects(player);
        apply(player);
        Text.msg(player, "Stance set to " + stance.coloured() + ".");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
    }

    public boolean hasAffinity(Player player) {
        return withAffinity.contains(player.getUniqueId());
    }

    // ---- per-tick application -------------------------------------------

    /** Called once per kit tick for the stance-capable player. */
    public void apply(Player player) {
        UUID id = player.getUniqueId();
        Stance stance = stanceOf(player);
        boolean affinity = detectAffinity(player);
        if (affinity) {
            withAffinity.add(id);
        } else {
            withAffinity.remove(id);
        }

        int bump = affinity ? 1 : 0;
        Set<PotionEffectType> applied = new HashSet<>();

        double armor = 0.0d;
        double knockback = 0.0d;
        double blockReach = 0.0d;
        double entityReach = 0.0d;
        double attackSpeed = 0.0d;

        switch (stance) {
            case RED -> {
                Effects.refresh(player, PotionEffectType.STRENGTH, redStrength + bump);
                applied.add(PotionEffectType.STRENGTH);
                // Affinity deepens the armour penalty; it is a cost, not a bonus.
                armor = redArmor + (affinity ? affinityExtraArmor : 0.0d);
                if (affinity) {
                    attackSpeed = affinityAttackSpeed;
                }
            }
            case BLUE -> {
                Effects.refresh(player, PotionEffectType.SPEED, blueSpeed + bump);
                Effects.refresh(player, PotionEffectType.WEAKNESS, blueWeakness + bump);
                Effects.refresh(player, PotionEffectType.HASTE, blueHaste + bump);
                applied.add(PotionEffectType.SPEED);
                applied.add(PotionEffectType.WEAKNESS);
                applied.add(PotionEffectType.HASTE);
                blockReach = blueBlockReach;
                entityReach = blueEntityReach + (affinity ? affinityExtraEntityReach : 0.0d);
            }
            case GREEN -> {
                Effects.refresh(player, PotionEffectType.RESISTANCE, greenResistance + bump);
                Effects.refresh(player, PotionEffectType.SLOWNESS, greenSlowness + bump);
                applied.add(PotionEffectType.RESISTANCE);
                applied.add(PotionEffectType.SLOWNESS);
                knockback = greenKnockback;
            }
            case NONE -> {
                // Nothing applied; the attribute writes below zero everything out.
            }
        }

        ourEffects.put(id, applied);

        setAttribute(player, Attributes.ARMOR, Keys.STANCE_ARMOR, armor);
        setAttribute(player, Attributes.KNOCKBACK_RESISTANCE, Keys.STANCE_KNOCKBACK, knockback);
        setAttribute(player, Attributes.BLOCK_INTERACTION_RANGE, Keys.STANCE_BLOCK_REACH, blockReach);
        setAttribute(player, Attributes.ENTITY_INTERACTION_RANGE, Keys.STANCE_ENTITY_REACH, entityReach);
        setAttribute(player, Attributes.ATTACK_SPEED, Keys.STANCE_ATTACK_SPEED, attackSpeed);

        if (stance == Stance.GREEN && affinity && affinityDebuffImmunity) {
            stripForeignDebuffs(player, applied);
        }
    }

    private boolean detectAffinity(Player player) {
        Block standingOn = player.getLocation().getBlock().getRelative(0, -1, 0);
        if (affinityBlocks.contains(standingOn.getType())) {
            return true;
        }
        // Biome is matched by key substring rather than an enum constant: Biome stopped being an
        // enum partway through 1.21, and mushroom_fields is the only match either way.
        String biomeKey;
        try {
            biomeKey = player.getLocation().getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
        } catch (Throwable ex) {
            return false;
        }
        for (String fragment : affinityBiomeFragments) {
            if (biomeKey.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Green + affinity is "debuff immunity", but green's own Slowness I is a debuff. Only effects
     * this plugin did not apply are stripped.
     */
    private void stripForeignDebuffs(Player player, Set<PotionEffectType> ours) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            PotionEffectType type = effect.getType();
            if (!ours.contains(type) && Effects.isHarmful(type)) {
                player.removePotionEffect(type);
            }
        }
    }

    private void setAttribute(Player player, org.bukkit.attribute.Attribute attribute,
                              NamespacedKey key, double value) {
        if (attribute == null) {
            return;
        }
        Map<NamespacedKey, Double> cache =
                appliedAttributes.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Double previous = cache.get(key);
        if (previous != null && previous == value) {
            return;
        }
        Attributes.set(player, attribute, key, value);
        cache.put(key, value);
    }

    // ---- cleanup --------------------------------------------------------

    private void clearStanceEffects(Player player) {
        Effects.remove(player, PotionEffectType.STRENGTH);
        Effects.remove(player, PotionEffectType.SPEED);
        Effects.remove(player, PotionEffectType.WEAKNESS);
        Effects.remove(player, PotionEffectType.HASTE);
        Effects.remove(player, PotionEffectType.RESISTANCE);
        Effects.remove(player, PotionEffectType.SLOWNESS);
    }

    /**
     * Strips every attribute modifier this class owns. Called on join (in case of an unclean
     * shutdown), on quit, and on disable -- keyed modifiers survive in player NBT otherwise.
     */
    public void clearAttributes(Player player) {
        Attributes.clear(player, Attributes.ARMOR, Keys.STANCE_ARMOR);
        Attributes.clear(player, Attributes.KNOCKBACK_RESISTANCE, Keys.STANCE_KNOCKBACK);
        Attributes.clear(player, Attributes.BLOCK_INTERACTION_RANGE, Keys.STANCE_BLOCK_REACH);
        Attributes.clear(player, Attributes.ENTITY_INTERACTION_RANGE, Keys.STANCE_ENTITY_REACH);
        Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.STANCE_ATTACK_SPEED);
        appliedAttributes.remove(player.getUniqueId());
        withAffinity.remove(player.getUniqueId());
        ourEffects.remove(player.getUniqueId());
    }

    // ---- blue affinity: reduced hunger loss ------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.kits().isOwner(player, "mavricc")) {
            return;
        }
        if (stanceOf(player) != Stance.BLUE || !hasAffinity(player)) {
            return;
        }
        int current = player.getFoodLevel();
        int next = event.getFoodLevel();
        if (next >= current) {
            return;
        }
        int loss = current - next;
        int reduced = (int) Math.round(loss * affinityHungerMultiplier);
        if (reduced <= 0) {
            // Rounded away entirely -- skip the tick's hunger loss outright.
            event.setCancelled(true);
            return;
        }
        event.setFoodLevel(current - reduced);
    }
}
