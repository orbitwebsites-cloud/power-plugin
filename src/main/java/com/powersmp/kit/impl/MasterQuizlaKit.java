package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.item.ResourcePackItems;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Immortality, an infinite-ammo hitscan Glock, and unlimited lemonade for masterquizla. */
public final class MasterQuizlaKit implements PowerKit, Listener {

    public static final String ID = "masterquizla";
    private static final String FIRE = "glock_fire";
    private static final String LEMONADE = "lemonade";

    private final PowerSMP plugin;
    private final NamespacedKey itemTypeKey;
    private final NamespacedKey ownerKey;
    private double gunRange = 80.0d;
    private double gunDamage = 14.0d;
    private double fireCooldown = 0.35d;

    public MasterQuizlaKit(PowerSMP plugin) {
        this.plugin = plugin;
        itemTypeKey = new NamespacedKey(plugin, "masterquizla_item");
        ownerKey = new NamespacedKey(plugin, "masterquizla_owner");
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Immortal Arsenal"; }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            gunRange = Math.max(1.0d, section.getDouble("glock.range", gunRange));
            gunDamage = Math.max(0.0d, section.getDouble("glock.damage", gunDamage));
            fireCooldown = Math.max(0.05d,
                    section.getDouble("glock.fire-cooldown-seconds", fireCooldown));
        }
        plugin.cooldowns().registerLabel(FIRE, "Glock");
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(FIRE, "Fire Glock", "Fire one infinite-ammo hitscan round."),
                new Ability(LEMONADE, "Free Lemonade", "Create a fresh bottle of lemonade."));
    }

    @Override public String primaryAbilityId() { return FIRE; }

    @Override
    public void tick(Player owner) {
        ensureItems(owner);
        if (owner.getLocation().getY() < owner.getWorld().getMinHeight() - 8) {
            owner.teleport(owner.getWorld().getSpawnLocation());
        }
    }

    @Override public void onJoin(Player owner) { ensureItems(owner); }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case FIRE -> fire(owner);
            case LEMONADE -> giveLemonade(owner);
            default -> false;
        };
    }

    private boolean fire(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.INFINITE_GLOCK)) return false;
        if (!is(owner.getInventory().getItemInMainHand(), owner, "glock")
                && !is(owner.getInventory().getItemInOffHand(), owner, "glock")) {
            Text.actionBar(owner, "<red>Hold your Infinite Glock to fire.</red>");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, FIRE, fireCooldown)) return false;

        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        RayTraceResult blockTrace = owner.getWorld().rayTraceBlocks(
                eye, direction, gunRange, FluidCollisionMode.NEVER, true);
        double maxDistance = blockTrace == null || blockTrace.getHitPosition() == null
                ? gunRange : blockTrace.getHitPosition().distance(eye.toVector());
        RayTraceResult entityTrace = owner.getWorld().rayTraceEntities(
                eye, direction, maxDistance, 0.35d,
                entity -> entity instanceof LivingEntity target
                        && !target.equals(owner) && TeamRules.canAffect(owner, target));
        Location end = eye.clone().add(direction.clone().multiply(maxDistance));
        if (entityTrace != null && entityTrace.getHitEntity() instanceof LivingEntity target) {
            end = entityTrace.getHitPosition().toLocation(owner.getWorld());
            target.setNoDamageTicks(0);
            target.damage(gunDamage, owner);
            target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    target.getLocation().add(0.0d, 1.0d, 0.0d), 8, 0.3d, 0.5d, 0.3d, 0.05d);
        }
        drawTracer(owner, eye, end);
        owner.getWorld().spawnParticle(Particle.SMOKE, eye.clone().add(direction.clone().multiply(0.7d)),
                5, 0.08d, 0.08d, 0.08d, 0.02d);
        owner.playSound(owner.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.8f);
        owner.playSound(owner.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.65f, 0.55f);
        return true;
    }

    private void drawTracer(Player owner, Location start, Location end) {
        Vector delta = end.toVector().subtract(start.toVector());
        double length = delta.length();
        if (length <= 0.0d) return;
        Vector step = delta.normalize().multiply(0.75d);
        Location point = start.clone();
        for (double traveled = 0.0d; traveled < length; traveled += 0.75d) {
            owner.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            point.add(step);
        }
    }

    private boolean giveLemonade(Player owner) {
        if (!plugin.unlocks().isUnlocked(owner, Power.INFINITE_LEMONADE)) return false;
        ItemStack lemonade = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta meta = lemonade.getItemMeta();
        meta.displayName(Text.mm("<yellow><bold>Fresh Lemonade</bold></yellow>"));
        meta.lore(List.of(Text.mm("<gray>Compliments of the Immortal Arsenal.</gray>")));
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, "lemonade");
        lemonade.setItemMeta(meta);
        var leftovers = owner.getInventory().addItem(lemonade);
        leftovers.values().forEach(item -> owner.getWorld().dropItemNaturally(owner.getLocation(), item));
        owner.playSound(owner.getLocation(), Sound.ENTITY_WANDERING_TRADER_DRINK_MILK, 0.7f, 1.4f);
        return true;
    }

    private void ensureItems(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.IMMORTALITY_TOTEM)
                && !contains(owner, "totem")) owner.getInventory().addItem(createTotem(owner));
        if (plugin.unlocks().isUnlocked(owner, Power.INFINITE_GLOCK)
                && !contains(owner, "glock")) owner.getInventory().addItem(createGlock(owner));
    }

    private ItemStack createTotem(Player owner) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.mm("<gradient:#fff3a3:#ff8a00><bold>Immortality Totem</bold></gradient>"));
        meta.lore(List.of(Text.mm("<gold>Never pops. Never expires.</gold>"),
                Text.mm("<dark_gray>Soulbound to " + owner.getName() + "</dark_gray>")));
        bind(meta, owner, "totem");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGlock(Player owner) {
        ItemStack item = new ItemStack(Material.IRON_HORSE_ARMOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.mm("<gray><bold>Infinite Glock</bold></gray>"));
        meta.lore(List.of(Text.mm("<white>14 damage • 80 block hitscan</white>"),
                Text.mm("<aqua>Infinite ammunition</aqua>"),
                Text.mm("<dark_gray>Soulbound to " + owner.getName() + "</dark_gray>")));
        bind(meta, owner, "glock");
        item.setItemMeta(meta);
        ResourcePackItems.apply(item, ResourcePackItems.MASTERQUIZLA_GLOCK);
        return item;
    }

    private void bind(ItemMeta meta, Player owner, String type) {
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    private boolean contains(Player owner, String type) {
        for (ItemStack item : owner.getInventory().getContents()) if (is(item, owner, type)) return true;
        return false;
    }

    private boolean is(ItemStack item, Player owner, String type) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return type.equals(meta.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING))
                && owner.getUniqueId().toString().equals(
                        meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    private boolean isBound(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onImmortalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player owner) || !plugin.kits().isOwner(owner, ID)
                || !plugin.unlocks().isUnlocked(owner, Power.IMMORTALITY_TOTEM)
                || !contains(owner, "totem")) return;
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            owner.teleport(owner.getWorld().getSpawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isBound(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.kits().isOwner(event.getEntity(), ID)) return;
        event.getDrops().removeIf(this::isBound);
        plugin.getServer().getScheduler().runTask(plugin, () -> ensureItems(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.kits().isOwner(player, ID)) return;
        if (event.getView().getTopInventory().equals(player.getInventory())) return;
        if (isBound(event.getCurrentItem()) || isBound(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseItem(PlayerInteractEvent event) {
        Player owner = event.getPlayer();
        if (!plugin.kits().isOwner(owner, ID) || !is(event.getItem(), owner, "glock")) return;
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                fire(owner);
            }
            default -> { }
        }
    }
}
