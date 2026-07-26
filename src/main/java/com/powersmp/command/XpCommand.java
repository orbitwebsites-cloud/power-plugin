package com.powersmp.command;

import com.powersmp.PowerSMP;
import com.powersmp.kit.impl.TechKnightKit;
import com.powersmp.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /xp} -- fills the caller's inventory with experience bottles.
 *
 * <p><b>Name collision.</b> {@code /xp} is a vanilla command (an alias of {@code /experience}).
 * Registering it here shadows the vanilla one for anyone typing it unqualified; admins who want the
 * vanilla behaviour must use {@code /minecraft:xp}. {@code /xpbottles} and {@code /powersmp:xp} are
 * registered as unambiguous aliases. Renaming this command in plugin.yml is the clean fix if
 * shadowing vanilla turns out to be a problem.
 */
public class XpCommand implements CommandExecutor {

    private final PowerSMP plugin;

    public XpCommand(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Text.msg(sender, "<red>Only players can do that.");
            return true;
        }
        if (!plugin.kits().isOwner(player, TechKnightKit.ID)) {
            Text.msg(player, "<red>That is not your power.");
            return true;
        }
        plugin.techknight().activate(player, "xp");
        return true;
    }
}
