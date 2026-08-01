package com.powersmp.item;

import com.powersmp.util.Keys;
import com.powersmp.util.Text;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

/** Custom-model-data ids shared by the optional lightweight PowerSMP resource pack. */
public final class ResourcePackItems {

    public static final int ENERGY_CORE = 27001;
    public static final int GRAPPLE_HOOK = 27002;
    public static final int TECH_GAUNTLET = 27003;
    public static final int FINAL_ORB = 27004;
    public static final int ASCENDED_WING = 27005;
    public static final int TECH_SHIELD = 27006;
    public static final int BONE_BLADE = 27007;
    public static final int BLOODLUST = 27008;
    public static final int NIGHTSCAR_CUTLASS = 27009;
    public static final int TIDE_TRIDENT = 27010;
    public static final int MOMENTUM_SPEAR = 27011;
    public static final int MASTERQUIZLA_GLOCK = 27012;

    private ResourcePackItems() {
    }

    public static void apply(ItemStack item, int modelData) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        // The 1.21.4+ item definition format reads custom_model_data as a float list.
        // Set it directly rather than relying on Bukkit's deprecated integer bridge.
        CustomModelDataComponent customModelData = meta.getCustomModelDataComponent();
        customModelData.setFloats(List.of((float) modelData));
        meta.setCustomModelDataComponent(customModelData);
        item.setItemMeta(meta);
    }

    /** Craftable energy used as a compact, resource-pack-friendly power component. */
    public static ItemStack energyCore() {
        ItemStack item = new ItemStack(Material.PRISMARINE_CRYSTALS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.mm("<aqua><bold>Energy Core</bold></aqua>"));
        meta.lore(List.of(Text.mm("<gray>A condensed spark of power.</gray>")));
        meta.getPersistentDataContainer().set(Keys.ENERGY_CORE, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        apply(item, ENERGY_CORE);
        return item;
    }
}
