package com.divinebanitem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

public class BanRuleManager {
    private final Map<String, BanRule> rules = new HashMap<>();
    private final List<RecipeOverride> overrides = new ArrayList<>();

    public void load(FileConfiguration config) {
        rules.clear();
        overrides.clear();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries != null) {
            for (String key : entries.getKeys(false)) {
                ConfigurationSection section = entries.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                BanRule rule = buildRule(key, section);
                rules.put(key, rule);
            }
        }

        List<Map<?, ?>> overrideList = config.getMapList("recipeOverrides");
        for (Map<?, ?> entry : overrideList) {
            overrides.add(RecipeOverride.from(entry));
        }
    }

    public Collection<BanRule> getRules() {
        return Collections.unmodifiableCollection(rules.values());
    }

    public Optional<BanRule> getRule(String key) {
        return Optional.ofNullable(rules.get(key));
    }

    public String resolveKey(ItemStack stack, BanRule.NbtMode nbtMode, String nbtPath, String nbtValue) {
        String itemKey = ItemKeyUtils.getItemKey(stack);
        String existing = findExistingKey(itemKey, nbtMode, nbtPath, nbtValue);
        if (existing != null) {
            return existing;
        }
        String base = itemKey.toLowerCase(Locale.ROOT).replace(":", "_");
        return createUniqueKey(base);
    }

    public BanRule upsertRule(FileConfiguration config, File configFile, String key, ItemStack stack,
                              BanRule.NbtMode nbtMode, String nbtPath, String nbtValue) throws IOException {
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null) {
            entries = config.createSection("entries");
        }
        ConfigurationSection section = entries.getConfigurationSection(key);
        boolean isNew = section == null;
        if (section == null) {
            section = entries.createSection(key);
        }
        ConfigurationSection itemSection = section.getConfigurationSection("item");
        if (itemSection == null) {
            itemSection = section.createSection("item");
        }
        itemSection.set("key", ItemKeyUtils.getItemKey(stack));
        itemSection.set("nbtMode", nbtMode.name());
        itemSection.set("nbtValue", nbtValue == null ? "" : nbtValue);
        itemSection.set("nbtPath", nbtPath == null ? "" : nbtPath);
        if (isNew) {
            ConfigurationSection actions = section.createSection("actions");
            actions.set("use", true);
            actions.set("place", false);
            actions.set("craft", true);
            actions.set("smelt", false);
            actions.set("pickup", false);
            actions.set("drop", false);

            ConfigurationSection recipes = section.createSection("recipes");
            recipes.set("removeBukkit", true);
            recipes.set("removeForge", false);

            ConfigurationSection purchase = section.createSection("purchase");
            purchase.set("enabled", false);
            purchase.set("price", 0);
            purchase.set("durations", Collections.emptyList());
            ConfigurationSection licenseEffect = purchase.createSection("licenseEffect");
            licenseEffect.set("allowUse", true);
            licenseEffect.set("allowCraft", false);
        }
        BanRule rule = buildRule(key, section);
        rules.put(key, rule);
        config.save(configFile);
        return rule;
    }

    public BanRule match(ItemStack stack) {
        String key = ItemKeyUtils.getItemKey(stack);
        for (BanRule rule : rules.values()) {
            if (!rule.getItemKey().equalsIgnoreCase(key)) {
                continue;
            }
            if (rule.getNbtMode() == BanRule.NbtMode.ANY) {
                return rule;
            }
            String snbt = NbtUtils.getSnbt(stack);
            switch (rule.getNbtMode()) {
                case EXACT_SNBT:
                    if (snbt.equals(rule.getNbtValue())) {
                        return rule;
                    }
                    break;
                case CONTAINS_SNBT:
                    if (snbt.contains(rule.getNbtValue())) {
                        return rule;
                    }
                    break;
                case PATH_EQUALS:
                    String value = NbtUtils.getPathValue(stack, rule.getNbtPath());
                    if (value.equals(rule.getNbtValue())) {
                        return rule;
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    public List<RecipeOverride> getOverrides() {
        return Collections.unmodifiableList(overrides);
    }

    public int removeBukkitRecipesFor(ItemStack stack) {
        int removed = 0;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe == null) {
                continue;
            }
            ItemStack result = recipe.getResult();
            if (result == null) {
                continue;
            }
            String key = ItemKeyUtils.getItemKey(result);
            if (key.equalsIgnoreCase(ItemKeyUtils.getItemKey(stack))) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private String findExistingKey(String itemKey, BanRule.NbtMode nbtMode, String nbtPath, String nbtValue) {
        for (BanRule rule : rules.values()) {
            if (!rule.getItemKey().equalsIgnoreCase(itemKey)) {
                continue;
            }
            if (rule.getNbtMode() != nbtMode) {
                continue;
            }
            if (!rule.getNbtPath().equals(nbtPath == null ? "" : nbtPath)) {
                continue;
            }
            if (!rule.getNbtValue().equals(nbtValue == null ? "" : nbtValue)) {
                continue;
            }
            return rule.getKey();
        }
        return null;
    }

    private String createUniqueKey(String base) {
        String candidate = base;
        int index = 2;
        while (rules.containsKey(candidate)) {
            candidate = base + "_" + index;
            index++;
        }
        return candidate;
    }

    private BanRule buildRule(String key, ConfigurationSection section) {
        ConfigurationSection itemSection = section.getConfigurationSection("item");
        String itemKey = itemSection == null ? "minecraft:air" : itemSection.getString("key", "minecraft:air");
        String nbtModeRaw = itemSection == null ? "ANY" : itemSection.getString("nbtMode", "ANY");
        BanRule.NbtMode nbtMode = BanRule.NbtMode.valueOf(nbtModeRaw.toUpperCase(Locale.ROOT));
        String nbtValue = itemSection == null ? "" : itemSection.getString("nbtValue", "");
        String nbtPath = itemSection == null ? "" : itemSection.getString("nbtPath", "");

        BanRule.Actions actions = new BanRule.Actions();
        ConfigurationSection actionSection = section.getConfigurationSection("actions");
        if (actionSection != null) {
            actions.use = actionSection.getBoolean("use", false);
            actions.place = actionSection.getBoolean("place", false);
            actions.craft = actionSection.getBoolean("craft", false);
            actions.smelt = actionSection.getBoolean("smelt", false);
            actions.pickup = actionSection.getBoolean("pickup", false);
            actions.drop = actionSection.getBoolean("drop", false);
        }

        BanRule.Recipes recipes = new BanRule.Recipes();
        ConfigurationSection recipesSection = section.getConfigurationSection("recipes");
        if (recipesSection != null) {
            recipes.removeBukkit = recipesSection.getBoolean("removeBukkit", false);
            recipes.removeForge = recipesSection.getBoolean("removeForge", false);
        }

        BanRule.PurchaseOptions purchase = new BanRule.PurchaseOptions();
        ConfigurationSection purchaseSection = section.getConfigurationSection("purchase");
        if (purchaseSection != null) {
            purchase.enabled = purchaseSection.getBoolean("enabled", false);
            purchase.price = purchaseSection.getDouble("price", 0);
            ConfigurationSection effectSection = purchaseSection.getConfigurationSection("licenseEffect");
            if (effectSection != null) {
                purchase.allowUse = effectSection.getBoolean("allowUse", true);
                purchase.allowCraft = effectSection.getBoolean("allowCraft", false);
            }
            List<BanRule.PurchaseTier> tiers = new ArrayList<>();
            List<Map<?, ?>> durationList = purchaseSection.getMapList("durations");
            for (Map<?, ?> entry : durationList) {
                Object duration = entry.get("duration");
                Object price = entry.get("price");
                if (duration != null && price != null) {
                    tiers.add(new BanRule.PurchaseTier(duration.toString(), Double.parseDouble(price.toString())));
                }
            }
            purchase.durations = tiers;
        }
        return new BanRule(key, itemKey, nbtMode, nbtValue, nbtPath, actions, recipes, purchase);
    }
}
