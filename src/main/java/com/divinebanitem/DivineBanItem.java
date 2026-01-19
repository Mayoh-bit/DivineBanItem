package com.divinebanitem;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class DivineBanItem extends JavaPlugin implements Listener {
    private BanRuleManager ruleManager;
    private LicenseStore licenseStore;
    private MessageService messages;
    private Economy economy;
    private String bypassPermission;
    private final Map<UUID, RecipeEditorSession> recipeEditors = new HashMap<>();
    private final Map<UUID, Inventory> adminInventories = new HashMap<>();
    private final Set<UUID> adminInventorySaving = new HashSet<>();
    private static final int ADMIN_SAVE_SLOT = 53;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        reloadPlugin();

        PluginCommand command = getCommand("dbi");
        if (command != null) {
            command.setExecutor(this::handleCommand);
            command.setTabCompleter(this::handleTabComplete);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void reloadPlugin() {
        reloadConfig();
        FileConfiguration config = getConfig();
        this.bypassPermission = config.getString("settings.bypass-permission", "divinebanitem.bypass.*");
        File messagesFile = new File(getDataFolder(), "messages.yml");
        FileConfiguration messageConfig = YamlConfiguration.loadConfiguration(messagesFile);
        this.messages = new MessageService(messageConfig);
        this.ruleManager = new BanRuleManager(config, new File(getDataFolder(), "config.yml"));
        this.ruleManager.load(config);
        this.licenseStore = new LicenseStore(getDataFolder());
        setupEconomy();
        removeConfiguredRecipes();
        applyRecipeOverrides();
    }

    private void saveResourceIfMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            economy = provider.getProvider();
        }
    }

    private void removeConfiguredRecipes() {
        if (!getConfig().getBoolean("settings.remove-bukkit-recipes-on-load", false)) {
            return;
        }
        boolean logSummary = getConfig().getBoolean("settings.log-removal-summary", true);
        int removed = 0;
        for (BanRule rule : ruleManager.getRules()) {
            if (!rule.getRecipes().removeBukkit) {
                continue;
            }
            ItemStack stack = ItemKeyUtils.createItemStack(rule.getItemKey(), 1);
            if (stack == null) {
                continue;
            }
            removed += ruleManager.removeBukkitRecipesFor(stack);
        }
        if (logSummary) {
            getLogger().info("Removed Bukkit recipes: " + removed);
        }
        for (BanRule rule : ruleManager.getRules()) {
            if (rule.getRecipes().removeForge) {
                getLogger().warning("Forge recipe removal is not implemented in this build: " + rule.getKey());
            }
        }
    }

    private void applyRecipeOverrides() {
        for (RecipeOverride override : ruleManager.getOverrides()) {
            ItemStack result = override.getResult();
            if (result != null) {
                ruleManager.removeBukkitRecipesFor(result);
            }
            Recipe recipe = override.toRecipe(this);
            if (recipe != null) {
                Bukkit.addRecipe(recipe);
            }
        }
    }

    private boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "banhand":
                return handleBanHand(sender);
            case "gui":
                return handleGui(sender);
            case "reload":
                return handleReload(sender);
            case "nbt":
                return handleNbt(sender);
            case "list":
                return handleList(sender);
            case "info":
                return handleInfo(sender, args);
            case "buy":
                return handleBuy(sender, args);
            case "grant":
                return handleGrant(sender, args);
            case "revoke":
                return handleRevoke(sender, args);
            case "recipe":
                return handleRecipe(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private List<String> handleTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("help", "banhand", "gui", "reload", "nbt", "list", "info", "buy", "grant",
                "revoke", "recipe");
            return subs.stream()
                .filter(entry -> entry.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recipe")) {
            List<String> subs = List.of("gui", "add");
            return subs.stream()
                .filter(entry -> entry.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("buy")
            || args[0].equalsIgnoreCase("grant") || args[0].equalsIgnoreCase("revoke"))) {
            if (args[0].equalsIgnoreCase("grant") || args[0].equalsIgnoreCase("revoke")) {
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            }
            return ruleManager.getRules().stream()
                .map(BanRule::getKey)
                .filter(entry -> entry.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("grant") || args[0].equalsIgnoreCase("revoke"))) {
            return ruleManager.getRules().stream()
                .map(BanRule::getKey)
                .filter(entry -> entry.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MessageService.colorize("&bDivineBanItem &7Commands:"));
        sender.sendMessage(MessageService.colorize("&e/dbi help &7- Show help"));
        sender.sendMessage(MessageService.colorize("&e/dbi banhand &7- Ban item in hand"));
        sender.sendMessage(MessageService.colorize("&e/dbi gui &7- Open admin GUI"));
        sender.sendMessage(MessageService.colorize("&e/dbi reload &7- Reload config"));
        sender.sendMessage(MessageService.colorize("&e/dbi nbt &7- Show held item NBT"));
        sender.sendMessage(MessageService.colorize("&e/dbi list &7- List rule keys"));
        sender.sendMessage(MessageService.colorize("&e/dbi info <key> &7- Show rule"));
        sender.sendMessage(MessageService.colorize("&e/dbi buy <key> [duration] &7- Buy license"));
        sender.sendMessage(MessageService.colorize("&e/dbi grant <player> <key> [duration] &7- Grant license"));
        sender.sendMessage(MessageService.colorize("&e/dbi revoke <player> <key> &7- Revoke license"));
        sender.sendMessage(MessageService.colorize("&e/dbi recipe gui &7- Open recipe editor"));
    }

    private boolean handleBanHand(CommandSender sender) {
        if (!sender.hasPermission("divinebanitem.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        Player player = (Player) sender;
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() == Material.AIR) {
            player.sendMessage(MessageService.colorize("&cHold an item to ban."));
            return true;
        }
        String savedKey = saveItemRule(stack);
        if (savedKey == null) {
            player.sendMessage(MessageService.colorize("&cFailed to save ban rule."));
            return true;
        }
        player.sendMessage(MessageService.colorize("&aSaved ban rule: &f" + savedKey));
        return true;
    }

    private boolean handleGui(CommandSender sender) {
        if (!sender.hasPermission("divinebanitem.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        Player player = (Player) sender;
        Inventory inventory = Bukkit.createInventory(player, 54, MessageService.colorize("&bDivineBanItem 管理"));
        inventory.setItem(ADMIN_SAVE_SLOT, createSaveButton());
        adminInventories.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("divinebanitem.reload")) {
            messages.send(sender, "no-permission");
            return true;
        }
        reloadPlugin();
        messages.send(sender, "reloaded");
        return true;
    }

    private boolean handleNbt(CommandSender sender) {
        if (!sender.hasPermission("divinebanitem.nbt")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        Player player = (Player) sender;
        ItemStack stack = player.getInventory().getItemInMainHand();
        String key = ItemKeyUtils.getItemKey(stack);
        String snbt = NbtUtils.getSnbt(stack);
        if (snbt.length() > 200) {
            messages.send(sender, "nbt-info", Map.of("key", key, "snbt", snbt.substring(0, 200) + "..."));
            getLogger().info("NBT for " + player.getName() + ": " + snbt);
            messages.send(sender, "nbt-console");
        } else {
            messages.send(sender, "nbt-info", Map.of("key", key, "snbt", snbt));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("divinebanitem.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        List<String> keys = ruleManager.getRules().stream().map(BanRule::getKey).sorted().collect(Collectors.toList());
        sender.sendMessage(MessageService.colorize("&eRules: &7" + String.join(", ", keys)));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(MessageService.colorize("&cUsage: /dbi info <key>"));
            return true;
        }
        Optional<BanRule> rule = ruleManager.getRule(args[1]);
        if (rule.isEmpty()) {
            messages.send(sender, "unknown-entry");
            return true;
        }
        BanRule entry = rule.get();
        sender.sendMessage(MessageService.colorize("&bKey: &f" + entry.getKey()));
        sender.sendMessage(MessageService.colorize("&7Item: &f" + entry.getItemKey()));
        sender.sendMessage(MessageService.colorize("&7NBT: &f" + entry.getNbtMode() + " " + entry.getNbtValue()));
        BanRule.Actions actions = entry.getActions();
        sender.sendMessage(MessageService.colorize("&7Actions: use=" + actions.use + ", place=" + actions.place
            + ", craft=" + actions.craft + ", smelt=" + actions.smelt + ", pickup=" + actions.pickup
            + ", drop=" + actions.drop));
        return true;
    }

    private boolean handleBuy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.buy")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(MessageService.colorize("&cUsage: /dbi buy <key> [duration]"));
            return true;
        }
        Player player = (Player) sender;
        Optional<BanRule> rule = ruleManager.getRule(args[1]);
        if (rule.isEmpty()) {
            messages.send(sender, "unknown-entry");
            return true;
        }
        BanRule entry = rule.get();
        if (!entry.getPurchase().enabled) {
            messages.send(sender, "unknown-entry");
            return true;
        }
        if (economy == null) {
            messages.send(sender, "vault-missing");
            return true;
        }
        double price = entry.getPurchase().price;
        long expiresAt = -1L;
        String durationLabel = "permanent";
        if (args.length >= 3) {
            String duration = args[2];
            BanRule.PurchaseTier tier = entry.getPurchase().durations.stream()
                .filter(option -> option.getDuration().equalsIgnoreCase(duration))
                .findFirst()
                .orElse(null);
            if (tier != null) {
                price = tier.getPrice();
                long durationMillis = DurationParser.parseToMillis(tier.getDuration());
                expiresAt = durationMillis <= 0L ? -1L : System.currentTimeMillis() + durationMillis;
                durationLabel = tier.getDuration();
            }
        }
        if (economy.getBalance(player) < price) {
            messages.send(sender, "insufficient-funds");
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            messages.send(sender, "insufficient-funds");
            return true;
        }
        licenseStore.grant(player.getUniqueId(), entry.getKey(), expiresAt);
        messages.send(sender, "license-purchased", Map.of("key", entry.getKey(), "duration", durationLabel));
        return true;
    }

    private boolean handleGrant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageService.colorize("&cUsage: /dbi grant <player> <key> [duration]"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageService.colorize("&cPlayer not found."));
            return true;
        }
        long expiresAt = -1L;
        if (args.length >= 4) {
            long durationMillis = DurationParser.parseToMillis(args[3]);
            expiresAt = durationMillis <= 0L ? -1L : System.currentTimeMillis() + durationMillis;
        }
        licenseStore.grant(target.getUniqueId(), args[2], expiresAt);
        messages.send(sender, "license-granted", Map.of("key", args[2]));
        return true;
    }

    private boolean handleRevoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageService.colorize("&cUsage: /dbi revoke <player> <key>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageService.colorize("&cPlayer not found."));
            return true;
        }
        licenseStore.revoke(target.getUniqueId(), args[2]);
        messages.send(sender, "license-revoked", Map.of("key", args[2]));
        return true;
    }

    private boolean handleRecipe(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "player-only");
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "gui";
        if (!action.equals("gui") && !action.equals("add")) {
            sender.sendMessage(MessageService.colorize("&cUsage: /dbi recipe gui"));
            return true;
        }
        Player player = (Player) sender;
        RecipeEditorSession session = new RecipeEditorSession(player.getUniqueId());
        recipeEditors.put(player.getUniqueId(), session);
        player.openInventory(session.getInventory());
        return true;
    }

    private ItemStack createSaveButton() {
        ItemStack button = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageService.colorize("&a保存封禁"));
            meta.setLore(List.of(
                MessageService.colorize("&7保存当前物品列表"),
                MessageService.colorize("&7并写入配置")
            ));
            button.setItemMeta(meta);
        }
        return button;
    }

    private boolean canBypass(Player player) {
        return player.hasPermission(bypassPermission);
    }

    private boolean hasLicense(Player player, BanRule rule, boolean isCraft) {
        if (!rule.getPurchase().enabled) {
            return false;
        }
        if (!licenseStore.hasValidLicense(player.getUniqueId(), rule.getKey())) {
            return false;
        }
        if (isCraft) {
            return rule.getPurchase().allowCraft;
        }
        return rule.getPurchase().allowUse;
    }

    private boolean shouldBlock(Player player, ItemStack stack, ActionContext context) {
        if (player == null || stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        if (canBypass(player)) {
            return false;
        }
        BanRule rule = ruleManager.match(stack);
        if (rule == null) {
            return false;
        }
        if (context.isCraft) {
            if (hasLicense(player, rule, true)) {
                return false;
            }
            return rule.getActions().craft;
        }
        if (context.isSmelt) {
            return rule.getActions().smelt;
        }
        if (context.isUse) {
            if (hasLicense(player, rule, false)) {
                return false;
            }
            return rule.getActions().use;
        }
        if (context.isPlace) {
            return rule.getActions().place;
        }
        if (context.isPickup) {
            return rule.getActions().pickup;
        }
        if (context.isDrop) {
            return rule.getActions().drop;
        }
        return false;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getView().getTopInventory().equals(adminInventory)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == ADMIN_SAVE_SLOT) {
            event.setCancelled(true);
            saveAdminInventory(player, adminInventory);
            return;
        }
        if (slot < adminInventory.getSize() && adminInventory.getItem(ADMIN_SAVE_SLOT) != null
            && event.getCurrentItem() != null && event.getCurrentItem().isSimilar(adminInventory.getItem(ADMIN_SAVE_SLOT))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getView().getTopInventory().equals(adminInventory)) {
            return;
        }
        if (event.getRawSlots().contains(ADMIN_SAVE_SLOT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getInventory().equals(adminInventory)) {
            return;
        }
        if (adminInventorySaving.remove(player.getUniqueId())) {
            adminInventories.remove(player.getUniqueId());
            return;
        }
        returnItems(player, adminInventory);
        adminInventories.remove(player.getUniqueId());
    }

    private void saveAdminInventory(Player player, Inventory inventory) {
        FileConfiguration config = getConfig();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null) {
            entries = config.createSection("entries");
        }
        int saved = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == ADMIN_SAVE_SLOT) {
                continue;
            }
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            String key = saveItemRule(item, entries);
            if (key != null) {
                saved++;
            }
        }
        if (saved == 0) {
            player.sendMessage(MessageService.colorize("&cNo items to save."));
            return;
        }
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
            player.sendMessage(MessageService.colorize("&cFailed to save ban rules."));
            return;
        }
        reloadPlugin();
        returnItems(player, inventory);
        adminInventorySaving.add(player.getUniqueId());
        player.sendMessage(MessageService.colorize("&aSaved " + saved + " ban entries."));
        player.closeInventory();
    }

    private void returnItems(Player player, Inventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == ADMIN_SAVE_SLOT) {
                continue;
            }
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            inventory.setItem(i, null);
        }
    }

    private String saveItemRule(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        FileConfiguration config = getConfig();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null) {
            entries = config.createSection("entries");
        }
        String key = saveItemRule(item, entries);
        if (key == null) {
            return null;
        }
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
            return null;
        }
        reloadPlugin();
        return key;
    }

    private String saveItemRule(ItemStack item, ConfigurationSection entries) {
        String itemKey = ItemKeyUtils.getItemKey(item);
        if (itemKey == null || itemKey.isBlank()) {
            return null;
        }
        String baseKey = itemKey.replace(":", "_").toLowerCase(Locale.ROOT);
        String key = baseKey;
        int index = 1;
        while (entries.contains(key)) {
            key = baseKey + "_" + index++;
        }
        ConfigurationSection section = entries.createSection(key);
        ConfigurationSection itemSection = section.createSection("item");
        itemSection.set("key", itemKey);
        String snbt = NbtUtils.getSnbt(item);
        boolean useNbt = snbt != null && !snbt.isBlank() && !snbt.equals("{}");
        itemSection.set("nbtMode", useNbt ? "EXACT_SNBT" : "ANY");
        if (useNbt) {
            itemSection.set("nbtValue", snbt);
        }
        ConfigurationSection actions = section.createSection("actions");
        actions.set("use", true);
        actions.set("place", true);
        actions.set("craft", true);
        actions.set("smelt", true);
        actions.set("pickup", true);
        actions.set("drop", true);
        ConfigurationSection recipes = section.createSection("recipes");
        recipes.set("removeBukkit", false);
        recipes.set("removeForge", false);
        ConfigurationSection purchase = section.createSection("purchase");
        purchase.set("enabled", false);
        return key;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack stack = event.getItem();
            if (shouldBlock(player, stack, ActionContext.use())) {
                messages.send(player, "blocked-use");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemInHand();
        if (shouldBlock(player, stack, ActionContext.place())) {
            messages.send(player, "blocked-place");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ItemStack result = event.getRecipe().getResult();
        if (shouldBlock(player, result, ActionContext.craft())) {
            messages.send(player, "blocked-craft");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSmelt(FurnaceSmeltEvent event) {
        ItemStack result = event.getResult();
        if (result == null) {
            return;
        }
        BanRule rule = ruleManager.match(result);
        if (rule != null && rule.getActions().smelt) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        ItemStack stack = event.getItem().getItemStack();
        if (shouldBlock(player, stack, ActionContext.pickup())) {
            messages.send(player, "blocked-pickup");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemDrop().getItemStack();
        if (shouldBlock(player, stack, ActionContext.drop())) {
            messages.send(player, "blocked-drop");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRecipeEditorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        RecipeEditorSession session = recipeEditors.get(player.getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.getInventory())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= session.getInventory().getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        if (session.isControlSlot(slot)) {
            event.setCancelled(true);
            if (slot == RecipeEditorSession.MODE_SLOT) {
                session.toggleType();
                player.updateInventory();
            } else if (slot == RecipeEditorSession.SAVE_SLOT) {
                handleRecipeSave(player, session);
            } else if (slot == RecipeEditorSession.CLOSE_SLOT) {
                player.closeInventory();
            }
            return;
        }
        if (!RecipeEditorSession.INPUT_SLOTS.contains(slot) && slot != RecipeEditorSession.OUTPUT_SLOT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRecipeEditorDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        RecipeEditorSession session = recipeEditors.get(player.getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.getInventory())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= session.getInventory().getSize()) {
                continue;
            }
            if (session.isControlSlot(slot)
                || (!RecipeEditorSession.INPUT_SLOTS.contains(slot) && slot != RecipeEditorSession.OUTPUT_SLOT)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onRecipeEditorClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        RecipeEditorSession session = recipeEditors.get(player.getUniqueId());
        if (session != null && event.getInventory().equals(session.getInventory())) {
            recipeEditors.remove(player.getUniqueId());
        }
    }

    private void handleRecipeSave(Player player, RecipeEditorSession session) {
        ItemStack result = session.getInventory().getItem(RecipeEditorSession.OUTPUT_SLOT);
        if (result == null || result.getType() == Material.AIR) {
            player.sendMessage(MessageService.colorize("&cPlace a result item in the output slot."));
            return;
        }
        List<Integer> inputSlots = RecipeEditorSession.INPUT_SLOTS.stream().sorted().collect(Collectors.toList());
        List<ItemStack> inputs = inputSlots.stream()
            .map(slot -> session.getInventory().getItem(slot))
            .collect(Collectors.toList());
        boolean hasIngredient = inputs.stream().anyMatch(item -> item != null && item.getType() != Material.AIR);
        if (!hasIngredient) {
            player.sendMessage(MessageService.colorize("&cPlace at least one ingredient in the grid."));
            return;
        }
        RecipeOverride override = buildOverride(session, result, inputs);
        boolean saved = ruleManager.addOverrideAndPersist(override);
        if (!saved) {
            player.sendMessage(MessageService.colorize("&cFailed to save recipe override."));
            return;
        }
        applyRecipeOverrides();
        player.sendMessage(MessageService.colorize("&aRecipe override saved: &f" + override.getKey()));
        player.closeInventory();
    }

    private RecipeOverride buildOverride(RecipeEditorSession session, ItemStack result, List<ItemStack> inputs) {
        String baseKey = ItemKeyUtils.getItemKey(result).replace(":", "_");
        String key = baseKey + "_" + System.currentTimeMillis();
        ItemStack resultClone = result.clone();
        RecipeOverride.Type type = session.getType();
        if (type == RecipeOverride.Type.SHAPELESS) {
            List<RecipeOverride.Ingredient> ingredients = inputs.stream()
                .filter(item -> item != null && item.getType() != Material.AIR)
                .map(this::toIngredient)
                .collect(Collectors.toList());
            return new RecipeOverride(key, type, resultClone, List.of(), ingredients, Map.of());
        }
        Map<String, Character> assignedSymbols = new java.util.LinkedHashMap<>();
        Map<Character, RecipeOverride.Ingredient> ingredientMap = new java.util.LinkedHashMap<>();
        List<String> shape = new java.util.ArrayList<>();
        char nextSymbol = 'A';
        for (int row = 0; row < 3; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 3; col++) {
                ItemStack item = inputs.get(row * 3 + col);
                if (item == null || item.getType() == Material.AIR) {
                    line.append(' ');
                    continue;
                }
                String signature = buildSignature(item);
                Character symbol = assignedSymbols.get(signature);
                if (symbol == null) {
                    symbol = nextSymbol++;
                    assignedSymbols.put(signature, symbol);
                    ingredientMap.put(symbol, toIngredient(item));
                }
                line.append(symbol);
            }
            shape.add(line.toString());
        }
        return new RecipeOverride(key, type, resultClone, shape, List.of(), ingredientMap);
    }

    private String buildSignature(ItemStack item) {
        String snbt = NbtUtils.getSnbt(item);
        return ItemKeyUtils.getItemKey(item) + "|" + item.getAmount() + "|" + snbt;
    }

    private RecipeOverride.Ingredient toIngredient(ItemStack item) {
        String key = ItemKeyUtils.getItemKey(item);
        String snbt = NbtUtils.getSnbt(item);
        return new RecipeOverride.Ingredient(key, item.getAmount(), snbt);
    }

    private static final class ActionContext {
        private final boolean isUse;
        private final boolean isPlace;
        private final boolean isCraft;
        private final boolean isSmelt;
        private final boolean isPickup;
        private final boolean isDrop;

        private ActionContext(boolean isUse, boolean isPlace, boolean isCraft, boolean isSmelt,
                              boolean isPickup, boolean isDrop) {
            this.isUse = isUse;
            this.isPlace = isPlace;
            this.isCraft = isCraft;
            this.isSmelt = isSmelt;
            this.isPickup = isPickup;
            this.isDrop = isDrop;
        }

        public static ActionContext use() {
            return new ActionContext(true, false, false, false, false, false);
        }

        public static ActionContext place() {
            return new ActionContext(false, true, false, false, false, false);
        }

        public static ActionContext craft() {
            return new ActionContext(false, false, true, false, false, false);
        }

        public static ActionContext smelt() {
            return new ActionContext(false, false, false, true, false, false);
        }

        public static ActionContext pickup() {
            return new ActionContext(false, false, false, false, true, false);
        }

        public static ActionContext drop() {
            return new ActionContext(false, false, false, false, false, true);
        }
    }
}
