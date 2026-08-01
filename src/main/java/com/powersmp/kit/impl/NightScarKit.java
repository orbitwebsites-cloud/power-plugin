package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.combat.ComboTracker;
import com.powersmp.item.CutlassItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Attributes;
import com.powersmp.util.Effects;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.Iterator;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

/**
 * Night Scar: permanent fire resistance, escalating health, a three-hit Shadow Bomb, and a
 * soulbound custom Cutlass Sword.
 */
public class NightScarKit implements PowerKit, Listener {

    public static final String ID = "night_scar3";

    private final PowerSMP plugin;
    private final ComboTracker combos = new ComboTracker(3.0d);

    private double baseHearts = 12.0d;
    private double shadowHearts = 15.0d;
    private double cutlassHearts = 20.0d;
    private int comboHits = 3;
    private int shadowDurationTicks = 100;
    private int slownessAmplifier = 3;

    public NightScarKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Night Scar";
    }

    public void reload(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection passive = section.getConfigurationSection("infernal-vitality");
        if (passive != null) {
            baseHearts = Math.max(10.0d, passive.getDouble("hearts", baseHearts));
        }
        ConfigurationSection shadow = section.getConfigurationSection("shadow-bomb");
        if (shadow != null) {
            shadowHearts = Math.max(baseHearts, shadow.getDouble("hearts", shadowHearts));
            comboHits = Math.max(1, shadow.getInt("combo-hits", comboHits));
            shadowDurationTicks = Math.max(1,
                    (int) Math.round(shadow.getDouble("duration-seconds", 5.0d) * 20.0d));
            slownessAmplifier = Math.max(0,
                    shadow.getInt("slowness-level", 4) - 1);
            combos.windowSeconds(Math.max(0.1d, shadow.getDouble("combo-window-seconds", 3.0d)));
        }
        ConfigurationSection cutlass = section.getConfigurationSection("cutlass");
        if (cutlass != null) {
            cutlassHearts = Math.max(shadowHearts, cutlass.getDouble("hearts", cutlassHearts));
        }
    }

    @Override
    public void tick(Player owner) {
        applyPowers(owner);
    }

    @Override
    public void onJoin(Player owner) {
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
        applyPowers(owner);
    }

    private void applyPowers(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.INFERNAL_VITALITY)) {
            Effects.applyInfinite(owner, PotionEffectType.FIRE_RESISTANCE, 0);
        } else {
            Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
        }

        double hearts = 10.0d;
        if (plugin.unlocks().isUnlocked(owner, Power.CUTLASS_MASTER)) {
            hearts = cutlassHearts;
            ensureCutlass(owner);
        } else if (plugin.unlocks().isUnlocked(owner, Power.SHADOW_BOMB)) {
            hearts = shadowHearts;
        } else if (plugin.unlocks().isUnlocked(owner, Power.INFERNAL_VITALITY)) {
            hearts = baseHearts;
        }
        Attributes.set(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH,
                Math.max(0.0d, hearts * 2.0d - 20.0d));
        double max = Attributes.valueOf(owner, Attributes.MAX_HEALTH, 20.0d);
        if (owner.getHealth() > max) {
            owner.setHealth(max);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player owner)
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.SHADOW_BOMB)
                || !(event.getEntity() instanceof LivingEntity target)
                || target.isDead()
                || !TeamRules.canAffect(owner, target)) {
            return;
        }
        int hits = combos.hit(owner.getUniqueId(), target.getUniqueId());
        if (hits < comboHits) {
            Text.actionBar(owner, "<dark_purple>Shadow combo " + hits + "/" + comboHits + "</dark_purple>");
            return;
        }
        combos.reset(owner.getUniqueId());
        Effects.apply(target, PotionEffectType.DARKNESS, shadowDurationTicks, 0);
        Effects.apply(target, PotionEffectType.SLOWNESS, shadowDurationTicks, slownessAmplifier);
        target.getWorld().spawnParticle(
                Particle.SQUID_INK, target.getLocation().add(0, 1.0d, 0), 30, 0.4d, 0.6d, 0.4d, 0.03d);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 0.8f);
        Text.actionBar(owner, "<dark_purple><bold>SHADOW BOMB</bold></dark_purple>");
    }

    private void ensureCutlass(Player owner) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (owner.getUniqueId().equals(CutlassItem.ownerOf(item))) {
                CutlassItem.refresh(item);
                return;
            }
        }
        ItemStack cutlass = CutlassItem.create(owner.getUniqueId());
        if (!owner.getInventory().addItem(cutlass).isEmpty()) {
            Text.actionBar(owner, "<yellow>Free an inventory slot for your Cutlass Sword.</yellow>");
        } else {
            Text.msg(owner, "<dark_red>Your <red>Cutlass Sword</red> answers your call.</dark_red>");
        }
    }

    private void removeCutlass(Player owner) {
        ItemStack[] contents = owner.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (CutlassItem.isCutlass(contents[slot])) {
                owner.getInventory().setItem(slot, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.kits().isOwner(event.getEntity(), ID)) {
            return;
        }
        for (Iterator<ItemStack> it = event.getDrops().iterator(); it.hasNext(); ) {
            if (CutlassItem.isCutlass(it.next())) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
            if (player.isOnline() && plugin.kits().isOwner(player, ID)) {
                applyPowers(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (CutlassItem.isCutlass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            Text.actionBar(event.getPlayer(), "<red>Your Cutlass Sword will not leave you.</red>");
        }
    }

    @Override
    public void onUnlock(Player owner, Power power) {
        if (power.kitId().equals(ID)) {
            applyPowers(owner);
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.CUTLASS_MASTER) {
            removeCutlass(owner);
        }
        if (power.kitId().equals(ID)) {
            applyPowers(owner);
        }
    }

    @Override
    public void onQuit(Player owner) {
        combos.reset(owner.getUniqueId());
        Attributes.clear(owner, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
        Effects.remove(owner, PotionEffectType.FIRE_RESISTANCE);
    }

    @Override
    public void onDisable() {
        combos.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.kits().isOwner(player, ID)) {
                Attributes.clear(player, Attributes.MAX_HEALTH, Keys.SCAR_BONUS_HEALTH);
                Effects.remove(player, PotionEffectType.FIRE_RESISTANCE);
            }
        }
    }
}
