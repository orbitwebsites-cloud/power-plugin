package com.powersmp.command;

import com.powersmp.PowerSMP;
import com.powersmp.kit.impl.KornFlakisKit;
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

/**
 * {@code /kill <player>} -- KornFlakis's execution.
 *
 * <p>{@code /kill} is a vanilla command that admins actually use, and simply claiming the name would
 * take it away from them. So anyone who is not the kit owner is forwarded verbatim to
 * {@code minecraft:kill}, running under their own permissions: ops keep the real command with the
 * real selectors, and KornFlakis gets his power on the same name. Vanilla is also always reachable
 * directly as {@code /minecraft:kill}.
 */
public class KillCommand implements CommandExecutor, TabCompleter {

    private final PowerSMP plugin;

    public KillCommand(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        boolean isOwner = sender instanceof Player player
                && plugin.kits().isOwner(player, KornFlakisKit.ID);

        if (!isOwner) {
            forwardToVanilla(sender, args);
            return true;
        }

        Player owner = (Player) sender;
        if (args.length == 0) {
            Text.msg(owner, "<gray>Usage: <white>/kill <player></white> <dark_gray>-- "
                    + "one execution, then a long wait.</dark_gray>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            Text.msg(owner, "<red>Player <white>" + Text.plain(args[0]) + "</white> is not online.");
            return true;
        }
        plugin.kornflakis().execute(owner, target);
        return true;
    }

    /** Hands the command back to vanilla, still subject to the sender's own permissions. */
    private void forwardToVanilla(CommandSender sender, String[] args) {
        StringBuilder vanilla = new StringBuilder("minecraft:kill");
        for (String arg : args) {
            vanilla.append(' ').append(arg);
        }
        try {
            Bukkit.dispatchCommand(sender, vanilla.toString());
        } catch (Throwable ex) {
            Text.msg(sender, "<red>Could not run the vanilla /kill. Try <white>/minecraft:kill</white>.");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
