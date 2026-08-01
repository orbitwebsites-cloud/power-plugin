package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Attributes;
import com.powersmp.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/** Permanent armor adaptation for ThePoultryMan10. */
public final class PoultryManKit implements PowerKit, Listener {

    public static final String ID = "poultryman";

    private final PowerSMP plugin;
    private final NamespacedKey armorKey;
    private double damagePerArmor = 1000.0d;
    private int maxArmor = 10;

    public PoultryManKit(PowerSMP plugin) {
        this.plugin = plugin;
        armorKey = new NamespacedKey(plugin, "poultry_damage_adaptation");
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Damage Adaptation"; }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            damagePerArmor = Math.max(1.0d,
                    section.getDouble("damage-per-armor", damagePerArmor));
            maxArmor = Math.max(0, section.getInt("max-bonus-armor", maxArmor));
        }
    }

    @Override
    public void tick(Player owner) {
        applyArmor(owner);
    }

    @Override
    public void onJoin(Player owner) {
        applyArmor(owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player owner)
                || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.DAMAGE_ADAPTATION)
                || event.getFinalDamage() <= 0.0d) {
            return;
        }
        PlayerData data = plugin.data().get(owner.getUniqueId());
        int before = armorLevel(data.adaptationDamage());
        data.adaptationDamage(data.adaptationDamage() + event.getFinalDamage());
        int after = armorLevel(data.adaptationDamage());
        plugin.data().markDirty();
        if (after > before) {
            applyArmor(owner);
            Text.msg(owner, "<gold><bold>ADAPTED</bold></gold> <gray>Damage endured granted "
                    + "<white>+" + after + " armor</white> total.</gray>");
            owner.playSound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.75f);
        }
    }

    private int armorLevel(double damage) {
        return Math.min(maxArmor, (int) Math.floor(damage / damagePerArmor));
    }

    private void applyArmor(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.DAMAGE_ADAPTATION)) {
            Attributes.clear(owner, Attributes.ARMOR, armorKey);
            return;
        }
        int armor = armorLevel(plugin.data().get(owner.getUniqueId()).adaptationDamage());
        Attributes.set(owner, Attributes.ARMOR, armorKey, armor);
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power == Power.DAMAGE_ADAPTATION) {
            Attributes.clear(owner, Attributes.ARMOR, armorKey);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Attributes.clear(player, Attributes.ARMOR, armorKey);
        }
    }
}
