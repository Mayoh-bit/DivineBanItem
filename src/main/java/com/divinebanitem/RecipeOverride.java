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
            addShapelessIngredient(shapelessRecipe, ingredient);
        }
        return shapelessRecipe;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("type", type.name());
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("key", ItemKeyUtils.getItemKey(result));
        resultMap.put("amount", result == null ? 1 : result.getAmount());
        String resultSnbt = result == null ? "" : NbtUtils.getSnbt(result);
        if (resultSnbt != null && !resultSnbt.isBlank()) {
            resultMap.put("snbt", resultSnbt);
        }
        data.put("result", resultMap);
        if (type == Type.SHAPED) {
            data.put("shape", new ArrayList<>(shape));
            Map<String, Object> ingredientData = new HashMap<>();
            for (Map.Entry<Character, Ingredient> entry : ingredientMap.entrySet()) {
                Ingredient ingredient = entry.getValue();
                Map<String, Object> map = new HashMap<>();
                map.put("key", ingredient.getKey());
                map.put("amount", ingredient.getAmount());
                if (ingredient.getSnbt() != null && !ingredient.getSnbt().isBlank()) {
                    map.put("snbt", ingredient.getSnbt());
                }
                ingredientData.put(entry.getKey().toString(), map);
            }
            data.put("ingredients", ingredientData);
        } else {
            List<Map<String, Object>> ingredientList = new ArrayList<>();
            for (Ingredient ingredient : ingredients) {
                Map<String, Object> map = new HashMap<>();
                map.put("key", ingredient.getKey());
                map.put("amount", ingredient.getAmount());
                if (ingredient.getSnbt() != null && !ingredient.getSnbt().isBlank()) {
                    map.put("snbt", ingredient.getSnbt());
                }
                ingredientList.add(map);
            }
            data.put("ingredients", ingredientList);
        }
        return data;
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
        String key = getString(raw, "key", "override");
        Type type = Type.valueOf(getString(raw, "type", "SHAPELESS").toUpperCase(Locale.ROOT));
        Map<?, ?> resultMap = getMap(raw, "result");
        String resultKey = getString(resultMap, "key", "minecraft:stone");
        int amount = getInt(resultMap, "amount", 1);
        ItemStack result = ItemKeyUtils.createItemStack(resultKey, amount);
        String snbt = getString(resultMap, "snbt", "");
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
        String key = getString(map, "key", "minecraft:stone");
        int amount = getInt(map, "amount", 1);
        String snbt = getString(map, "snbt", "");
        return new Ingredient(key, amount, snbt);
    }

    private void addShapelessIngredient(ShapelessRecipe recipe, Ingredient ingredient) {
        RecipeChoice choice = toChoice(ingredient);
        if (tryAddChoice(recipe, choice, ingredient.getAmount())) {
            return;
        }
        ItemStack item = ItemKeyUtils.createItemStack(ingredient.getKey(), 1);
        Material material = item == null ? Material.STONE : item.getType();
        for (int i = 0; i < ingredient.getAmount(); i++) {
            recipe.addIngredient(material);
        }
    }

    private static boolean tryAddChoice(ShapelessRecipe recipe, RecipeChoice choice, int amount) {
        try {
            java.lang.reflect.Method method = ShapelessRecipe.class.getMethod("addIngredient", RecipeChoice.class);
            for (int i = 0; i < amount; i++) {
                method.invoke(recipe, choice);
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String getString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map == null ? null : map.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private static int getInt(Map<?, ?> map, String key, int defaultValue) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static Map<?, ?> getMap(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Map<?, ?> valueMap) {
            return valueMap;
        }
        return new HashMap<>();
    }
}
