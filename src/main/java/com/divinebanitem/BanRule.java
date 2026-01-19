package com.divinebanitem;

import java.util.Collections;
import java.util.List;

public class BanRule {
    public enum NbtMode {
        ANY,
        EXACT_SNBT,
        CONTAINS_SNBT,
        PATH_EQUALS
    }

    public static class Actions {
        public boolean use;
        public boolean place;
        public boolean craft;
        public boolean smelt;
        public boolean pickup;
        public boolean drop;
    }

    public static class Recipes {
        public boolean removeBukkit;
        public boolean removeForge;
    }

    public static class PurchaseTier {
        private final String duration;
        private final double price;

        public PurchaseTier(String duration, double price) {
            this.duration = duration;
            this.price = price;
        }

        public String getDuration() {
            return duration;
        }

        public double getPrice() {
            return price;
        }
    }

    public static class PurchaseOptions {
        public boolean enabled;
        public double price;
        public List<PurchaseTier> durations = Collections.emptyList();
        public boolean allowUse = true;
        public boolean allowCraft = false;
    }

    private final String key;
    private final String itemKey;
    private final NbtMode nbtMode;
    private final String nbtValue;
    private final String nbtPath;
    private final Actions actions;
    private final Recipes recipes;
    private final PurchaseOptions purchase;

    public BanRule(String key, String itemKey, NbtMode nbtMode, String nbtValue, String nbtPath,
                   Actions actions, Recipes recipes, PurchaseOptions purchase) {
        this.key = key;
        this.itemKey = itemKey;
        this.nbtMode = nbtMode;
        this.nbtValue = nbtValue;
        this.nbtPath = nbtPath;
        this.actions = actions;
        this.recipes = recipes;
        this.purchase = purchase;
    }

    public String getKey() {
        return key;
    }

    public String getItemKey() {
        return itemKey;
    }

    public NbtMode getNbtMode() {
        return nbtMode;
    }

    public String getNbtValue() {
        return nbtValue;
    }

    public String getNbtPath() {
        return nbtPath;
    }

    public Actions getActions() {
        return actions;
    }

    public Recipes getRecipes() {
        return recipes;
    }

    public PurchaseOptions getPurchase() {
        return purchase;
    }
}
