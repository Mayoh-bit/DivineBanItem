package com.divinebanitem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class NbtUtils {
    private static final Object ACCESS_LOCK = new Object();
    private static volatile ReflectionAccess reflectionAccess;
    private static volatile boolean reflectionResolved;

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
            ReflectionAccess access = getReflectionAccess();
            if (access == null) {
                return stack;
            }
            Object nmsItem = access.asNmsCopy.invoke(null, stack);
            Object compoundTag = access.parseTag.invoke(null, snbt);
            if (access.setTag != null) {
                access.setTag.invoke(nmsItem, compoundTag);
            }
            return (ItemStack) access.asBukkitCopy.invoke(null, nmsItem);
        } catch (Exception ignored) {
        }
        return stack;
    }

    private static Object getCompoundTag(ItemStack stack) throws Exception {
        if (stack == null) {
            return null;
        }
        ReflectionAccess access = getReflectionAccess();
        if (access == null) {
            return null;
        }
        Object nmsItem = access.asNmsCopy.invoke(null, stack);
        Object tag = access.compoundTagCtor.newInstance();
        return access.saveMethod.invoke(nmsItem, tag);
    }

    private static ReflectionAccess getReflectionAccess() {
        if (reflectionResolved) {
            return reflectionAccess;
        }
        synchronized (ACCESS_LOCK) {
            if (!reflectionResolved) {
                reflectionAccess = ReflectionAccess.create();
                reflectionResolved = true;
            }
        }
        return reflectionAccess;
    }

    private static final class ReflectionAccess {
        private final Method asNmsCopy;
        private final Method asBukkitCopy;
        private final Constructor<?> compoundTagCtor;
        private final Method saveMethod;
        private final Method parseTag;
        private final Method setTag;

        private ReflectionAccess(Method asNmsCopy, Method asBukkitCopy, Constructor<?> compoundTagCtor,
                                 Method saveMethod, Method parseTag, Method setTag) {
            this.asNmsCopy = asNmsCopy;
            this.asBukkitCopy = asBukkitCopy;
            this.compoundTagCtor = compoundTagCtor;
            this.saveMethod = saveMethod;
            this.parseTag = parseTag;
            this.setTag = setTag;
        }

        private static ReflectionAccess create() {
            try {
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
                Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
                Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStackClass);
                Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
                Constructor<?> ctor = compoundTagClass.getConstructor();
                Method saveMethod = nmsItemStackClass.getMethod("save", compoundTagClass);
                Class<?> tagParser = Class.forName("net.minecraft.nbt.TagParser");
                Method parseTag = tagParser.getMethod("parseTag", String.class);
                Method setTag = null;
                for (Method method : nmsItemStackClass.getMethods()) {
                    if (method.getName().equals("setTag") && method.getParameterCount() == 1) {
                        setTag = method;
                        break;
                    }
                }
                return new ReflectionAccess(asNmsCopy, asBukkitCopy, ctor, saveMethod, parseTag, setTag);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
