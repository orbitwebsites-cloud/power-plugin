package com.powersmp;

import com.powersmp.combat.FreezeUtil;
import com.powersmp.combat.RespawnGuard;
import com.powersmp.command.PowerCommand;
import com.powersmp.command.KillCommand;
import com.powersmp.command.PowerSMPCommand;
import com.powersmp.cooldown.CooldownManager;
import com.powersmp.data.DataStore;
import com.powersmp.domain.IllusoryRealm;
import com.powersmp.food.MushroomHungerService;
import com.powersmp.item.BoundItemListener;
import com.powersmp.item.ResourcePackItems;
import com.powersmp.kit.AbilityTriggerListener;
import com.powersmp.kit.Ability;
import com.powersmp.kit.KitRegistry;
import com.powersmp.kit.PowerKit;
import com.powersmp.command.XpCommand;
import com.powersmp.kit.impl.NorthOfNowhereKit;
import com.powersmp.kit.impl.DisasterflamesKit;
import com.powersmp.kit.impl.DomanKit;
import com.powersmp.kit.impl.TheGhostKit;
import com.powersmp.kit.impl.ItzMeTentxKit;
import com.powersmp.kit.impl.IdleDeathGambleKit;
import com.powersmp.kit.impl.JJLionKit;
import com.powersmp.kit.impl.KornFlakisKit;
import com.powersmp.kit.impl.LifeStealerKit;
import com.powersmp.kit.impl.LlamaChasKit;
import com.powersmp.kit.impl.LuckyKit;
import com.powersmp.kit.impl.MarbKit;
import com.powersmp.kit.impl.MavriccKit;
import com.powersmp.kit.impl.MonkeyManKit;
import com.powersmp.kit.impl.NightScarKit;
import com.powersmp.kit.impl.PhantomKit;
import com.powersmp.kit.impl.SparkKit;
import com.powersmp.kit.impl.TechKnightKit;
import com.powersmp.kit.impl.VoidwalkerKit;
import com.powersmp.kit.impl.XCriticKit;
import com.powersmp.kit.impl.CrazyTNT2CoolKit;
import com.powersmp.kit.impl.BitesTheDustKit;
import com.powersmp.menu.KeybindMenu;
import com.powersmp.menu.PowerMenu;
import com.powersmp.progression.UnlockManager;
import com.powersmp.progression.Power;
import com.powersmp.stance.StanceCommand;
import com.powersmp.stance.StanceManager;
import com.powersmp.team.TeamRules;
import com.powersmp.util.Attributes;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.MovementExemption;
import com.powersmp.util.Text;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Entry point: builds the shared services, registers the kits, and drives the one shared tick.
 *
 * <p>Kits do not own tasks or schedulers of their own -- there is a single timer here that calls
 * {@link PowerKit#tick(Player)} for whoever is online. One timer is easier to reason about than
 * four, and it keeps the "polled ~1x/sec" contract in one place.
 */
public class PowerSMP extends JavaPlugin implements Listener {

    private static final String KITS_FILE = "kits.yml";

    private FileConfiguration kitsConfig;

    private DataStore data;
    private CooldownManager cooldowns;
    private FreezeUtil freeze;
    private RespawnGuard respawnGuard;
    private KitRegistry kits;
    private UnlockManager unlocks;
    private StanceManager stances;
    private MushroomHungerService food;
    private IllusoryRealm realm;
    private PowerMenu powerMenu;
    private KeybindMenu keybindMenu;
    private AbilityTriggerListener abilityTriggers;
    private BoundItemListener boundItems;

    private MavriccKit mavricc;
    private NorthOfNowhereKit northOfNowhere;
    private XCriticKit xcritic;
    private KornFlakisKit kornflakis;
    private ItzMeTentxKit itzmetentx;
    private JJLionKit jjlion;
    private DomanKit doman;
    private SparkKit spark;
    private NightScarKit nightscar;
    private MarbKit marb;
    private LlamaChasKit llamachas;
    private MonkeyManKit monkeyman;
    private TechKnightKit techknight;
    private VoidwalkerKit voidwalker;
    private DisasterflamesKit disasterflames;
    private TheGhostKit theghost;
    private PhantomKit phantom;
    private LuckyKit lucky;
    private LifeStealerKit lifestealer;
    private CrazyTNT2CoolKit crazyTNT2Cool;
    private IdleDeathGambleKit idleDeathGamble;
    private BitesTheDustKit bitesTheDust;

    private int tickInterval = 20;
    private BukkitTask kitTickTask;
    private BukkitTask autosaveTask;
    private NamespacedKey energyRecipeKey;

    @Override
    public void onEnable() {
        Keys.init(this);
        Attributes.warnMissing(getLogger());
        Enchants.warnMissing(getLogger());

        saveResource(KITS_FILE, false);
        registerEnergyRecipe();
        kitsConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), KITS_FILE));

        data = new DataStore(this, "data.yml");
        data.load();

        cooldowns = new CooldownManager(this);
        freeze = new FreezeUtil(this);
        respawnGuard = new RespawnGuard(this);
        kits = new KitRegistry(this);
        unlocks = new UnlockManager(this);
        stances = new StanceManager(this);
        food = new MushroomHungerService(this);
        realm = new IllusoryRealm(this);
        powerMenu = new PowerMenu(this);
        keybindMenu = new KeybindMenu(this);
        abilityTriggers = new AbilityTriggerListener(this);
        boundItems = new BoundItemListener(this);

        mavricc = new MavriccKit(this);
        northOfNowhere = new NorthOfNowhereKit(this);
        xcritic = new XCriticKit(this);
        kornflakis = new KornFlakisKit(this);
        itzmetentx = new ItzMeTentxKit(this);
        jjlion = new JJLionKit(this);
        doman = new DomanKit(this);
        spark = new SparkKit(this);
        nightscar = new NightScarKit(this);
        marb = new MarbKit(this);
        llamachas = new LlamaChasKit(this);
        monkeyman = new MonkeyManKit(this);
        techknight = new TechKnightKit(this);
        voidwalker = new VoidwalkerKit(this);
        disasterflames = new DisasterflamesKit(this);
        theghost = new TheGhostKit(this);
        phantom = new PhantomKit(this);
        lucky = new LuckyKit(this);
        lifestealer = new LifeStealerKit(this);
        crazyTNT2Cool = new CrazyTNT2CoolKit(this);
        idleDeathGamble = new IdleDeathGambleKit(this);
        bitesTheDust = new BitesTheDustKit(this);
        kits.register(mavricc);
        kits.register(northOfNowhere);
        kits.register(xcritic);
        kits.register(kornflakis);
        kits.register(itzmetentx);
        kits.register(jjlion);
        kits.register(doman);
        kits.register(spark);
        kits.register(nightscar);
        kits.register(marb);
        kits.register(llamachas);
        kits.register(monkeyman);
        kits.register(techknight);
        kits.register(voidwalker);
        kits.register(disasterflames);
        kits.register(theghost);
        kits.register(phantom);
        kits.register(lucky);
        kits.register(lifestealer);
        kits.register(crazyTNT2Cool);
        kits.register(idleDeathGamble);
        kits.register(bitesTheDust);

        cooldowns.attachStore(data);

        applyConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(freeze, this);
        Bukkit.getPluginManager().registerEvents(respawnGuard, this);
        Bukkit.getPluginManager().registerEvents(unlocks, this);
        Bukkit.getPluginManager().registerEvents(stances, this);
        Bukkit.getPluginManager().registerEvents(food, this);
        Bukkit.getPluginManager().registerEvents(realm, this);
        Bukkit.getPluginManager().registerEvents(powerMenu, this);
        Bukkit.getPluginManager().registerEvents(keybindMenu, this);
        Bukkit.getPluginManager().registerEvents(abilityTriggers, this);
        Bukkit.getPluginManager().registerEvents(boundItems, this);
        Bukkit.getPluginManager().registerEvents(new TeamRules(), this);
        removeMaceRecipes();
        int removedMaces = boundItems.purgeLoadedMaces();
        if (removedMaces > 0) {
            getLogger().info("Removed " + removedMaces + " mace(s) from loaded worlds.");
        }
        Bukkit.getPluginManager().registerEvents(mavricc, this);
        Bukkit.getPluginManager().registerEvents(northOfNowhere, this);
        Bukkit.getPluginManager().registerEvents(xcritic, this);
        Bukkit.getPluginManager().registerEvents(kornflakis, this);
        Bukkit.getPluginManager().registerEvents(itzmetentx, this);
        Bukkit.getPluginManager().registerEvents(jjlion, this);
        Bukkit.getPluginManager().registerEvents(doman, this);
        Bukkit.getPluginManager().registerEvents(spark, this);
        Bukkit.getPluginManager().registerEvents(nightscar, this);
        Bukkit.getPluginManager().registerEvents(marb, this);
        Bukkit.getPluginManager().registerEvents(llamachas, this);
        Bukkit.getPluginManager().registerEvents(monkeyman, this);
        Bukkit.getPluginManager().registerEvents(techknight, this);
        Bukkit.getPluginManager().registerEvents(techknight.menu(), this);
        Bukkit.getPluginManager().registerEvents(voidwalker, this);
        Bukkit.getPluginManager().registerEvents(disasterflames, this);
        Bukkit.getPluginManager().registerEvents(theghost, this);
        Bukkit.getPluginManager().registerEvents(phantom, this);
        Bukkit.getPluginManager().registerEvents(lucky, this);
        Bukkit.getPluginManager().registerEvents(lifestealer, this);
        Bukkit.getPluginManager().registerEvents(crazyTNT2Cool, this);
        Bukkit.getPluginManager().registerEvents(bitesTheDust, this);

        bind("stance", new StanceCommand(this));
        bind("power", new PowerCommand(this));
        bind("powersmp", new PowerSMPCommand(this));
        bind("xp", new XpCommand(this));
        bind("kill", new KillCommand(this));

        freeze.start();
        respawnGuard.start();
        realm.start();
        cooldowns.startDisplay(kitsConfig.getBoolean("general.cooldown-action-bar", true));
        startKitTick();
        startAutosave();

        for (PowerKit kit : kits.all()) {
            kit.onEnable();
        }
        // Covers /reload and a mid-session plugin enable.
        for (Player online : Bukkit.getOnlinePlayers()) {
            handleJoin(online);
        }

        getLogger().info("PowerSMP enabled with " + kits.all().size() + " kit(s).");
    }

    @Override
    public void onDisable() {
        // A movement burst temporarily enables flight. Restore it before kit cleanup changes any
        // legitimate flight state, and before cancelling the delayed task that would normally end
        // the burst.
        for (Player player : Bukkit.getOnlinePlayers()) {
            MovementExemption.restore(player);
        }
        for (PowerKit kit : kits == null ? java.util.List.<PowerKit>of() : kits.all()) {
            kit.onDisable();
        }
        if (freeze != null) {
            freeze.shutdown();
        }
        if (respawnGuard != null) {
            respawnGuard.shutdown();
        }
        if (realm != null) {
            realm.shutdown();
        }
        if (cooldowns != null) {
            cooldowns.shutdown();
        }
        Bukkit.getScheduler().cancelTasks(this);
        if (energyRecipeKey != null) {
            Bukkit.removeRecipe(energyRecipeKey);
        }
        if (data != null) {
            data.markDirty();
            data.save();
        }
    }

    private void bind(String name, CommandExecutor handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor(handler);
        if (handler instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    /** Pushes the current kits.yml into every service. Safe to call repeatedly. */
    private void applyConfig() {
        tickInterval = Math.max(1, kitsConfig.getInt("general.tick-interval-ticks", 20));
        TeamRules.reload(kitsConfig.getConfigurationSection("teams"));
        cooldowns.actionBarMaxSeconds(kitsConfig.getLong("general.cooldown-action-bar-max-seconds", 600L));
        cooldowns.readyNotifications(
                kitsConfig.getBoolean("general.cooldown-ready-notifications", true));
        kits.loadAssignments(kitsConfig.getConfigurationSection("assignments"));
        unlocks.reload(kitsConfig.getConfigurationSection("progression"));
        stances.reload(kitsConfig.getConfigurationSection("mavricc"));
        food.reload(kitsConfig.getConfigurationSection("mavricc"));
        realm.reload(kitsConfig.getConfigurationSection("illusory-realm"));
        respawnGuard.reload(kitsConfig.getConfigurationSection("respawn-protection"));
        mavricc.reload(kitsConfig.getConfigurationSection("mavricc"));
        northOfNowhere.reload(kitsConfig.getConfigurationSection("northofnowhere"));
        xcritic.reload(kitsConfig.getConfigurationSection("xcr1t1cx"));
        kornflakis.reload(kitsConfig.getConfigurationSection("kornflakis"));
        itzmetentx.reload(kitsConfig.getConfigurationSection("itzmetentx"));
        jjlion.reload(kitsConfig.getConfigurationSection("jjlionjxi"));
        doman.reload(kitsConfig.getConfigurationSection("domanthegamer"));
        spark.reload(kitsConfig.getConfigurationSection("sparkkkkkkkk"));
        nightscar.reload(kitsConfig.getConfigurationSection("night_scar3"));
        marb.reload(kitsConfig.getConfigurationSection("marb13"));
        llamachas.reload(kitsConfig.getConfigurationSection("llamachas"));
        monkeyman.reload(kitsConfig.getConfigurationSection("monkeyman"));
        techknight.reload(kitsConfig.getConfigurationSection("techknight"));
        voidwalker.reload(kitsConfig.getConfigurationSection("voidwalker"));
        disasterflames.reload(kitsConfig.getConfigurationSection("disasterflames"));
        theghost.reload(kitsConfig.getConfigurationSection("theghost"));
        phantom.reload(kitsConfig.getConfigurationSection("phantom"));
        lucky.reload(kitsConfig.getConfigurationSection("lucky"));
        lifestealer.reload(kitsConfig.getConfigurationSection("lifestealer"));
        crazyTNT2Cool.reload(kitsConfig.getConfigurationSection("crazytnt2cool"));
        idleDeathGamble.reload(kitsConfig.getConfigurationSection("idledeathgamble"));
        bitesTheDust.reload(kitsConfig.getConfigurationSection("bites-the-dust"));
    }

    /** {@code /powersmp reload}: re-reads kits.yml and restarts the tick at the new interval. */
    public void reloadKits() {
        Map<UUID, List<PowerKit>> previousAssignments = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            previousAssignments.put(player.getUniqueId(), kits.assignedKitsOf(player));
        }

        kitsConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), KITS_FILE));
        applyConfig();

        // Reconcile online players whose assignment changed. Without this, old infinite effects,
        // attribute modifiers and visibility state survive the reload, while newly assigned kits
        // never receive their signature items until the next relog.
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<PowerKit> before = previousAssignments.getOrDefault(player.getUniqueId(), List.of());
            List<PowerKit> after = kits.assignedKitsOf(player);
            Set<String> beforeIds = new HashSet<>();
            Set<String> afterIds = new HashSet<>();
            before.forEach(kit -> beforeIds.add(kit.id()));
            after.forEach(kit -> afterIds.add(kit.id()));
            // A Lucky override hides the permanent assignment from normal lookups. Tear it down
            // explicitly if reload removed Lucky, otherwise the rolled kit would live forever.
            if (beforeIds.contains(LuckyKit.ID) && !afterIds.contains(LuckyKit.ID)) {
                lucky.onRevoke(player, Power.LUCKY_ROLL);
            }
            for (PowerKit kit : before) {
                if (!afterIds.contains(kit.id())) {
                    kit.onQuit(player);
                }
            }
            for (PowerKit kit : after) {
                if (!beforeIds.contains(kit.id())) {
                    kit.onJoin(player);
                }
            }
            sanitizeAbilityBindings(player);
        }

        // Only restart the recurring services whose cadence/config changed. cancelTasks(this)
        // also kills in-flight cleanup (unhide, restore fake blocks, end movement exemptions,
        // Lucky rerolls, realm supervision), leaving powers permanently stuck.
        stopRecurringTasks();
        cooldowns.stopDisplay();
        freeze.start();
        respawnGuard.start();
        realm.start();
        cooldowns.startDisplay(kitsConfig.getBoolean("general.cooldown-action-bar", true));
        startKitTick();
        startAutosave();
    }

    private void registerEnergyRecipe() {
        energyRecipeKey = new NamespacedKey(this, "energy_core");
        Bukkit.removeRecipe(energyRecipeKey);
        ShapedRecipe recipe = new ShapedRecipe(energyRecipeKey, ResourcePackItems.energyCore());
        recipe.shape("ABA", "BCB", "ABA");
        recipe.setIngredient('A', Material.EXPERIENCE_BOTTLE);
        recipe.setIngredient('B', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('C', Material.NETHERITE_CHESTPLATE);
        Bukkit.addRecipe(recipe);
    }

    /** Removes vanilla and datapack crafting recipes whose result is a mace. */
    private void removeMaceRecipes() {
        List<NamespacedKey> maceRecipes = new java.util.ArrayList<>();
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (recipe.getResult().getType() == Material.MACE
                    && recipe instanceof org.bukkit.Keyed keyed) {
                maceRecipes.add(keyed.getKey());
            }
        });
        maceRecipes.forEach(Bukkit::removeRecipe);
    }

    private void startKitTick() {
        if (kitTickTask != null) {
            kitTickTask.cancel();
        }
        kitTickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                boundItems.purgeMaces(player);
                for (PowerKit kit : kits.kitsOf(player)) {
                    try {
                        kit.tick(player);
                    } catch (Exception ex) {
                        // One kit throwing must not stop the others from ticking.
                        getLogger().warning("Kit '" + kit.id() + "' threw during tick for "
                                + player.getName() + ": " + ex);
                    }
                }
            }
        }, tickInterval, tickInterval);
    }

    private void startAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        long seconds = Math.max(30, kitsConfig.getInt("general.autosave-seconds", 300));
        autosaveTask = Bukkit.getScheduler().runTaskTimer(
                this, () -> data.saveAsync(), seconds * 20L, seconds * 20L);
    }

    private void stopRecurringTasks() {
        if (kitTickTask != null) {
            kitTickTask.cancel();
            kitTickTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }

    // ---- player lifecycle ------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        handleJoin(event.getPlayer());
    }

    private void handleJoin(Player player) {
        MovementExemption.restore(player);
        // Temporary combat modifiers are scheduled for removal, but a crash can interrupt that
        // task. Repair them for every player before their assigned kit is reapplied.
        Attributes.clear(player, Attributes.ATTACK_SPEED, Keys.TIDAL_COMBO_ATTACK_SPEED);
        boundItems.purgeMaces(player);
        data.get(player.getUniqueId()).lastKnownName(player.getName());
        data.markDirty();
        cooldowns.hydrate(player.getUniqueId());
        sanitizeAbilityBindings(player);
        for (PowerKit kit : kits.kitsOf(player)) {
            kit.onJoin(player);
        }
    }

    /**
     * Removes bindings to abilities the player no longer owns after a kit removal or upgrade.
     *
     * @return number of stale binding entries removed
     */
    public int sanitizeAbilityBindings(Player player) {
        Set<String> valid = new HashSet<>();
        valid.add(KeybindMenu.UNBOUND);
        for (PowerKit kit : kits.assignedKitsOf(player)) {
            for (Ability ability : kit.abilities()) {
                valid.add(ability.id().toLowerCase(java.util.Locale.ROOT));
            }
        }
        com.powersmp.data.PlayerData playerData = data.get(player.getUniqueId());
        int before = playerData.abilityBindings().size();
        playerData.abilityBindings().entrySet().removeIf(entry ->
                !valid.contains(entry.getValue().toLowerCase(java.util.Locale.ROOT)));
        boolean removedPrimary = !playerData.primaryAbility().isBlank()
                && !valid.contains(playerData.primaryAbility().toLowerCase(java.util.Locale.ROOT));
        if (removedPrimary) {
            playerData.primaryAbility("");
        }
        int removed = before - playerData.abilityBindings().size();
        if (removed > 0 || removedPrimary) {
            data.markDirty();
            int total = removed + (removedPrimary ? 1 : 0);
            Text.msg(player, "<yellow>Removed " + total
                    + " stale ability control" + (total == 1 ? "" : "s")
                    + " from an old kit.</yellow>");
        }
        return removed + (removedPrimary ? 1 : 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (PowerKit kit : kits.kitsOf(event.getPlayer())) {
            kit.onQuit(event.getPlayer());
        }
        cooldowns.clearAll(event.getPlayer().getUniqueId());
    }

    // ---- service accessors ----------------------------------------------

    public FileConfiguration kitsConfig() {
        return kitsConfig;
    }

    public DataStore data() {
        return data;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public FreezeUtil freeze() {
        return freeze;
    }

    public KitRegistry kits() {
        return kits;
    }

    public UnlockManager unlocks() {
        return unlocks;
    }

    public StanceManager stances() {
        return stances;
    }

    public MushroomHungerService food() {
        return food;
    }

    public TechKnightKit techknight() {
        return techknight;
    }

    public KornFlakisKit kornflakis() {
        return kornflakis;
    }

    public IllusoryRealm realm() {
        return realm;
    }

    public PowerMenu powerMenu() {
        return powerMenu;
    }

    public KeybindMenu keybindMenu() {
        return keybindMenu;
    }

    public int kitTickIntervalTicks() {
        return tickInterval;
    }
}
