package com.divinebanitem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Resolves GUI icons for both vanilla and Forge items with optional SNBT overrides.
 * Uses reflection to avoid a hard dependency on CraftBukkit/NMS classes.
 */
public final class ItemStackResolver {
    private static final Object ACCESS_LOCK = new Object();
    private static final AtomicBoolean FORGE_MISSING_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FORGE_ERROR_LOGGED = new AtomicBoolean(false);
    private static volatile ReflectionAccess reflectionAccess;
    private static volatile boolean reflectionResolved;

    private ItemStackResolver() {
    }

    public static ItemStack resolveIcon(String namespacedId, String snbt, String displayName, List<String> lore) {
        if (namespacedId == null || namespacedId.isBlank()) {
            return createFallbackItem("<invalid>", displayName, lore);
        }
        ItemStack result = resolveForgeStack(namespacedId, snbt);
        if (result == null) {
            result = resolveBukkitStack(namespacedId, snbt);
        }
        if (result == null) {
            return createFallbackItem(namespacedId, displayName, lore);
        }
        applyDisplayMeta(result, displayName, lore);
        return result;
    }

    private static ItemStack resolveForgeStack(String namespacedId, String snbt) {
        ReflectionAccess access = getReflectionAccess();
        if (access == null || access.forgeRegistry == null) {
            if (FORGE_MISSING_LOGGED.compareAndSet(false, true)) {
                Bukkit.getLogger().info("Forge registry 不可用，GUI 将回退至 Bukkit 物品解析。");
            }
            return null;
        }
        try {
            Object resLoc = access.resourceLocationCtor.newInstance(namespacedId);
            Object item = access.getRegistryItem(access.forgeRegistry, resLoc);
            if (item == null && access.builtInRegistry != null) {
                item = access.getRegistryItem(access.builtInRegistry, resLoc);
            }
            if (item == null) {
                return null;
            }
            Constructor<?> itemStackCtor = access.findItemStackConstructor(item.getClass());
            if (itemStackCtor == null) {
                return null;
            }
            Object nmsStack = itemStackCtor.newInstance(item, 1);
            if (snbt != null && !snbt.isBlank() && access.parseTag != null) {
                Object tag = access.parseTag.invoke(null, snbt);
                if (access.setTag != null) {
                    access.setTag.invoke(nmsStack, tag);
                }
            }
            return (ItemStack) access.asBukkitCopy.invoke(null, nmsStack);
        } catch (Exception ex) {
            if (FORGE_ERROR_LOGGED.compareAndSet(false, true)) {
                Bukkit.getLogger().warning("Forge 物品图标解析失败，GUI 将回退至 Bukkit 物品解析。");
            }
            return null;
        }
    }

    private static ItemStack resolveBukkitStack(String namespacedId, String snbt) {
        NamespacedKey key = NamespacedKey.fromString(namespacedId);
        Material material = null;
        if (key != null) {
            try {
                material = Bukkit.getRegistry(Material.class).get(key);
            } catch (Exception ignored) {
                material = null;
            }
        }
        if (material == null) {
            material = Material.matchMaterial(namespacedId);
        }
        if (material == null) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        if (snbt != null && !snbt.isBlank()) {
            stack = NbtUtils.applySnbt(stack, snbt);
        }
        return stack;
    }

    private static ItemStack createFallbackItem(String namespacedId, String displayName, List<String> lore) {
        ItemStack barrier = new ItemStack(Material.BARRIER);
        List<String> mergedLore = new ArrayList<>();
        if (lore != null) {
            mergedLore.addAll(lore);
        }
        mergedLore.add(MessageService.colorize("&c未检测到 Forge registry 或物品不存在"));
        mergedLore.add(MessageService.colorize("&7物品: &f" + namespacedId));
        applyDisplayMeta(barrier, displayName, mergedLore);
        return barrier;
    }

    private static void applyDisplayMeta(ItemStack stack, String displayName, List<String> lore) {
        if (displayName == null && (lore == null || lore.isEmpty())) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (displayName != null) {
            meta.setDisplayName(displayName);
        }
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
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
        private final Constructor<?> resourceLocationCtor;
        private final Object forgeRegistry;
        private final Object builtInRegistry;
        private final Method asBukkitCopy;
        private final Class<?> nmsItemStackClass;
        private final Method parseTag;
        private final Method setTag;
        private final Method registryGet;
        private final Method registryGetValue;

        private ReflectionAccess(Constructor<?> resourceLocationCtor, Object forgeRegistry, Object builtInRegistry,
                                 Method asBukkitCopy, Class<?> nmsItemStackClass, Method parseTag, Method setTag,
                                 Method registryGet, Method registryGetValue) {
            this.resourceLocationCtor = resourceLocationCtor;
            this.forgeRegistry = forgeRegistry;
            this.builtInRegistry = builtInRegistry;
            this.asBukkitCopy = asBukkitCopy;
            this.nmsItemStackClass = nmsItemStackClass;
            this.parseTag = parseTag;
            this.setTag = setTag;
            this.registryGet = registryGet;
            this.registryGetValue = registryGetValue;
        }

        private static ReflectionAccess create() {
            try {
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
                Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStackClass);
                Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
                Constructor<?> resLocCtor = resourceLocation.getConstructor(String.class);
                Object forgeRegistry = null;
                try {
                    Class<?> forgeRegistries = Class.forName("net.minecraftforge.registries.ForgeRegistries");
                    forgeRegistry = forgeRegistries.getField("ITEMS").get(null);
                } catch (Exception ignored) {
                    forgeRegistry = null;
                }
                Object builtInRegistry = null;
                try {
                    Class<?> builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                    builtInRegistry = builtInRegistries.getField("ITEM").get(null);
                } catch (Exception ignored) {
                    builtInRegistry = null;
                }
                Method parseTag = null;
                Method setTag = null;
                try {
                    Class<?> tagParser = Class.forName("net.minecraft.nbt.TagParser");
                    parseTag = tagParser.getMethod("parseTag", String.class);
                    for (Method method : nmsItemStackClass.getMethods()) {
                        if (method.getName().equals("setTag") && method.getParameterCount() == 1) {
                            setTag = method;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    parseTag = null;
                    setTag = null;
                }
                Method registryGet = null;
                Method registryGetValue = null;
                Object registrySample = forgeRegistry != null ? forgeRegistry : builtInRegistry;
                if (registrySample != null) {
                    try {
                        registryGet = registrySample.getClass().getMethod("get", resourceLocation);
                    } catch (Exception ignored) {
                        registryGet = null;
                    }
                    try {
                        registryGetValue = registrySample.getClass().getMethod("getValue", resourceLocation);
                    } catch (Exception ignored) {
                        registryGetValue = null;
                    }
                }
                return new ReflectionAccess(resLocCtor, forgeRegistry, builtInRegistry, asBukkitCopy,
                    nmsItemStackClass, parseTag, setTag, registryGet, registryGetValue);
            } catch (Exception ignored) {
                return null;
            }
        }

        private Object getRegistryItem(Object registry, Object resLoc) {
            if (registry == null || resLoc == null) {
                return null;
            }
            try {
                if (registryGet != null) {
                    return registryGet.invoke(registry, resLoc);
                }
                if (registryGetValue != null) {
                    return registryGetValue.invoke(registry, resLoc);
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }

        private Constructor<?> findItemStackConstructor(Class<?> itemClass) {
            for (Constructor<?> constructor : nmsItemStackClass.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && params[1] == int.class && params[0].isAssignableFrom(itemClass)) {
                    return constructor;
                }
            }
            return null;
        }
    }
}
