package com.divinebanitem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemKeyUtils {
    private ItemKeyUtils() {
    }

    public static String getItemKey(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return "minecraft:air";
        }
        String nmsKey = getItemKeyFromNms(stack);
        if (nmsKey != null) {
            return nmsKey;
        }
        return stack.getType().getKey().toString();
    }

    public static ItemStack createItemStack(String key, int amount) {
        ItemStack fromNms = createFromNms(key, amount);
        if (fromNms != null) {
            return fromNms;
        }
        Material material = Material.matchMaterial(key);
        if (material == null) {
            return null;
        }
        return new ItemStack(material, amount);
    }

    private static String getItemKeyFromNms(ItemStack stack) {
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            Object nmsItem = asNmsCopy.invoke(null, stack);
            Method getItem = nmsItem.getClass().getMethod("getItem");
            Object item = getItem.invoke(nmsItem);

            Class<?> builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Field itemRegistryField = builtInRegistries.getField("ITEM");
            Object registry = itemRegistryField.get(null);
            Method getKey = registry.getClass().getMethod("getKey", Object.class);
            Object key = getKey.invoke(registry, item);
            if (key != null) {
                return key.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ItemStack createFromNms(String key, int amount) {
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
            Constructor<?> resLocCtor = resourceLocation.getConstructor(String.class);
            Object resLoc = resLocCtor.newInstance(key);

            Class<?> builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Field itemRegistryField = builtInRegistries.getField("ITEM");
            Object registry = itemRegistryField.get(null);
            Method get = registry.getClass().getMethod("get", resourceLocation);
            Object item = get.invoke(registry, resLoc);
            if (item == null) {
                return null;
            }
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Constructor<?> itemStackCtor = nmsItemStack.getConstructor(item.getClass(), int.class);
            Object nmsStack = itemStackCtor.newInstance(item, amount);
            Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            return (ItemStack) asBukkitCopy.invoke(null, nmsStack);
        } catch (Exception ignored) {
        }
        return null;
    }
}
