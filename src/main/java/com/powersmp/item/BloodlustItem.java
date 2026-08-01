package com.powersmp.item;

import com.powersmp.util.Enchants;
import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Doman's soulbound, kill-scaling Altar SMP Bloodlust Sword. */
public final class BloodlustItem {

    private BloodlustItem() {
    }

    public static ItemStack create(UUID owner, int kills) {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer()
                    .set(Keys.BLOODLUST_OWNER, PersistentDataType.STRING, owner.toString());
            Enchants.applyVanishing(meta);
            sword.setItemMeta(meta);
        }
        update(sword, kills);
        return sword;
    }

    public static void update(ItemStack sword, int kills) {
        if (!isBloodlust(sword)) {
            return;
        }
        int safeKills = Math.max(0, kills);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Text.mm("<gradient:#ff4545:#640000><bold>Bloodlust</bold></gradient>"));
        meta.getPersistentDataContainer()
                .set(Keys.BLOODLUST_KILLS, PersistentDataType.INTEGER, safeKills);
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Text.mm("<red><bold>" + safeKills + "/5 player kills</bold></red>"));
        lore.add(line(safeKills, 0, "Passive Bleed"));
        lore.add(line(safeKills, 1, "Speed II while held"));
        lore.add(line(safeKills, 2, "Blood Sense"));
        lore.add(line(safeKills, 3, "Blood Trail"));
        lore.add(line(safeKills, 4, "Strength I while held"));
        lore.add(line(safeKills, 5, "Blood Chain"));
        lore.add(Text.mm("<dark_gray>Unbreakable \u2022 Soulbound</dark_gray>"));
        meta.lore(lore);
        sword.setItemMeta(meta);
        ResourcePackItems.apply(sword, ResourcePackItems.BLOODLUST);
    }

    private static net.kyori.adventure.text.Component line(int kills, int required, String name) {
        return Text.mm(kills >= required
                ? "<green>\u2714 " + name + "</green>"
                : "<dark_gray>\u2716 " + name + " (" + required + " kills)</dark_gray>");
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
                .get(Keys.BLOODLUST_OWNER, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static int killsOf(ItemStack item) {
        if (!isBloodlust(item)) {
            return 0;
        }
        Integer kills = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.BLOODLUST_KILLS, PersistentDataType.INTEGER);
        return kills == null ? 0 : Math.max(0, kills);
    }

    public static boolean isBloodlust(ItemStack item) {
        return ownerOf(item) != null;
    }
}
