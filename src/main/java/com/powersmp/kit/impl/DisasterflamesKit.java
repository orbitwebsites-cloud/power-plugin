package com.powersmp.kit.impl;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Effects;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** disasterflames' Ten-Shadows-inspired summon kit. */
public final class DisasterflamesKit implements PowerKit, Listener {

    public static final String ID = "disasterflames";
    private static final String DOGS = "divine_dogs";
    private static final String RABBITS = "rabbit_escape";
    private static final String STORAGE = "shadow_storage";
    private static final String MAHORAGA = "mahoraga";

    private final PowerSMP plugin;
    private final NamespacedKey summonOwnerKey;
    private final NamespacedKey summonTypeKey;
    private final Map<UUID, List<UUID>> dogs = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> rabbits = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> mahoragas = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> openStorage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> invincibleUntil = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> summonTasks = new ConcurrentHashMap<>();

    private double dogsCooldown = 60.0d;
    private double rabbitCooldown = 60.0d;
    private int rabbitDurationTicks = 300;
    private int summonDurationTicks = 100;
    private int mahoragaDurationTicks = 2400;
    private double mahoragaCooldown = 600.0d;
    private double warningRadius = 48.0d;

    public DisasterflamesKit(PowerSMP plugin) {
        this.plugin = plugin;
        summonOwnerKey = new NamespacedKey(plugin, "shadow_summon_owner");
        summonTypeKey = new NamespacedKey(plugin, "shadow_summon_type");
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Shadows Technique"; }

    public void reload(ConfigurationSection section) {
        if (section != null) {
            dogsCooldown = Math.max(0.0d, section.getDouble("divine-dogs.cooldown-seconds", dogsCooldown));
            rabbitCooldown = Math.max(0.0d, section.getDouble("rabbit-escape.cooldown-seconds", rabbitCooldown));
            rabbitDurationTicks = seconds(section, "rabbit-escape.duration-seconds", rabbitDurationTicks);
            summonDurationTicks = seconds(section, "mahoraga.summoning-seconds", summonDurationTicks);
            mahoragaDurationTicks = seconds(section, "mahoraga.duration-seconds", mahoragaDurationTicks);
            mahoragaCooldown = Math.max(0.0d, section.getDouble("mahoraga.cooldown-seconds", mahoragaCooldown));
            warningRadius = Math.max(1.0d, section.getDouble("mahoraga.warning-radius", warningRadius));
        }
        plugin.cooldowns().registerLabel(DOGS, "Divine Dogs");
        plugin.cooldowns().registerLabel(RABBITS, "Rabbit Escape");
        plugin.cooldowns().registerLabel(MAHORAGA, "Mahoraga");
        plugin.cooldowns().registerPersistent(MAHORAGA);
    }

    private static int seconds(ConfigurationSection section, String path, int fallbackTicks) {
        return Math.max(1, (int) Math.round(section.getDouble(path, fallbackTicks / 20.0d) * 20.0d));
    }

    @Override
    public void tick(Player owner) {
        if (plugin.unlocks().isUnlocked(owner, Power.SHADOW_STORAGE)) {
            Effects.refresh(owner, PotionEffectType.STRENGTH, 0);
            Effects.refresh(owner, PotionEffectType.SPEED, 1);
        }
        clean(owner.getUniqueId());
    }

    @Override
    public List<Ability> abilities() {
        return List.of(
                new Ability(DOGS, "Divine Dogs", "Summon two armored Strength III, Speed III wolves."),
                new Ability(RABBITS, "Rabbit Escape", "Vanish without particles and release 20 rabbits."),
                new Ability(STORAGE, "Shadow Storage", "Open your hidden, death-safe shadow inventory."),
                new Ability(MAHORAGA, "Mahoraga", "Begin the ritual for the Divine General."));
    }

    @Override public String primaryAbilityId() { return DOGS; }

    @Override
    public boolean activate(Player owner, String abilityId) {
        return switch (abilityId.toLowerCase(Locale.ROOT)) {
            case DOGS -> summonDogs(owner);
            case RABBITS -> rabbitEscape(owner);
            case STORAGE -> openStorage(owner);
            case MAHORAGA -> summonMahoraga(owner);
            default -> false;
        };
    }

    private boolean summonDogs(Player owner) {
        if (!require(owner, Power.DIVINE_DOGS) || hasLiving(dogs.get(owner.getUniqueId()))) {
            if (hasLiving(dogs.get(owner.getUniqueId()))) Text.msg(owner, "<red>Your Divine Dogs are already active.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, DOGS, dogsCooldown)) return false;
        List<UUID> pair = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final boolean white = i == 0;
            Location at = owner.getLocation().clone().add(i == 0 ? 1.2d : -1.2d, 0.0d, 0.0d);
            Wolf wolf = owner.getWorld().spawn(at, Wolf.class, dog -> {
                dog.setTamed(true);
                dog.setOwner(owner);
                dog.customName(Text.mm(white ? "<white><bold>Divine Dog: White</bold>" : "<black><bold>Divine Dog: Black</bold>"));
                dog.setCustomNameVisible(true);
                dog.getEquipment().setItem(EquipmentSlot.BODY,
                        new ItemStack(Material.WOLF_ARMOR), true);
                dog.getAttribute(Attribute.ARMOR).setBaseValue(12.0d);
                dog.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 2, true, false, true));
                dog.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 2, true, false, true));
                tag(dog, owner, "dog");
            });
            pair.add(wolf.getUniqueId());
        }
        dogs.put(owner.getUniqueId(), pair);
        owner.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation(), 70, 1.3d, 0.4d, 1.3d, 0.08d);
        owner.playSound(owner.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1.3f, 0.7f);
        return true;
    }

    private boolean rabbitEscape(Player owner) {
        if (!require(owner, Power.RABBIT_ESCAPE) || hasLiving(rabbits.get(owner.getUniqueId()))) {
            if (hasLiving(rabbits.get(owner.getUniqueId()))) Text.msg(owner, "<red>Rabbit Escape is already active.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, RABBITS, rabbitCooldown)) return false;
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                rabbitDurationTicks, 0, true, false, false));
        List<UUID> swarm = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Rabbit rabbit = owner.getWorld().spawn(owner.getLocation().clone().add(
                    (Math.random() - 0.5d) * 4.0d, 0.2d, (Math.random() - 0.5d) * 4.0d), Rabbit.class);
            rabbit.setPersistent(false);
            rabbit.setVelocity(owner.getLocation().getDirection().multiply(Math.random() * 0.8d)
                    .add(new org.bukkit.util.Vector((Math.random() - 0.5d), 0.35d, (Math.random() - 0.5d))));
            tag(rabbit, owner, "rabbit");
            swarm.add(rabbit.getUniqueId());
        }
        rabbits.put(owner.getUniqueId(), swarm);
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeEntities(rabbits.remove(owner.getUniqueId())), rabbitDurationTicks);
        owner.playSound(owner.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        return true;
    }

    private boolean openStorage(Player owner) {
        if (!require(owner, Power.SHADOW_STORAGE)) return false;
        PlayerData data = plugin.data().get(owner.getUniqueId());
        Inventory inventory = Bukkit.createInventory(owner, 54, Text.mm("<dark_purple><bold>Shadow Storage</bold>"));
        for (int i = 0; i < Math.min(54, data.shadowStorage().size()); i++) {
            inventory.setItem(i, data.shadowStorage().get(i));
        }
        openStorage.put(owner.getUniqueId(), inventory);
        owner.openInventory(inventory);
        owner.playSound(owner.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 0.55f);
        return true;
    }

    @EventHandler
    public void onStorageClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || openStorage.remove(player.getUniqueId()) != event.getInventory()) return;
        persistStorage(player.getUniqueId(), event.getInventory());
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.8f, 0.55f);
    }

    private void persistStorage(UUID owner, Inventory inventory) {
        PlayerData data = plugin.data().get(owner);
        data.shadowStorage().clear();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) data.shadowStorage().add(item.clone());
        }
        plugin.data().markDirty();
    }

    private boolean summonMahoraga(Player owner) {
        if (!require(owner, Power.MAHORAGA) || living(mahoragas.get(owner.getUniqueId())) != null
                || summonTasks.containsKey(owner.getUniqueId())) {
            if (living(mahoragas.get(owner.getUniqueId())) != null || summonTasks.containsKey(owner.getUniqueId()))
                Text.msg(owner, "<red>Mahoraga is already active or being summoned.");
            return false;
        }
        if (!plugin.cooldowns().tryUse(owner, MAHORAGA, mahoragaCooldown)) return false;
        UUID id = owner.getUniqueId();
        invincibleUntil.put(id, System.currentTimeMillis() + summonDurationTicks * 50L);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, summonDurationTicks, 4, true, false, true));
        for (Player nearby : owner.getWorld().getNearbyPlayers(owner.getLocation(), warningRadius)) {
            Text.msg(nearby, "<red><bold>⚠ Mahoraga has been summoned. Adaptation begins.</bold></red>");
            nearby.playSound(nearby.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.3f, 0.55f);
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            summonTasks.remove(id);
            invincibleUntil.remove(id);
            if (!owner.isOnline()) return;
            spawnMahoraga(owner);
        }, summonDurationTicks);
        summonTasks.put(id, task);
        return true;
    }

    private void spawnMahoraga(Player owner) {
        boolean tamed = plugin.data().get(owner.getUniqueId()).mahoragaTamed();
        IronGolem golem = owner.getWorld().spawn(owner.getLocation().add(2.0d, 0.0d, 0.0d), IronGolem.class, entity -> {
            entity.customName(Text.mm("<gold><bold>Eight-Handled Sword Mahoraga</bold>"));
            entity.setCustomNameVisible(true);
            entity.setPlayerCreated(tamed);
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(200.0d);
            entity.setHealth(200.0d);
            entity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 1, true, false, true));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, true, false, true));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, true, false, true));
            tag(entity, owner, tamed ? "mahoraga_tamed" : "mahoraga_ritual");
        });
        mahoragas.put(owner.getUniqueId(), golem.getUniqueId());
        owner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, golem.getLocation().add(0, 1, 0), 160, 1.2, 1.8, 1.2, 0.3);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Entity current = living(mahoragas.get(owner.getUniqueId()));
            if (current != null) current.remove();
            mahoragas.remove(owner.getUniqueId());
        }, mahoragaDurationTicks);
        if (!tamed) startHostileRitual(owner, golem);
    }

    private void startHostileRitual(Player owner, IronGolem golem) {
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!golem.isValid() || golem.isDead()) { task.cancel(); return; }
            LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (Entity entity : golem.getNearbyEntities(32, 16, 32)) {
                if (!(entity instanceof LivingEntity target) || target.equals(golem)) continue;
                double distance = target.getLocation().distanceSquared(golem.getLocation());
                if (distance < best) { best = distance; nearest = target; }
            }
            if (nearest != null) golem.setTarget(nearest);
        }, 0L, 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSummonerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && invincibleUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        Player owner = null;
        LivingEntity target = null;
        if (event.getDamager() instanceof Player attacker && plugin.kits().isOwner(attacker, ID)
                && event.getEntity() instanceof LivingEntity victim) { owner = attacker; target = victim; }
        else if (event.getEntity() instanceof Player victim && plugin.kits().isOwner(victim, ID)
                && event.getDamager() instanceof LivingEntity attacker) { owner = victim; target = attacker; }
        if (owner == null || target == null
                || (target instanceof Player targetPlayer
                        && TeamRules.areTeammates(owner, targetPlayer))) return;
        Entity summon = living(mahoragas.get(owner.getUniqueId()));
        if (summon instanceof IronGolem golem
                && "mahoraga_tamed".equals(type(golem))) golem.setTarget(target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSummonDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        String type = type(dead);
        UUID ownerId = owner(dead);
        if (ownerId == null) return;
        if ("mahoraga_ritual".equals(type)) {
            Player killer = dead.getKiller();
            if (killer != null && killer.getUniqueId().equals(ownerId)) {
                plugin.data().get(ownerId).mahoragaTamed(true);
                plugin.data().markDirty();
                Text.msg(killer, "<gold><bold>MAHORAGA TAMED</bold></gold> <gray>Future rituals answer to you.</gray>");
                killer.playSound(killer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.7f);
            }
            mahoragas.remove(ownerId);
        }
    }

    private boolean require(Player owner, Power power) {
        return plugin.unlocks().isUnlocked(owner, power) || plugin.unlocks().denyLocked(owner, power);
    }

    private void tag(LivingEntity entity, Player owner, String type) {
        entity.getPersistentDataContainer().set(summonOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        entity.getPersistentDataContainer().set(summonTypeKey, PersistentDataType.STRING, type);
    }

    private UUID owner(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(summonOwnerKey, PersistentDataType.STRING);
        try { return raw == null ? null : UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    private String type(Entity entity) {
        return entity.getPersistentDataContainer().get(summonTypeKey, PersistentDataType.STRING);
    }

    private boolean hasLiving(List<UUID> ids) {
        if (ids == null) return false;
        return ids.stream().anyMatch(id -> living(id) != null);
    }

    private Entity living(UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        return entity != null && entity.isValid() && !entity.isDead() ? entity : null;
    }

    private void clean(UUID owner) {
        if (!hasLiving(dogs.get(owner))) dogs.remove(owner);
        if (!hasLiving(rabbits.get(owner))) rabbits.remove(owner);
        if (living(mahoragas.get(owner)) == null) mahoragas.remove(owner);
        if (invincibleUntil.getOrDefault(owner, 0L) <= System.currentTimeMillis()) invincibleUntil.remove(owner);
    }

    private void removeEntities(List<UUID> ids) {
        if (ids == null) return;
        for (UUID id : ids) { Entity entity = living(id); if (entity != null) entity.remove(); }
    }

    @Override
    public void onJoin(Player owner) {
        UUID ownerId = owner.getUniqueId();
        List<UUID> recoveredDogs = new ArrayList<>();
        List<UUID> recoveredRabbits = new ArrayList<>();
        UUID recoveredMahoraga = null;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!ownerId.equals(owner(entity))) continue;
                String type = type(entity);
                if ("dog".equals(type) && recoveredDogs.size() < 2) recoveredDogs.add(entity.getUniqueId());
                else if ("rabbit".equals(type) && recoveredRabbits.size() < 20) recoveredRabbits.add(entity.getUniqueId());
                else if (("mahoraga_tamed".equals(type) || "mahoraga_ritual".equals(type))
                        && recoveredMahoraga == null) recoveredMahoraga = entity.getUniqueId();
                else entity.remove();
            }
        }
        if (!recoveredDogs.isEmpty()) dogs.put(ownerId, recoveredDogs);
        if (!recoveredRabbits.isEmpty()) rabbits.put(ownerId, recoveredRabbits);
        if (recoveredMahoraga != null) mahoragas.put(ownerId, recoveredMahoraga);
    }

    @Override public void onQuit(Player owner) { invincibleUntil.remove(owner.getUniqueId()); }

    @Override
    public void onDisable() {
        openStorage.forEach(this::persistStorage);
        openStorage.clear();
        dogs.values().forEach(this::removeEntities);
        rabbits.values().forEach(this::removeEntities);
        mahoragas.values().forEach(id -> { Entity entity = living(id); if (entity != null) entity.remove(); });
        summonTasks.values().forEach(BukkitTask::cancel);
        dogs.clear(); rabbits.clear(); mahoragas.clear(); summonTasks.clear(); invincibleUntil.clear();
    }
}
