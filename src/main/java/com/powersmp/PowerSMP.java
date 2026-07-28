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
import com.powersmp.kit.AbilityTriggerListener;
import com.powersmp.kit.KitRegistry;
import com.powersmp.kit.PowerKit;
import com.powersmp.command.XpCommand;
import com.powersmp.kit.impl.NorthOfNowhereKit;
import com.powersmp.kit.impl.ReturnByDeathKit;
import com.powersmp.kit.impl.DomanKit;
import com.powersmp.kit.impl.TheGhostKit;
import com.powersmp.kit.impl.ItzMeTentxKit;
import com.powersmp.kit.impl.JJLionKit;
import com.powersmp.kit.impl.KornFlakisKit;
import com.powersmp.kit.impl.LlamaChasKit;
import com.powersmp.kit.impl.MarbKit;
import com.powersmp.kit.impl.MavriccKit;
import com.powersmp.kit.impl.MonkeyManKit;
import com.powersmp.kit.impl.NightScarKit;
import com.powersmp.kit.impl.SparkKit;
import com.powersmp.kit.impl.TechKnightKit;
import com.powersmp.kit.impl.VoidwalkerKit;
import com.powersmp.kit.impl.XCriticKit;
import com.powersmp.menu.KeybindMenu;
import com.powersmp.menu.PowerMenu;
import com.powersmp.progression.UnlockManager;
import com.powersmp.stance.StanceCommand;
import com.powersmp.stance.StanceManager;
import com.powersmp.util.Attributes;
import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import java.io.File;
import org.bukkit.Bukkit;
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
import org.bukkit.plugin.java.JavaPlugin;

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
    private ReturnByDeathKit returnByDeath;
    private TheGhostKit theghost;

    private int tickInterval = 20;

    @Override
    public void onEnable() {
        Keys.init(this);
        Attributes.warnMissing(getLogger());
        Enchants.warnMissing(getLogger());

        saveResource(KITS_FILE, false);
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
        returnByDeath = new ReturnByDeathKit(this);
        theghost = new TheGhostKit(this);
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
        kits.register(returnByDeath);
        kits.register(theghost);

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
        Bukkit.getPluginManager().registerEvents(returnByDeath, this);
        Bukkit.getPluginManager().registerEvents(theghost, this);

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
        cooldowns.actionBarMaxSeconds(kitsConfig.getLong("general.cooldown-action-bar-max-seconds", 600L));
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
        returnByDeath.reload(kitsConfig.getConfigurationSection("returnbydeath"));
        theghost.reload(kitsConfig.getConfigurationSection("theghost"));
    }

    /** {@code /powersmp reload}: re-reads kits.yml and restarts the tick at the new interval. */
    public void reloadKits() {
        kitsConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), KITS_FILE));
        applyConfig();
        // Everything scheduled is torn down and rebuilt so a changed tick interval takes effect.
        Bukkit.getScheduler().cancelTasks(this);
        cooldowns.stopDisplay();
        freeze.start();
        respawnGuard.start();
        cooldowns.startDisplay(kitsConfig.getBoolean("general.cooldown-action-bar", true));
        startKitTick();
        startAutosave();
    }

    private void startKitTick() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PowerKit kit = kits.kitOf(player);
                if (kit == null) {
                    continue;
                }
                try {
                    kit.tick(player);
                } catch (Exception ex) {
                    // One kit throwing must not stop the others from ticking.
                    getLogger().warning("Kit '" + kit.id() + "' threw during tick for "
                            + player.getName() + ": " + ex);
                }
            }
        }, tickInterval, tickInterval);
    }

    private void startAutosave() {
        long seconds = Math.max(30, kitsConfig.getInt("general.autosave-seconds", 300));
        Bukkit.getScheduler().runTaskTimer(this, () -> data.save(), seconds * 20L, seconds * 20L);
    }

    // ---- player lifecycle ------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        handleJoin(event.getPlayer());
    }

    private void handleJoin(Player player) {
        data.get(player.getUniqueId()).lastKnownName(player.getName());
        data.markDirty();
        cooldowns.hydrate(player.getUniqueId());
        PowerKit kit = kits.kitOf(player);
        if (kit != null) {
            kit.onJoin(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PowerKit kit = kits.kitOf(event.getPlayer());
        if (kit != null) {
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
}
