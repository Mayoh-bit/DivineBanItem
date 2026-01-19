package com.divinebanitem;

import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class RecipeEditorSession {
    public static final int SIZE = 27;
    public static final int OUTPUT_SLOT = 13;
    public static final int MODE_SLOT = 15;
    public static final int SAVE_SLOT = 22;
    public static final int CLOSE_SLOT = 26;
    public static final Set<Integer> INPUT_SLOTS = Set.of(
        0, 1, 2,
        3, 4, 5,
        6, 7, 8
    );

    private final UUID playerId;
    private final Inventory inventory;
    private RecipeOverride.Type type;

    public RecipeEditorSession(UUID playerId) {
        this.playerId = playerId;
        this.type = RecipeOverride.Type.SHAPED;
        this.inventory = Bukkit.createInventory(null, SIZE, ChatColor.DARK_GREEN + "Recipe Editor");
        renderControls();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public RecipeOverride.Type getType() {
        return type;
    }

    public void toggleType() {
        type = type == RecipeOverride.Type.SHAPED ? RecipeOverride.Type.SHAPELESS : RecipeOverride.Type.SHAPED;
        renderControls();
    }

    public boolean isControlSlot(int slot) {
        return slot == MODE_SLOT || slot == SAVE_SLOT || slot == CLOSE_SLOT;
    }

    private void renderControls() {
        inventory.setItem(MODE_SLOT, createControl(Material.CRAFTING_TABLE,
            "&eMode: &f" + (type == RecipeOverride.Type.SHAPED ? "Shaped" : "Shapeless"),
            "&7Click to toggle"));
        inventory.setItem(SAVE_SLOT, createControl(Material.EMERALD_BLOCK,
            "&aSave Recipe", "&7Persist override and apply"));
        inventory.setItem(CLOSE_SLOT, createControl(Material.BARRIER,
            "&cClose", "&7Close editor"));
    }

    private ItemStack createControl(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            meta.setLore(java.util.List.of(ChatColor.translateAlternateColorCodes('&', lore)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
