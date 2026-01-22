package com.divinebanitem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

public class BanRuleManager {
    private final Map<String, BanRule> rules = new LinkedHashMap<>();
    private final Map<String, RuleBucket> rulesByItemKey = new HashMap<>();
    private final List<RecipeOverride> overrides = new ArrayList<>();
    private final File configFile;
    private FileConfiguration config;

    public BanRuleManager(FileConfiguration config, File configFile) {
        this.config = config;
        this.configFile = configFile;
    }

    public void load(FileConfiguration config) {
        this.config = config;
        rules.clear();
        rulesByItemKey.clear();
        overrides.clear();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries != null) {
            for (String key : entries.getKeys(false)) {
                ConfigurationSection section = entries.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                ConfigurationSection itemSection = section.getConfigurationSection("item");
                String itemKey = itemSection == null ? "minecraft:air" : itemSection.getString("key", "minecraft:air");
                String nbtModeRaw = itemSection == null ? "ANY" : itemSection.getString("nbtMode", "ANY");
                BanRule.NbtMode nbtMode;
                try {
                    nbtMode = BanRule.NbtMode.valueOf(nbtModeRaw.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    Bukkit.getLogger().warning("Invalid nbtMode '" + nbtModeRaw + "' for entry '" + key
                        + "', defaulting to ANY.");
                    nbtMode = BanRule.NbtMode.ANY;
                }
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
                String reason = section.getString("reason", "");
                BanRule rule = new BanRule(key, itemKey, nbtMode, nbtValue, nbtPath, actions, recipes, purchase, reason);
                rules.put(key, rule);
                indexRule(rule);
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

    public BanRule match(ItemStack stack) {
        String key = ItemKeyUtils.getItemKey(stack);
        if (key == null) {
            return null;
        }
        RuleBucket bucket = rulesByItemKey.get(key.toLowerCase(Locale.ROOT));
        if (bucket == null) {
            return null;
        }
        String snbt = null;
        for (BanRule rule : bucket.nbtRules) {
            if (rule.getNbtMode() == BanRule.NbtMode.EXACT_SNBT || rule.getNbtMode() == BanRule.NbtMode.CONTAINS_SNBT) {
                if (snbt == null) {
                    snbt = NbtUtils.getSnbt(stack);
                }
                if (rule.getNbtMode() == BanRule.NbtMode.EXACT_SNBT && snbt.equals(rule.getNbtValue())) {
                    return rule;
                }
                if (rule.getNbtMode() == BanRule.NbtMode.CONTAINS_SNBT && snbt.contains(rule.getNbtValue())) {
                    return rule;
                }
            } else if (rule.getNbtMode() == BanRule.NbtMode.PATH_EQUALS) {
                String value = NbtUtils.getPathValue(stack, rule.getNbtPath());
                if (value.equals(rule.getNbtValue())) {
                    return rule;
                }
            }
        }
        if (!bucket.anyRules.isEmpty()) {
            return bucket.anyRules.get(0);
        }
        return null;
    }

    public List<RecipeOverride> getOverrides() {
        return Collections.unmodifiableList(overrides);
    }

    public boolean addOverrideAndPersist(RecipeOverride override) {
        overrides.add(override);
        if (config == null || configFile == null) {
            return false;
        }
        List<Map<String, Object>> data = overrides.stream()
            .map(RecipeOverride::toMap)
            .collect(Collectors.toList());
        config.set("recipeOverrides", data);
        try {
            config.save(configFile);
            return true;
        } catch (IOException ignored) {
            return false;
        }
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

    private void indexRule(BanRule rule) {
        String itemKey = rule.getItemKey() == null ? "" : rule.getItemKey().toLowerCase(Locale.ROOT);
        RuleBucket bucket = rulesByItemKey.computeIfAbsent(itemKey, ignored -> new RuleBucket());
        if (rule.getNbtMode() == BanRule.NbtMode.ANY) {
            bucket.anyRules.add(rule);
        } else {
            bucket.nbtRules.add(rule);
        }
    }

    private static final class RuleBucket {
        private final List<BanRule> anyRules = new ArrayList<>();
        private final List<BanRule> nbtRules = new ArrayList<>();
    }
}
