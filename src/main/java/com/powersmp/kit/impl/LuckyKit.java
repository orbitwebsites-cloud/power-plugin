package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.DraconicItems;
import com.powersmp.item.MaceItem;
import com.powersmp.item.SpearItem;
import com.powersmp.item.TridentItem;
import com.powersmp.item.TitanBladeItem;
import com.powersmp.item.BloodlustItem;
import com.powersmp.item.CutlassItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lucky: rolls a random other kit's whole power set and holds it for a fixed window, then rerolls.
 *
 * <p>This does not reimplement any other kit's abilities -- it makes Lucky temporarily <em>become</em>
 * the rolled kit by pointing {@link com.powersmp.kit.KitRegistry}'s in-memory override at it. Every
 * lookup that matters ({@code kitOf}, {@code isOwner}, {@code UnlockManager.isUnlocked}, and the
 * shared tick/join/quit dispatch in {@code PowerSMP}) already goes through that one choke point, so
 * the rolled kit's tick, abilities, item protections and unlock gate all just work for Lucky with no
 * per-kit special-casing. Rerolling calls the outgoing kit's own {@code onQuit} -- the same cleanup
 * hook every kit already has to get right for a real disconnect -- and strips whatever soulbound
 * weapon it handed out, since that gear is no longer functional or owned once the override moves on.
 *
 * <p>Deliberately not persisted across a relog: quitting cancels the timer, cleans up the current
 * roll, and clears the override, so every join starts a fresh roll. Matches how the rest of this
 * plugin treats online-only ephemeral state (grapple tasks, climb progress, and so on).
 *
 */
public class LuckyKit implements PowerKit, Listener {

    public static final String ID = "lucky";

    private final PowerSMP plugin;
    private final Random random = new Random();
    private final Map<UUID, BukkitTask> rerollTasks = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentRoll = new ConcurrentHashMap<>();

    private int rollDurationTicks = 20 * 60 * 20;

    public LuckyKit(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Lucky";
    }

    public void reload(ConfigurationSection section) {
        int minutes = section == null ? 20 : Math.max(1, section.getInt("roll-minutes", 20));
        rollDurationTicks = (int) Math.min(Integer.MAX_VALUE, minutes * 60L * 20L);
    }

    @Override
    public void onJoin(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.LUCKY_ROLL)) {
            roll(owner);
        }
    }

    private void roll(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        UUID id = owner.getUniqueId();
        List<PowerKit> candidates = new ArrayList<>();
        for (PowerKit kit : plugin.kits().all()) {
            if (!kit.id().equalsIgnoreCase(ID)) {
                candidates.add(kit);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        PowerKit chosen = candidates.get(random.nextInt(candidates.size()));

        endCurrentRoll(owner);

        plugin.kits().setOverride(id, chosen.id());
        currentRoll.put(id, chosen.id());
        chosen.onJoin(owner);

        int minutes = rollDurationTicks / 20 / 60;
        Text.msg(owner, "<gold><bold>Lucky Roll!</bold></gold> <gray>You are channeling</gray> <white>"
                + Text.plain(chosen.displayName()) + "</white><gray>'s power for " + minutes + " minute"
                + (minutes == 1 ? "" : "s") + ".</gray>");
        owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, owner.getLocation().add(0.0, 1.0, 0.0),
                40, 0.5, 0.8, 0.5, 0.3);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> roll(owner), rollDurationTicks);
        rerollTasks.put(id, task);
    }

    /** Tears down whatever kit is currently rolled, without starting a new one. */
    private void endCurrentRoll(Player owner) {
        UUID id = owner.getUniqueId();
        String previousId = currentRoll.remove(id);
        if (previousId == null) {
            return;
        }
        PowerKit previous = plugin.kits().byId(previousId);
        if (previous != null) {
            previous.onQuit(owner);
        }
        stripSignatureItems(owner);
    }

    /**
     * The signature bound weapon a rolled kit handed out is dead weight once the roll moves on --
     * its own kit's protections and activation checks all key off {@code isOwner}, which stops
     * matching the moment the override changes. Removing it keeps Lucky's inventory from slowly
     * filling up with inert signature items. The mace checks below only clean up retired items
     * from older plugin versions; no current kit issues one.
     */
    private void stripSignatureItems(Player owner) {
        ItemStack[] contents = owner.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isSignatureItem(contents[i])) {
                owner.getInventory().setItem(i, null);
            }
        }
        ItemStack[] armor = owner.getInventory().getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < armor.length; i++) {
            if (isSignatureItem(armor[i])) {
                armor[i] = null;
                armorChanged = true;
            }
        }
        if (armorChanged) {
            owner.getInventory().setArmorContents(armor);
        }
        if (isSignatureItem(owner.getInventory().getItemInOffHand())) {
            owner.getInventory().setItemInOffHand(null);
        }
    }

    private boolean isSignatureItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (TitanBladeItem.isBoneBlade(item) || BloodlustItem.isBloodlust(item)
                || CutlassItem.isCutlass(item) || TridentItem.isBoundTrident(item)
                || SpearItem.isSpear(item) || MaceItem.isSoulbound(item)
                || DraconicItems.isDraconicMace(item) || MarbKit.isShadow(item)) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(Keys.BOUND_ELYTRA, PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(Keys.SCAR_MACE, PersistentDataType.INTEGER);
    }

    /**
     * Own listener rather than relying on {@code PowerSMP}'s generic quit dispatch: that dispatch
     * calls {@code onQuit} on whatever {@code kitOf} resolves to <em>right now</em>, which would be
     * the rolled kit, not Lucky -- there would be nothing left to cancel this timer. Runs at
     * {@code LOWEST} so the rolled kit's cleanup and the override clear happen before the generic
     * dispatch's own (harmless, now-redundant) lookup.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        BukkitTask task = rerollTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        endCurrentRoll(player);
        plugin.kits().clearOverride(id);
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : rerollTasks.values()) {
            task.cancel();
        }
        rerollTasks.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (currentRoll.containsKey(player.getUniqueId())) {
                endCurrentRoll(player);
                plugin.kits().clearOverride(player.getUniqueId());
            }
        }
    }

    @Override
    public void onRevoke(Player owner, Power power) {
        if (power != Power.LUCKY_ROLL) {
            return;
        }
        BukkitTask task = rerollTasks.remove(owner.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        endCurrentRoll(owner);
        plugin.kits().clearOverride(owner.getUniqueId());
    }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return false;
    }
}
