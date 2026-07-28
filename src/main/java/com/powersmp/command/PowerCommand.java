package com.powersmp.command;

import com.powersmp.PowerSMP;
import com.powersmp.kit.Ability;
import com.powersmp.kit.PowerKit;
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

/**
 * {@code /power} -- the player-facing entry point to their own kit.
 *
 * <p>Every activated ability is reachable here. Each player's own chosen client action (see
 * {@code /power keybind}) is a shortcut for the kit's primary, but a command always works, which
 * matters for abilities whose natural trigger would collide with normal play.
 */
public class PowerCommand implements CommandExecutor, TabCompleter {

    private final PowerSMP plugin;

    public PowerCommand(PowerSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Text.msg(sender, "<red>Only players have kits.");
            return true;
        }
        PowerKit kit = plugin.kits().kitOf(player);
        if (kit == null) {
            Text.msg(player, "<gray>You have no kit assigned.</gray>");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("info")) {
            showList(player, kit);
            return true;
        }
        if (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("menu")) {
            // A clickable alternative to typing an exact ability id -- console/controller players
            // in particular are fighting an on-screen keyboard for something a click already does.
            plugin.powerMenu().open(player, kit);
            return true;
        }
        if (args[0].equalsIgnoreCase("keybind") || args[0].equalsIgnoreCase("keybinds")) {
            plugin.keybindMenu().open(player);
            return true;
        }

        String abilityId = args[0].equalsIgnoreCase("use") && args.length > 1
                ? args[1]
                : args[0];
        Ability ability = find(kit, abilityId);
        if (ability == null) {
            Text.msg(player, "<red>No ability called <white>" + Text.plain(abilityId)
                    + "</white>. Try <white>/power list</white>.");
            return true;
        }
        kit.activate(player, ability.id());
        return true;
    }

    private void showList(Player player, PowerKit kit) {
        Text.msg(player, "<white>" + Text.plain(kit.displayName()) + "</white> <dark_gray>("
                + Text.plain(kit.id()) + ")</dark_gray>");

        List<Ability> abilities = kit.abilities();
        if (abilities.isEmpty()) {
            Text.raw(player, "<gray>  This kit has no activated abilities -- it is all passive.</gray>");
        } else {
            for (Ability ability : abilities) {
                long remaining = plugin.cooldowns().remainingMillis(player.getUniqueId(), ability.id());
                String state = remaining > 0
                        ? "<red>" + Text.duration(remaining) + "</red>"
                        : "<green>ready</green>";
                Text.raw(player, "<dark_gray>  •</dark_gray> <white>/power " + Text.plain(ability.id())
                        + "</white> <dark_gray>-</dark_gray> " + Text.plain(ability.name())
                        + " <dark_gray>[" + state + "<dark_gray>]</dark_gray>");
                Text.raw(player, "<dark_gray>      " + Text.plain(ability.description()) + "</dark_gray>");
            }
        }

        List<String> locked = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        for (Power power : Power.values()) {
            if (!power.kitId().equalsIgnoreCase(kit.id())) {
                continue;
            }
            (plugin.unlocks().isUnlocked(player, power) ? unlocked : locked).add(power.displayName());
        }
        if (!unlocked.isEmpty()) {
            Text.raw(player, "<gray>Unlocked:</gray> <green>" + Text.plain(String.join(", ", unlocked)) + "</green>");
        }
        if (!locked.isEmpty()) {
            Text.raw(player, "<gray>Locked:</gray> <red>" + Text.plain(String.join(", ", locked)) + "</red>");
        }
    }

    private Ability find(PowerKit kit, String abilityId) {
        for (Ability ability : kit.abilities()) {
            if (ability.id().equalsIgnoreCase(abilityId)) {
                return ability;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        PowerKit kit = plugin.kits().kitOf(player);
        if (kit == null || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("list".startsWith(prefix)) {
            out.add("list");
        }
        if ("gui".startsWith(prefix)) {
            out.add("gui");
        }
        if ("keybind".startsWith(prefix)) {
            out.add("keybind");
        }
        for (Ability ability : kit.abilities()) {
            if (ability.id().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(ability.id());
            }
        }
        return out;
    }
}
