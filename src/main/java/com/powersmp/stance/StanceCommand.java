package com.powersmp.stance;

import com.powersmp.PowerSMP;
import com.powersmp.progression.Power;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /stance red|blue|green|none} -- only for players whose kit has the stance power. */
public class StanceCommand implements CommandExecutor, TabCompleter {

    private final PowerSMP plugin;

    public StanceCommand(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Text.msg(sender, "<red>Only players have stances.");
            return true;
        }
        if (!plugin.unlocks().isUnlocked(player, Power.STANCE_CHANGE)) {
            Text.msg(player, "<red>Your kit does not have stances.");
            return true;
        }
        if (args.length == 0) {
            Stance current = plugin.stances().stanceOf(player);
            Text.msg(player, "Current stance: " + current.coloured()
                    + (plugin.stances().hasAffinity(player)
                    ? " <dark_purple>(Mushroom Affinity active)</dark_purple>" : ""));
            Text.raw(player, "<gray>Usage: <white>/stance <red>red</red>|<aqua>blue</aqua>|<green>green</green>|<gray>none</gray>");
            return true;
        }
        Stance stance = Stance.parse(args[0]);
        if (stance == null) {
            Text.msg(player, "<red>Unknown stance <white>" + Text.plain(args[0])
                    + "</white>. Valid: red, blue, green, none.");
            return true;
        }
        plugin.stances().setStance(player, stance);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Stance stance : Stance.values()) {
            String name = stance.configKey();
            if (name.startsWith(prefix)) {
                out.add(name);
            }
        }
        return out;
    }
}
