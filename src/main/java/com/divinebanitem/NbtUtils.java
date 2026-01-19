package com.divinebanitem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class NbtUtils {
    private NbtUtils() {
    }

    public static String getSnbt(ItemStack stack) {
        try {
            Object tag = getCompoundTag(stack);
            if (tag != null) {
                return tag.toString();
            }
        } catch (Exception ignored) {
        }
        return stack == null ? "" : stack.serialize().toString();
    }

    public static String getPathValue(ItemStack stack, String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Object tag = getCompoundTag(stack);
            if (tag == null) {
                return "";
            }
            String[] parts = path.split("\\.");
            Object current = tag;
            for (String part : parts) {
                if (current == null) {
                    return "";
                }
                Method get = current.getClass().getMethod("get", String.class);
                current = get.invoke(current, part);
            }
            if (current == null) {
                return "";
            }
            try {
                Method getAsString = current.getClass().getMethod("getAsString");
                Object value = getAsString.invoke(current);
                return value == null ? "" : value.toString();
            } catch (NoSuchMethodException ignored) {
                return current.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static ItemStack applySnbt(ItemStack stack, String snbt) {
        if (snbt == null || snbt.isBlank() || stack == null) {
            return stack;
        }
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            Object nmsItem = asNmsCopy.invoke(null, stack);
            Class<?> tagParser = Class.forName("net.minecraft.nbt.TagParser");
            Method parseTag = tagParser.getMethod("parseTag", String.class);
            Object compoundTag = parseTag.invoke(null, snbt);
            Method setTag = null;
            for (Method method : nmsItem.getClass().getMethods()) {
                if (method.getName().equals("setTag") && method.getParameterCount() == 1) {
                    setTag = method;
                    break;
                }
            }
            if (setTag != null) {
                setTag.invoke(nmsItem, compoundTag);
            }
            Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItem.getClass());
            return (ItemStack) asBukkitCopy.invoke(null, nmsItem);
        } catch (Exception ignored) {
        }
        return stack;
    }

    private static Object getCompoundTag(ItemStack stack) throws Exception {
        if (stack == null) {
            return null;
        }
        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
        Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
        Object nmsItem = asNmsCopy.invoke(null, stack);
        Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Constructor<?> ctor = compoundTagClass.getConstructor();
        Object tag = ctor.newInstance();
        Method save = nmsItem.getClass().getMethod("save", compoundTagClass);
        return save.invoke(nmsItem, tag);
    }
}
