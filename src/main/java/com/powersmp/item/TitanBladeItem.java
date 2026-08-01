package com.powersmp.item;

import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tech Knight's Titan Protocol weapon.
 *
 * <p>The researched Altar SMP Bone Blade uses an unbreakable Netherite Sword baseline. Titan
 * Protocol adds the requested kill tiers on top of the Altar weapon's active abilities.
 */
public final class TitanBladeItem {

    private TitanBladeItem() {
    }

    public static ItemStack create(UUID owner, int tier, int playerKills,
                                   int tierTwoKills, int tierThreeKills) {
        ItemStack blade = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = blade.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer()
                    .set(Keys.TITAN_BLADE_OWNER, PersistentDataType.STRING, owner.toString());
            Enchants.applyVanishing(meta);
            blade.setItemMeta(meta);
        }
        update(blade, tier, playerKills, tierTwoKills, tierThreeKills);
        return blade;
    }

    public static void update(ItemStack blade, int tier, int playerKills,
                              int tierTwoKills, int tierThreeKills) {
        if (!isBoneBlade(blade)) {
            return;
        }
        int safeTier = Math.max(1, Math.min(3, tier));
        ItemMeta meta = blade.getItemMeta();
        meta.displayName(Text.mm("<gradient:#f5f0df:#9b8d73><bold>Bone Blade</bold></gradient>"));
        int sharpness = switch (safeTier) {
            case 2 -> 5;
            case 3 -> 8;
            default -> 3;
        };
        if (Enchants.SHARPNESS != null) {
            meta.addEnchant(Enchants.SHARPNESS, sharpness, true);
        }
        meta.getPersistentDataContainer()
                .set(Keys.TITAN_BLADE_TIER, PersistentDataType.INTEGER, safeTier);
        String progress = switch (safeTier) {
            case 1 -> playerKills + "/" + tierTwoKills + " player kills to Tier II";
            case 2 -> playerKills + "/" + tierThreeKills + " player kills to Tier III";
            default -> "Maximum tier reached";
        };
        meta.lore(List.of(
                Text.mm("<gold><bold>Titan Protocol — Tier " + numeral(safeTier) + "</bold></gold>"),
                Text.mm("<gray>" + progress + "</gray>"),
                Text.mm("<yellow>Sharpness " + roman(sharpness) + "</yellow>"),
                Text.mm("<red>" + switch (safeTier) {
                    case 2 -> "+5 attack damage";
                    case 3 -> "+9 attack damage";
                    default -> "+2 attack damage";
                } + "</red>"),
                Text.mm(switch (safeTier) {
                    case 2 -> "<aqua>Strength II, Speed I, Fire Resistance</aqua>";
                    case 3 -> "<aqua>Strength II, Speed II, Resistance II, Fire Resistance</aqua>";
                    default -> "<gray>More systems unlock at Tier II</gray>";
                }),
                Text.mm("<white>Skeletal Leap</white><gray> - forward leap + Speed III</gray>"),
                Text.mm("<white>Bone Cage</white><gray> - 5s ranged stun</gray>"),
                Text.mm("<dark_gray>Inventory passive • Soulbound</dark_gray>")));
        blade.setItemMeta(meta);
        ResourcePackItems.apply(blade, ResourcePackItems.BONE_BLADE);
    }

    public static UUID ownerOf(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer()
                .get(Keys.TITAN_BLADE_OWNER, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static int tierOf(ItemStack item) {
        if (!isBoneBlade(item)) {
            return 0;
        }
        Integer tier = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.TITAN_BLADE_TIER, PersistentDataType.INTEGER);
        return tier == null ? 1 : Math.max(1, Math.min(3, tier));
    }

    public static boolean isBoneBlade(ItemStack item) {
        return ownerOf(item) != null;
    }

    private static String numeral(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            default -> "III";
        };
    }

    private static String roman(int level) {
        return switch (level) {
            case 3 -> "III";
            case 5 -> "V";
            case 8 -> "VIII";
            default -> Integer.toString(level);
        };
    }
}
