package com.divinebanitem;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class DivineBanItem extends JavaPlugin implements Listener {
    private BanRuleManager ruleManager;
    private LicenseStore licenseStore;
    private MessageService messages;
    private Economy economy;
    private String bypassPermission;

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
        this.ruleManager = new BanRuleManager();
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
            default:
                sendHelp(sender);
                return true;
        }
    }

    private List<String> handleTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("help", "reload", "nbt", "list", "info", "buy", "grant", "revoke");
            return subs.stream()
                .filter(entry -> entry.startsWith(args[0].toLowerCase(Locale.ROOT)))
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
        sender.sendMessage(MessageService.colorize("&e/dbi reload &7- Reload config"));
        sender.sendMessage(MessageService.colorize("&e/dbi nbt &7- Show held item NBT"));
        sender.sendMessage(MessageService.colorize("&e/dbi list &7- List rule keys"));
        sender.sendMessage(MessageService.colorize("&e/dbi info <key> &7- Show rule"));
        sender.sendMessage(MessageService.colorize("&e/dbi buy <key> [duration] &7- Buy license"));
        sender.sendMessage(MessageService.colorize("&e/dbi grant <player> <key> [duration] &7- Grant license"));
        sender.sendMessage(MessageService.colorize("&e/dbi revoke <player> <key> &7- Revoke license"));
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
