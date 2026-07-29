package com.powersmp.command;

import com.powersmp.PowerSMP;
import com.powersmp.data.PlayerData;
import com.powersmp.item.SpearItem;
import com.powersmp.kit.PowerKit;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /powersmp} -- admin tooling: reload, inspect, and grant or revoke unlocks by hand. */
public class PowerSMPCommand implements CommandExecutor, TabCompleter {

    private final PowerSMP plugin;

    public PowerSMPCommand(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadKits();
                Text.msg(sender, "<green>Reloaded kits.yml.");
            }
            case "info" -> info(sender, args);
            case "grant" -> grantOrRevoke(sender, args, true);
            case "revoke" -> grantOrRevoke(sender, args, false);
            case "kills" -> kills(sender, args);
            case "spear" -> spear(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        Text.msg(sender, "<gray>PowerSMP admin</gray>");
        Text.raw(sender, "<dark_gray>  •</dark_gray> <white>/powersmp reload</white>");
        Text.raw(sender, "<dark_gray>  •</dark_gray> <white>/powersmp info [player]</white>");
        Text.raw(sender, "<dark_gray>  •</dark_gray> <white>/powersmp grant|revoke <player> <power></white>");
        Text.raw(sender, "<dark_gray>  •</dark_gray> <white>/powersmp kills <player> [amount]</white>");
        Text.raw(sender, "<dark_gray>  •</dark_gray> <white>/powersmp spear [player]</white>");
    }

    private void info(CommandSender sender, String[] args) {
        Player target = resolve(sender, args, 1);
        if (target == null) {
            return;
        }
        List<PowerKit> kits = plugin.kits().kitsOf(target);
        PlayerData data = plugin.data().get(target.getUniqueId());

        String kitLabel = kits.isEmpty() ? "none" : kits.stream()
                .map(kit -> Text.plain(kit.displayName() + " (" + kit.id() + ")"))
                .reduce((a, b) -> a + ", " + b).orElse("none");
        Text.msg(sender, "<white>" + Text.plain(target.getName()) + "</white> <dark_gray>-</dark_gray> kit: <aqua>"
                + kitLabel + "</aqua>");
        Text.raw(sender, "<gray>  stance:</gray> " + Text.plain(data.stance())
                + " <gray>| kills:</gray> " + data.kills()
                + " <gray>| spear:</gray> tier " + data.spearTier() + " (" + data.spearKills() + " kills)");
        Text.raw(sender, "<gray>  mushroom hunger scope:</gray> " + plugin.food().scopeName());
        for (PowerKit kit : kits) {
            for (Power power : Power.values()) {
                if (!power.kitId().equalsIgnoreCase(kit.id())) {
                    continue;
                }
                boolean on = plugin.unlocks().isUnlocked(target, power);
                Text.raw(sender, "<dark_gray>  •</dark_gray> " + (on ? "<green>✔</green> " : "<red>✘</red> ")
                        + Text.plain(power.displayName()) + " <dark_gray>(" + power.gate().name().toLowerCase(Locale.ROOT)
                        + ")</dark_gray>");
            }
        }
    }

    private void grantOrRevoke(CommandSender sender, String[] args, boolean grant) {
        if (args.length < 3) {
            Text.msg(sender, "<red>Usage: /powersmp " + (grant ? "grant" : "revoke") + " <player> <power>");
            return;
        }
        Player target = resolve(sender, args, 1);
        if (target == null) {
            return;
        }
        Power power = Power.byId(args[2]);
        if (power == null) {
            Text.msg(sender, "<red>Unknown power <white>" + Text.plain(args[2]) + "</white>.");
            return;
        }
        boolean changed = grant
                ? plugin.unlocks().grant(target, power)
                : plugin.unlocks().revoke(target, power);
        Text.msg(sender, changed
                ? "<green>" + (grant ? "Granted" : "Revoked") + " <white>" + Text.plain(power.displayName())
                        + "</white> " + (grant ? "to" : "from") + " <white>" + Text.plain(target.getName()) + "</white>."
                : "<yellow>No change -- " + Text.plain(target.getName()) + " already "
                        + (grant ? "had" : "lacked") + " that power.");
    }

    private void kills(CommandSender sender, String[] args) {
        Player target = resolve(sender, args, 1);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.data().get(target.getUniqueId());
        if (args.length < 3) {
            Text.msg(sender, "<white>" + Text.plain(target.getName()) + "</white> has <aqua>"
                    + data.kills() + "</aqua> kill(s).");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            Text.msg(sender, "<red>'" + Text.plain(args[2]) + "' is not a number.");
            return;
        }
        data.kills(amount);
        plugin.data().markDirty();
        plugin.unlocks().checkKillUnlocks(target);
        Text.msg(sender, "<green>Set <white>" + Text.plain(target.getName()) + "</white> to "
                + amount + " kill(s).");
    }

    private void spear(CommandSender sender, String[] args) {
        Player target = resolve(sender, args, 1);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.data().get(target.getUniqueId());
        target.getInventory().addItem(SpearItem.create(target.getUniqueId(), data.spearTier()));
        Text.msg(sender, "<green>Gave <white>" + Text.plain(target.getName())
                + "</white> a Lunge " + SpearItem.numeral(data.spearTier()) + " spear.");
    }

    /** Resolves args[index] as a player name, defaulting to the sender when omitted. */
    private Player resolve(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player found = Bukkit.getPlayerExact(args[index]);
            if (found == null) {
                Text.msg(sender, "<red>Player <white>" + Text.plain(args[index]) + "</white> is not online.");
            }
            return found;
        }
        if (sender instanceof Player self) {
            return self;
        }
        Text.msg(sender, "<red>Name a player -- the console is not one.");
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("reload", "info", "grant", "revoke", "kills", "spear")) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
        } else if (args.length == 3
                && (args[0].equalsIgnoreCase("grant") || args[0].equalsIgnoreCase("revoke"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Power power : Power.values()) {
                if (power.id().startsWith(prefix)) {
                    out.add(power.id());
                }
            }
        }
        return out;
    }
}
