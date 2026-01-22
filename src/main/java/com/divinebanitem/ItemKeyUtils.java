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
        if (material == null && key != null) {
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains(":")) {
                String legacy = normalized.substring(normalized.indexOf(':') + 1);
                material = Material.matchMaterial(legacy);
                if (material == null) {
                    material = Material.getMaterial(legacy.toUpperCase(java.util.Locale.ROOT));
                }
            } else {
                material = Material.getMaterial(normalized.toUpperCase(java.util.Locale.ROOT));
            }
        }
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
            Object key = getRegistryKey(registry, item);
            if (key != null) {
                return key.toString();
            }
            Object forgeRegistry = getForgeItemRegistry();
            Object forgeKey = getRegistryKey(forgeRegistry, item);
            if (forgeKey != null) {
                return forgeKey.toString();
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
            Object item = getRegistryItem(registry, resLoc);
            if (item == null) {
                Object forgeRegistry = getForgeItemRegistry();
                item = getRegistryItem(forgeRegistry, resLoc);
            }
            if (item == null) {
                return null;
            }
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Constructor<?> itemStackCtor = findItemStackConstructor(nmsItemStack, item.getClass());
            if (itemStackCtor == null) {
                return null;
            }
            Object nmsStack = itemStackCtor.newInstance(item, amount);
            Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            return (ItemStack) asBukkitCopy.invoke(null, nmsStack);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object getForgeItemRegistry() {
        try {
            Class<?> forgeRegistries = Class.forName("net.minecraftforge.registries.ForgeRegistries");
            Field itemsField = forgeRegistries.getField("ITEMS");
            return itemsField.get(null);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object getRegistryItem(Object registry, Object resLoc) {
        if (registry == null || resLoc == null) {
            return null;
        }
        try {
            Method get = registry.getClass().getMethod("get", resLoc.getClass());
            return get.invoke(registry, resLoc);
        } catch (NoSuchMethodException ignored) {
            try {
                Method getValue = registry.getClass().getMethod("getValue", resLoc.getClass());
                return getValue.invoke(registry, resLoc);
            } catch (Exception ignoredAgain) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object getRegistryKey(Object registry, Object item) {
        if (registry == null || item == null) {
            return null;
        }
        try {
            Method getKey = registry.getClass().getMethod("getKey", Object.class);
            return getKey.invoke(registry, item);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Constructor<?> findItemStackConstructor(Class<?> nmsItemStack, Class<?> itemClass) {
        for (Constructor<?> constructor : nmsItemStack.getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 2 && params[1] == int.class && params[0].isAssignableFrom(itemClass)) {
                return constructor;
            }
        }
        return null;
    }
}
