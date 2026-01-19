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
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
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
    private final Map<UUID, Map<Integer, String>> adminRuleSlots = new HashMap<>();
    private final Map<UUID, Inventory> listInventories = new HashMap<>();
    private final Map<UUID, Integer> listPages = new HashMap<>();
    private final Map<UUID, BanHandWizardSession> banHandSessions = new HashMap<>();
    private final Map<UUID, Map<String, Long>> messageCooldowns = new HashMap<>();
    private final Set<UUID> adminInventorySaving = new HashSet<>();
    private static final int ADMIN_SAVE_SLOT = 53;
    private static final int LIST_PAGE_SIZE = 45;
    private static final int LIST_PREV_SLOT = 45;
    private static final int LIST_INFO_SLOT = 49;
    private static final int LIST_NEXT_SLOT = 53;
    private static final String LIST_TITLE_PREFIX = "&3封禁物品列表";

    private static final class ListInventoryHolder implements org.bukkit.inventory.InventoryHolder {
        private ListInventoryHolder() {
        }

        @Override
        public Inventory getInventory() {
            return null;
        }

    }

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
            getLogger().info("已移除原版配方数量: " + removed);
        }
        for (BanRule rule : ruleManager.getRules()) {
            if (rule.getRecipes().removeForge) {
                getLogger().warning("当前版本未实现 Forge 配方移除: " + rule.getKey());
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
            case "removehand":
                return handleRemoveHand(sender);
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
            List<String> subs = List.of("help", "banhand", "removehand", "gui", "reload", "nbt", "list", "info",
                "buy", "grant", "revoke", "recipe");
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
        sender.sendMessage(MessageService.colorize("&3DivineBanItem &7指令列表:"));
        sender.sendMessage(MessageService.colorize("&b/dbi help &7- 查看帮助"));
        sender.sendMessage(MessageService.colorize("&b/dbi banhand &7- 引导封禁手持物品"));
        sender.sendMessage(MessageService.colorize("&b/dbi removehand &7- 解除手持物品封禁"));
        sender.sendMessage(MessageService.colorize("&b/dbi gui &7- 打开管理物品箱"));
        sender.sendMessage(MessageService.colorize("&b/dbi reload &7- 重载配置"));
        sender.sendMessage(MessageService.colorize("&b/dbi nbt &7- 查看手持物品 NBT"));
        sender.sendMessage(MessageService.colorize("&b/dbi list &7- GUI 查看封禁清单"));
        sender.sendMessage(MessageService.colorize("&b/dbi info <key> &7- 查看封禁条目详情"));
        sender.sendMessage(MessageService.colorize("&b/dbi buy <key> [duration] &7- 购买许可"));
        sender.sendMessage(MessageService.colorize("&b/dbi grant <player> <key> [duration] &7- 授予许可"));
        sender.sendMessage(MessageService.colorize("&b/dbi revoke <player> <key> &7- 撤销许可"));
        sender.sendMessage(MessageService.colorize("&b/dbi recipe gui &7- 打开配方编辑器"));
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
        if (banHandSessions.containsKey(player.getUniqueId())) {
            messages.send(player, "banhand-in-progress");
            return true;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() == Material.AIR) {
            messages.send(player, "banhand-hold-item");
            return true;
        }
        BanHandWizardSession session = new BanHandWizardSession(stack.clone());
        banHandSessions.put(player.getUniqueId(), session);
        messages.send(player, "banhand-start");
        promptBanHandStep(player, session);
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
        openAdminInventory(player);
        return true;
    }

    private boolean handleRemoveHand(CommandSender sender) {
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
            messages.send(player, "banhand-hold-item");
            return true;
        }
        BanRule rule = ruleManager.match(stack);
        if (rule == null) {
            messages.send(player, "removehand-no-match");
            return true;
        }
        if (!removeRule(rule.getKey())) {
            messages.send(player, "removehand-failed");
            return true;
        }
        messages.send(player, "removehand-removed", Map.of("key", rule.getKey()));
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
        if (!(sender instanceof Player)) {
            List<String> keys = ruleManager.getRules().stream()
                .map(BanRule::getKey)
                .sorted()
                .collect(Collectors.toList());
            sender.sendMessage(MessageService.colorize("&7封禁条目: &f" + String.join(", ", keys)));
            return true;
        }
        Player player = (Player) sender;
        openListInventory(player, 0);
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("divinebanitem.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(MessageService.colorize("&c用法: /dbi info <key>"));
            return true;
        }
        Optional<BanRule> rule = ruleManager.getRule(args[1]);
        if (rule.isEmpty()) {
            messages.send(sender, "unknown-entry");
            return true;
        }
        BanRule entry = rule.get();
        sender.sendMessage(MessageService.colorize("&3键值: &f" + entry.getKey()));
        sender.sendMessage(MessageService.colorize("&7物品: &f" + entry.getItemKey()));
        sender.sendMessage(MessageService.colorize("&7NBT 规则: &f" + entry.getNbtMode() + " " + entry.getNbtValue()));
        BanRule.Actions actions = entry.getActions();
        sender.sendMessage(MessageService.colorize("&7封禁类型: &f" + formatActionList(actions)));
        String reason = entry.getReason() == null || entry.getReason().isBlank() ? "未填写" : entry.getReason();
        sender.sendMessage(MessageService.colorize("&7封禁原因: &f" + reason));
        BanRule.PurchaseOptions purchase = entry.getPurchase();
        if (purchase.enabled) {
            sender.sendMessage(MessageService.colorize("&7许可价格: &f永久 " + formatPrice(purchase.price)));
            if (!purchase.durations.isEmpty()) {
                sender.sendMessage(MessageService.colorize("&7临时价格: &f" + formatDurationPrices(purchase.durations)));
            }
        } else {
            sender.sendMessage(MessageService.colorize("&7许可价格: &8不可购买"));
        }
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
            sender.sendMessage(MessageService.colorize("&c用法: /dbi buy <key> [duration]"));
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
        String durationLabel = "永久";
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
            sender.sendMessage(MessageService.colorize("&c用法: /dbi grant <player> <key> [duration]"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageService.colorize("&c未找到该玩家。"));
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
            sender.sendMessage(MessageService.colorize("&c用法: /dbi revoke <player> <key>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageService.colorize("&c未找到该玩家。"));
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
            sender.sendMessage(MessageService.colorize("&c用法: /dbi recipe gui"));
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
                MessageService.colorize("&8保存当前物品列表"),
                MessageService.colorize("&8并写入配置文件")
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
        if (isListInventory(event.getView().getTopInventory(), player)) {
            handleListInventoryClick(event, player);
            return;
        }
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getView().getTopInventory().equals(adminInventory)) {
            return;
        }
        int slot = event.getRawSlot();
        Map<Integer, String> ruleSlots = adminRuleSlots.getOrDefault(player.getUniqueId(), Map.of());
        if (slot == ADMIN_SAVE_SLOT) {
            event.setCancelled(true);
            saveAdminInventory(player, adminInventory, ruleSlots);
            return;
        }
        if (slot < adminInventory.getSize() && ruleSlots.containsKey(slot)) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                String key = ruleSlots.get(slot);
                if (removeRule(key)) {
                    messages.send(player, "admin-remove-success", Map.of("key", key));
                    openAdminInventory(player);
                } else {
                    messages.send(player, "admin-remove-failed", Map.of("key", key));
                }
            }
            return;
        }
        if (slot < adminInventory.getSize() && adminInventory.getItem(ADMIN_SAVE_SLOT) != null
            && event.getCurrentItem() != null && event.getCurrentItem().isSimilar(adminInventory.getItem(ADMIN_SAVE_SLOT))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (isListInventory(event.getView().getTopInventory(), player)) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            player.updateInventory();
            return;
        }
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory != null && event.getView().getTopInventory().equals(adminInventory)) {
            int slot = event.getSlot();
            Map<Integer, String> ruleSlots = adminRuleSlots.getOrDefault(player.getUniqueId(), Map.of());
            if (slot >= 0 && slot < adminInventory.getSize() && ruleSlots.containsKey(slot)) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (isListInventory(event.getView().getTopInventory(), player)) {
            event.setCancelled(true);
            player.updateInventory();
            return;
        }
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getView().getTopInventory().equals(adminInventory)) {
            return;
        }
        Map<Integer, String> ruleSlots = adminRuleSlots.getOrDefault(player.getUniqueId(), Map.of());
        if (event.getRawSlots().contains(ADMIN_SAVE_SLOT)
            || event.getRawSlots().stream().anyMatch(ruleSlots::containsKey)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        if (isListInventory(event.getInventory(), player)) {
            listInventories.remove(player.getUniqueId());
            listPages.remove(player.getUniqueId());
            return;
        }
        Inventory adminInventory = adminInventories.get(player.getUniqueId());
        if (adminInventory == null || !event.getInventory().equals(adminInventory)) {
            return;
        }
        if (adminInventorySaving.remove(player.getUniqueId())) {
            adminInventories.remove(player.getUniqueId());
            adminRuleSlots.remove(player.getUniqueId());
            return;
        }
        returnItems(player, adminInventory, adminRuleSlots.getOrDefault(player.getUniqueId(), Map.of()));
        adminInventories.remove(player.getUniqueId());
        adminRuleSlots.remove(player.getUniqueId());
    }

    private void saveAdminInventory(Player player, Inventory inventory, Map<Integer, String> ruleSlots) {
        FileConfiguration config = getConfig();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null) {
            entries = config.createSection("entries");
        }
        int saved = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == ADMIN_SAVE_SLOT || ruleSlots.containsKey(i)) {
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
            player.sendMessage(MessageService.colorize("&e没有可保存的物品。"));
            return;
        }
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
            player.sendMessage(MessageService.colorize("&c保存封禁配置失败。"));
            return;
        }
        reloadPlugin();
        returnItems(player, inventory, ruleSlots);
        adminInventorySaving.add(player.getUniqueId());
        player.sendMessage(MessageService.colorize("&a已保存 " + saved + " 条封禁配置。"));
        player.closeInventory();
    }

    private void returnItems(Player player, Inventory inventory, Map<Integer, String> ruleSlots) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == ADMIN_SAVE_SLOT || ruleSlots.containsKey(i)) {
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
        return saveItemRule(item, entries, null);
    }

    private String saveItemRule(ItemStack item, ConfigurationSection entries, BanHandWizardSession wizard) {
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
        if (wizard == null) {
            actions.set("use", true);
            actions.set("place", true);
            actions.set("craft", true);
            actions.set("smelt", true);
            actions.set("pickup", true);
            actions.set("drop", true);
        } else {
            actions.set("use", wizard.actions.use);
            actions.set("place", wizard.actions.place);
            actions.set("craft", wizard.actions.craft);
            actions.set("smelt", wizard.actions.smelt);
            actions.set("pickup", wizard.actions.pickup);
            actions.set("drop", wizard.actions.drop);
        }
        ConfigurationSection recipes = section.createSection("recipes");
        if (wizard == null) {
            recipes.set("removeBukkit", false);
            recipes.set("removeForge", false);
        } else {
            recipes.set("removeBukkit", wizard.recipes.removeBukkit);
            recipes.set("removeForge", wizard.recipes.removeForge);
        }
        ConfigurationSection purchase = section.createSection("purchase");
        if (wizard == null) {
            purchase.set("enabled", false);
        } else {
            purchase.set("enabled", wizard.purchase.enabled);
            purchase.set("price", wizard.purchase.price);
            if (!wizard.purchase.durations.isEmpty()) {
                List<Map<String, Object>> durationList = new java.util.ArrayList<>();
                for (BanRule.PurchaseTier tier : wizard.purchase.durations) {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("duration", tier.getDuration());
                    entry.put("price", tier.getPrice());
                    durationList.add(entry);
                }
                purchase.set("durations", durationList);
            }
            ConfigurationSection licenseEffect = purchase.createSection("licenseEffect");
            licenseEffect.set("allowUse", wizard.purchase.allowUse);
            licenseEffect.set("allowCraft", wizard.purchase.allowCraft);
            if (wizard.reason != null && !wizard.reason.isBlank()) {
                section.set("reason", wizard.reason);
            }
        }
        return key;
    }

    @EventHandler
    public void onBanHandChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        BanHandWizardSession session = banHandSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage().trim();
        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("取消")) {
            banHandSessions.remove(player.getUniqueId());
            messages.send(player, "banhand-cancelled");
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> handleBanHandInput(player, session, message));
    }

    private void promptBanHandStep(Player player, BanHandWizardSession session) {
        switch (session.step) {
            case USE:
                messages.send(player, "banhand-step-use");
                break;
            case PLACE:
                messages.send(player, "banhand-step-place");
                break;
            case CRAFT:
                messages.send(player, "banhand-step-craft");
                break;
            case SMELT:
                messages.send(player, "banhand-step-smelt");
                break;
            case PICKUP:
                messages.send(player, "banhand-step-pickup");
                break;
            case DROP:
                messages.send(player, "banhand-step-drop");
                break;
            case REMOVE_BUKKIT:
                messages.send(player, "banhand-step-remove-bukkit");
                break;
            case REMOVE_FORGE:
                messages.send(player, "banhand-step-remove-forge");
                break;
            case PURCHASE_ENABLED:
                messages.send(player, "banhand-step-purchase-enabled");
                break;
            case PURCHASE_PRICE:
                messages.send(player, "banhand-step-purchase-price");
                break;
            case PURCHASE_DURATIONS:
                messages.send(player, "banhand-step-purchase-durations");
                break;
            case REASON:
                messages.send(player, "banhand-step-reason");
                break;
            default:
                break;
        }
    }

    private void handleBanHandInput(Player player, BanHandWizardSession session, String input) {
        switch (session.step) {
            case USE:
                Boolean use = parseBoolean(input);
                if (use == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.use = use;
                session.step = BanHandWizardStep.PLACE;
                promptBanHandStep(player, session);
                return;
            case PLACE:
                Boolean place = parseBoolean(input);
                if (place == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.place = place;
                session.step = BanHandWizardStep.CRAFT;
                promptBanHandStep(player, session);
                return;
            case CRAFT:
                Boolean craft = parseBoolean(input);
                if (craft == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.craft = craft;
                session.step = BanHandWizardStep.SMELT;
                promptBanHandStep(player, session);
                return;
            case SMELT:
                Boolean smelt = parseBoolean(input);
                if (smelt == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.smelt = smelt;
                session.step = BanHandWizardStep.PICKUP;
                promptBanHandStep(player, session);
                return;
            case PICKUP:
                Boolean pickup = parseBoolean(input);
                if (pickup == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.pickup = pickup;
                session.step = BanHandWizardStep.DROP;
                promptBanHandStep(player, session);
                return;
            case DROP:
                Boolean drop = parseBoolean(input);
                if (drop == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.actions.drop = drop;
                session.step = BanHandWizardStep.REMOVE_BUKKIT;
                promptBanHandStep(player, session);
                return;
            case REMOVE_BUKKIT:
                Boolean removeBukkit = parseBoolean(input);
                if (removeBukkit == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.recipes.removeBukkit = removeBukkit;
                session.step = BanHandWizardStep.REMOVE_FORGE;
                promptBanHandStep(player, session);
                return;
            case REMOVE_FORGE:
                Boolean removeForge = parseBoolean(input);
                if (removeForge == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.recipes.removeForge = removeForge;
                session.step = BanHandWizardStep.PURCHASE_ENABLED;
                promptBanHandStep(player, session);
                return;
            case PURCHASE_ENABLED:
                Boolean purchaseEnabled = parseBoolean(input);
                if (purchaseEnabled == null) {
                    messages.send(player, "banhand-invalid-boolean");
                    promptBanHandStep(player, session);
                    return;
                }
                session.purchase.enabled = purchaseEnabled;
                session.purchase.allowUse = session.actions.use;
                session.purchase.allowCraft = session.actions.craft;
                if (purchaseEnabled) {
                    session.step = BanHandWizardStep.PURCHASE_PRICE;
                } else {
                    session.step = BanHandWizardStep.REASON;
                }
                promptBanHandStep(player, session);
                return;
            case PURCHASE_PRICE:
                Double price = parsePrice(input);
                if (price == null || price < 0) {
                    messages.send(player, "banhand-invalid-price");
                    promptBanHandStep(player, session);
                    return;
                }
                session.purchase.price = price;
                session.step = BanHandWizardStep.PURCHASE_DURATIONS;
                promptBanHandStep(player, session);
                return;
            case PURCHASE_DURATIONS:
                if (!input.equalsIgnoreCase("skip") && !input.equalsIgnoreCase("无") && !input.equals("0")) {
                    List<BanRule.PurchaseTier> tiers = parseDurationTiers(input);
                    if (tiers == null) {
                        messages.send(player, "banhand-invalid-durations");
                        promptBanHandStep(player, session);
                        return;
                    }
                    session.purchase.durations = tiers;
                }
                session.step = BanHandWizardStep.REASON;
                promptBanHandStep(player, session);
                return;
            case REASON:
                if (!input.equalsIgnoreCase("skip") && !input.equalsIgnoreCase("无")) {
                    session.reason = input;
                }
                finalizeBanHand(player, session);
                return;
            default:
                return;
        }
    }

    private void finalizeBanHand(Player player, BanHandWizardSession session) {
        banHandSessions.remove(player.getUniqueId());
        FileConfiguration config = getConfig();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null) {
            entries = config.createSection("entries");
        }
        String savedKey = saveItemRule(session.item, entries, session);
        if (savedKey == null) {
            messages.send(player, "banhand-save-failed");
            return;
        }
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
            messages.send(player, "banhand-save-failed");
            return;
        }
        reloadPlugin();
        messages.send(player, "banhand-saved", Map.of("key", savedKey));
    }

    private Boolean parseBoolean(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (normalized.equals("是") || normalized.equals("y") || normalized.equals("yes")
            || normalized.equals("true") || normalized.equals("1")) {
            return true;
        }
        if (normalized.equals("否") || normalized.equals("n") || normalized.equals("no")
            || normalized.equals("false") || normalized.equals("0")) {
            return false;
        }
        return null;
    }

    private Double parsePrice(String input) {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<BanRule.PurchaseTier> parseDurationTiers(String input) {
        String[] parts = input.split(",");
        List<BanRule.PurchaseTier> tiers = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] entry = trimmed.split("=");
            if (entry.length != 2) {
                return null;
            }
            String duration = entry[0].trim();
            String priceRaw = entry[1].trim();
            Double price = parsePrice(priceRaw);
            if (duration.isEmpty() || price == null || price < 0) {
                return null;
            }
            tiers.add(new BanRule.PurchaseTier(duration, price));
        }
        return tiers;
    }

    private void openListInventory(Player player, int page) {
        List<BanRule> rules = ruleManager.getRules().stream()
            .sorted(java.util.Comparator.comparing(BanRule::getKey))
            .collect(Collectors.toList());
        int totalPages = Math.max(1, (int) Math.ceil(rules.size() / (double) LIST_PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        Inventory inventory = Bukkit.createInventory(new ListInventoryHolder(), 54,
            MessageService.colorize(LIST_TITLE_PREFIX + " &7(" + (safePage + 1) + "/" + totalPages + ")"));
        int startIndex = safePage * LIST_PAGE_SIZE;
        for (int slot = 0; slot < LIST_PAGE_SIZE; slot++) {
            int index = startIndex + slot;
            if (index >= rules.size()) {
                break;
            }
            inventory.setItem(slot, buildRuleDisplay(rules.get(index)));
        }
        if (safePage > 0) {
            inventory.setItem(LIST_PREV_SLOT, createNavItem(Material.ARROW, "&b上一页"));
        } else {
            inventory.setItem(LIST_PREV_SLOT, createNavItem(Material.GRAY_STAINED_GLASS_PANE, "&8上一页"));
        }
        inventory.setItem(LIST_INFO_SLOT, createNavItem(Material.PAPER, "&7共计 &f" + rules.size() + " &7条"));
        if (safePage < totalPages - 1) {
            inventory.setItem(LIST_NEXT_SLOT, createNavItem(Material.ARROW, "&b下一页"));
        } else {
            inventory.setItem(LIST_NEXT_SLOT, createNavItem(Material.GRAY_STAINED_GLASS_PANE, "&8下一页"));
        }
        listInventories.put(player.getUniqueId(), inventory);
        listPages.put(player.getUniqueId(), safePage);
        player.openInventory(inventory);
    }

    private void handleListInventoryClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        player.updateInventory();
        int slot = event.getRawSlot();
        int page = listPages.getOrDefault(player.getUniqueId(), 0);
        if (slot == LIST_PREV_SLOT) {
            openListInventory(player, page - 1);
            return;
        }
        if (slot == LIST_NEXT_SLOT) {
            openListInventory(player, page + 1);
        }
    }

    private boolean isListInventory(Inventory inventory, Player player) {
        if (inventory == null) {
            return false;
        }
        if (inventory.getHolder() instanceof ListInventoryHolder) {
            return true;
        }
        Inventory stored = listInventories.get(player.getUniqueId());
        return stored != null && stored.equals(inventory);
    }

    private void openAdminInventory(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 54, MessageService.colorize("&3DivineBanItem 管理"));
        inventory.setItem(ADMIN_SAVE_SLOT, createSaveButton());
        List<BanRule> rules = ruleManager.getRules().stream()
            .sorted(java.util.Comparator.comparing(BanRule::getKey))
            .collect(Collectors.toList());
        Map<Integer, String> ruleSlots = new HashMap<>();
        int slot = 0;
        for (BanRule rule : rules) {
            if (slot == ADMIN_SAVE_SLOT) {
                break;
            }
            inventory.setItem(slot, buildAdminRuleDisplay(rule));
            ruleSlots.put(slot, rule.getKey());
            slot++;
        }
        adminInventories.put(player.getUniqueId(), inventory);
        adminRuleSlots.put(player.getUniqueId(), ruleSlots);
        player.openInventory(inventory);
    }

    private ItemStack buildRuleDisplay(BanRule rule) {
        ItemStack item = ItemKeyUtils.createItemStack(rule.getItemKey(), 1);
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageService.colorize("&f" + rule.getKey()));
            List<String> lore = new java.util.ArrayList<>();
            lore.add(MessageService.colorize("&8物品: &7" + rule.getItemKey()));
            lore.add(MessageService.colorize("&8封禁类型: &7" + formatActionList(rule.getActions())));
            String reason = rule.getReason() == null || rule.getReason().isBlank() ? "未填写" : rule.getReason();
            lore.add(MessageService.colorize("&8封禁原因: &7" + reason));
            BanRule.PurchaseOptions purchase = rule.getPurchase();
            if (purchase.enabled) {
                lore.add(MessageService.colorize("&8永久价格: &7" + formatPrice(purchase.price)));
                if (!purchase.durations.isEmpty()) {
                    lore.add(MessageService.colorize("&8临时价格: &7" + formatDurationPrices(purchase.durations)));
                }
            } else {
                lore.add(MessageService.colorize("&8许可: &7不可购买"));
            }
            meta.setLore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildAdminRuleDisplay(BanRule rule) {
        ItemStack item = buildRuleDisplay(rule);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            List<String> updated = new java.util.ArrayList<>();
            if (lore != null) {
                updated.addAll(lore);
            }
            updated.add(MessageService.colorize("&6双击移除该封禁"));
            meta.setLore(updated);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean removeRule(String key) {
        FileConfiguration config = getConfig();
        ConfigurationSection entries = config.getConfigurationSection("entries");
        if (entries == null || !entries.contains(key)) {
            return false;
        }
        entries.set(key, null);
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException ignored) {
            return false;
        }
        reloadPlugin();
        return true;
    }

    private ItemStack createNavItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageService.colorize(name));
            String lore = material == Material.GRAY_STAINED_GLASS_PANE ? "&8不可用" : "&8点击切换页面";
            meta.setLore(List.of(MessageService.colorize(lore)));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatActionList(BanRule.Actions actions) {
        List<String> parts = new java.util.ArrayList<>();
        if (actions.use) {
            parts.add("使用");
        }
        if (actions.place) {
            parts.add("放置");
        }
        if (actions.craft) {
            parts.add("合成");
        }
        if (actions.smelt) {
            parts.add("熔炼");
        }
        if (actions.pickup) {
            parts.add("拾取");
        }
        if (actions.drop) {
            parts.add("丢弃");
        }
        if (parts.isEmpty()) {
            return "无";
        }
        return String.join("、", parts);
    }

    private String formatDurationPrices(List<BanRule.PurchaseTier> tiers) {
        return tiers.stream()
            .map(tier -> tier.getDuration() + "=" + formatPrice(tier.getPrice()))
            .collect(Collectors.joining(", "));
    }

    private String formatPrice(double price) {
        if (price <= 0) {
            return "免费";
        }
        if (price == Math.rint(price)) {
            return String.valueOf((long) price);
        }
        return String.format(Locale.ROOT, "%.2f", price);
    }

    private void sendBlocked(Player player, String messageKey) {
        if (!shouldSendBlockedMessage(player, messageKey)) {
            return;
        }
        messages.send(player, messageKey);
    }

    private boolean shouldSendBlockedMessage(Player player, String messageKey) {
        long cooldown = getConfig().getLong("settings.block-message-cooldown-ms", 2000L);
        if (cooldown <= 0) {
            return true;
        }
        Map<String, Long> perKey = messageCooldowns.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        long now = System.currentTimeMillis();
        Long last = perKey.get(messageKey);
        if (last != null && now - last < cooldown) {
            return false;
        }
        perKey.put(messageKey, now);
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack stack = event.getItem();
            if (shouldBlock(player, stack, ActionContext.use())) {
                sendBlocked(player, "blocked-use");
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemInHand();
        if (shouldBlock(player, stack, ActionContext.place())) {
            sendBlocked(player, "blocked-place");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        for (HumanEntity viewer : event.getViewers()) {
            if (viewer instanceof Player) {
                Player player = (Player) viewer;
                if (shouldBlock(player, result, ActionContext.craft())) {
                    event.getInventory().setResult(new ItemStack(Material.AIR));
                    return;
                }
            }
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
            sendBlocked(player, "blocked-craft");
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
            sendBlocked(player, "blocked-pickup");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemDrop().getItemStack();
        if (shouldBlock(player, stack, ActionContext.drop())) {
            sendBlocked(player, "blocked-drop");
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
            player.sendMessage(MessageService.colorize("&c请在输出格放入结果物品。"));
            return;
        }
        List<Integer> inputSlots = RecipeEditorSession.INPUT_SLOTS.stream().sorted().collect(Collectors.toList());
        List<ItemStack> inputs = inputSlots.stream()
            .map(slot -> session.getInventory().getItem(slot))
            .collect(Collectors.toList());
        boolean hasIngredient = inputs.stream().anyMatch(item -> item != null && item.getType() != Material.AIR);
        if (!hasIngredient) {
            player.sendMessage(MessageService.colorize("&c请至少放入一个配方材料。"));
            return;
        }
        RecipeOverride override = buildOverride(session, result, inputs);
        boolean saved = ruleManager.addOverrideAndPersist(override);
        if (!saved) {
            player.sendMessage(MessageService.colorize("&c保存配方替换失败。"));
            return;
        }
        applyRecipeOverrides();
        player.sendMessage(MessageService.colorize("&a配方替换已保存: &f" + override.getKey()));
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

    private enum BanHandWizardStep {
        USE,
        PLACE,
        CRAFT,
        SMELT,
        PICKUP,
        DROP,
        REMOVE_BUKKIT,
        REMOVE_FORGE,
        PURCHASE_ENABLED,
        PURCHASE_PRICE,
        PURCHASE_DURATIONS,
        REASON
    }

    private static final class BanHandWizardSession {
        private final ItemStack item;
        private final BanRule.Actions actions = new BanRule.Actions();
        private final BanRule.Recipes recipes = new BanRule.Recipes();
        private final BanRule.PurchaseOptions purchase = new BanRule.PurchaseOptions();
        private BanHandWizardStep step = BanHandWizardStep.USE;
        private String reason = "";

        private BanHandWizardSession(ItemStack item) {
            this.item = item;
        }
    }
}
