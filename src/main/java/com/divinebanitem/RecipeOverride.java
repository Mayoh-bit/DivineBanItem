package com.divinebanitem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

public class RecipeOverride {
    public enum Type {
        SHAPED,
        SHAPELESS
    }

    public static class Ingredient {
        private final String key;
        private final int amount;
        private final String snbt;

        public Ingredient(String key, int amount, String snbt) {
            this.key = key;
            this.amount = amount;
            this.snbt = snbt;
        }

        public String getKey() {
            return key;
        }

        public int getAmount() {
            return amount;
        }

        public String getSnbt() {
            return snbt;
        }
    }

    private final String key;
    private final Type type;
    private final ItemStack result;
    private final List<String> shape;
    private final List<Ingredient> ingredients;
    private final Map<Character, Ingredient> ingredientMap;

    public RecipeOverride(String key, Type type, ItemStack result, List<String> shape,
                          List<Ingredient> ingredients, Map<Character, Ingredient> ingredientMap) {
        this.key = key;
        this.type = type;
        this.result = result;
        this.shape = shape;
        this.ingredients = ingredients;
        this.ingredientMap = ingredientMap;
    }

    public String getKey() {
        return key;
    }

    public Type getType() {
        return type;
    }

    public ItemStack getResult() {
        return result;
    }

    public Recipe toRecipe(JavaPlugin plugin) {
        NamespacedKey namespacedKey = new NamespacedKey(plugin, "override_" + key.toLowerCase(Locale.ROOT));
        if (type == Type.SHAPED) {
            ShapedRecipe shapedRecipe = new ShapedRecipe(namespacedKey, result);
            shapedRecipe.shape(shape.toArray(new String[0]));
            for (Map.Entry<Character, Ingredient> entry : ingredientMap.entrySet()) {
                shapedRecipe.setIngredient(entry.getKey(), toChoice(entry.getValue()));
            }
            return shapedRecipe;
        }
        ShapelessRecipe shapelessRecipe = new ShapelessRecipe(namespacedKey, result);
        for (Ingredient ingredient : ingredients) {
            shapelessRecipe.addIngredient(ingredient.getAmount(), toChoice(ingredient));
        }
        return shapelessRecipe;
    }

    private RecipeChoice toChoice(Ingredient ingredient) {
        ItemStack item = ItemKeyUtils.createItemStack(ingredient.getKey(), ingredient.getAmount());
        if (item == null) {
            return new RecipeChoice.MaterialChoice(Material.STONE);
        }
        if (ingredient.getSnbt() != null && !ingredient.getSnbt().isBlank()) {
            item = NbtUtils.applySnbt(item, ingredient.getSnbt());
            return new RecipeChoice.ExactChoice(item);
        }
        return new RecipeChoice.ExactChoice(item);
    }

    public static RecipeOverride from(Map<?, ?> raw) {
        String key = raw.getOrDefault("key", "override").toString();
        Type type = Type.valueOf(raw.getOrDefault("type", "SHAPELESS").toString().toUpperCase(Locale.ROOT));
        Map<?, ?> resultMap = (Map<?, ?>) raw.getOrDefault("result", new HashMap<>());
        String resultKey = resultMap.getOrDefault("key", "minecraft:stone").toString();
        int amount = Integer.parseInt(resultMap.getOrDefault("amount", 1).toString());
        ItemStack result = ItemKeyUtils.createItemStack(resultKey, amount);
        String snbt = resultMap.getOrDefault("snbt", "").toString();
        if (result != null && !snbt.isBlank()) {
            result = NbtUtils.applySnbt(result, snbt);
        }

        if (type == Type.SHAPED) {
            List<String> shape = new ArrayList<>();
            Object shapeObj = raw.get("shape");
            if (shapeObj instanceof List<?>) {
                for (Object line : (List<?>) shapeObj) {
                    shape.add(line.toString());
                }
            }
            Map<Character, Ingredient> map = new HashMap<>();
            Object ingredientObj = raw.get("ingredients");
            if (ingredientObj instanceof Map<?, ?>) {
                Map<?, ?> ingredientMap = (Map<?, ?>) ingredientObj;
                for (Map.Entry<?, ?> entry : ingredientMap.entrySet()) {
                    char symbol = entry.getKey().toString().charAt(0);
                    Map<?, ?> data = (Map<?, ?>) entry.getValue();
                    map.put(symbol, ingredientFromMap(data));
                }
            }
            return new RecipeOverride(key, type, result, shape, new ArrayList<>(), map);
        }

        List<Ingredient> ingredients = new ArrayList<>();
        Object ingredientObj = raw.get("ingredients");
        if (ingredientObj instanceof List<?>) {
            for (Object entry : (List<?>) ingredientObj) {
                if (entry instanceof Map<?, ?>) {
                    ingredients.add(ingredientFromMap((Map<?, ?>) entry));
                }
            }
        }
        return new RecipeOverride(key, type, result, new ArrayList<>(), ingredients, new HashMap<>());
    }

    private static Ingredient ingredientFromMap(Map<?, ?> map) {
        String key = map.getOrDefault("key", "minecraft:stone").toString();
        int amount = Integer.parseInt(map.getOrDefault("amount", 1).toString());
        String snbt = map.getOrDefault("snbt", "").toString();
        return new Ingredient(key, amount, snbt);
    }
}
