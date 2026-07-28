package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.combat.ComboTracker;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.List;
import java.util.Random;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * Life Stealer: an on-hit combo that drains health, a glow that outs a target's position for a few
 * seconds, and double drops off hostile mobs.
 *
 * <p>All three are always-on passives with no button to press -- everything here fires off the
 * player's own damage-dealing and mob-killing, the same way, say, disasterflames's Return by Death
 * kit is all reactive with nothing to activate.
 */
public class LifeStealerKit implements PowerKit, Listener {

    public static final String ID = "lifestealer";

    private final PowerSMP plugin;
    private final Random random = new Random();
    private final ComboTracker combos = new ComboTracker(3.0d);

    private int stealComboHits = 5;
    private int stealMinHearts = 1;
    private int stealMaxHearts = 3;
    private int markGlowSeconds = 5;
    private int dropMultiplier = 2;

    public LifeStealerKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Life Stealer";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection lifesteal = section.getConfigurationSection("lifesteal");
        if (lifesteal != null) {
            stealComboHits = lifesteal.getInt("combo-hits", stealComboHits);
            stealMinHearts = lifesteal.getInt("min-hearts", stealMinHearts);
            stealMaxHearts = Math.max(stealMinHearts, lifesteal.getInt("max-hearts", stealMaxHearts));
        }
        ConfigurationSection marked = section.getConfigurationSection("marked-prey");
        if (marked != null) {
            markGlowSeconds = marked.getInt("glow-seconds", markGlowSeconds);
        }
        ConfigurationSection drops = section.getConfigurationSection("double-drops");
        if (drops != null) {
            dropMultiplier = Math.max(1, drops.getInt("multiplier", dropMultiplier));
        }
    }

    // ---- Lifesteal + Marked Prey: both ride the same on-hit event --------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !plugin.kits().isOwner(player, ID)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target) || target.isDead()) {
            return;
        }
        if (plugin.unlocks().isUnlocked(player, Power.MARKED_PREY)) {
            Effects.apply(target, PotionEffectType.GLOWING, markGlowSeconds * 20, 0);
        }
        if (plugin.unlocks().isUnlocked(player, Power.LIFESTEAL)) {
            lifesteal(player, target);
        }
    }

    private void lifesteal(Player player, LivingEntity target) {
        int hits = combos.hit(player.getUniqueId(), target.getUniqueId());
        if (hits < stealComboHits) {
            return;
        }
        combos.reset(player.getUniqueId());

        int hearts = stealMinHearts + random.nextInt(stealMaxHearts - stealMinHearts + 1);
        double amount = hearts * 2.0d;
        double stolen = Math.min(amount, target.getHealth());
        if (stolen <= 0.0d) {
            return;
        }
        target.setHealth(Math.max(0.0d, target.getHealth() - stolen));

        double max = Attributes.valueOf(player, Attributes.MAX_HEALTH, 20.0d);
        player.setHealth(Math.min(max, player.getHealth() + stolen));

        Text.actionBar(player, "<dark_red>Stole " + hearts + " heart" + (hearts == 1 ? "" : "s")
                + " from " + Text.plain(target.getName()) + "</dark_red>");
    }

    // ---- Double Drops ------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Monster)) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer == null || !plugin.kits().isOwner(killer, ID)
                || !plugin.unlocks().isUnlocked(killer, Power.DOUBLE_DROPS)) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> extra = drops.stream().map(ItemStack::clone).toList();
        for (int i = 1; i < dropMultiplier; i++) {
            for (ItemStack item : extra) {
                drops.add(item.clone());
            }
        }
    }
}
