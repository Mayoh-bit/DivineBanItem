package com.divinebanitem;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public class MessageService {
    private static final Pattern COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private final FileConfiguration config;

    public MessageService(FileConfiguration config) {
        this.config = config;
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(colorize(get(key)));
    }

    public void send(CommandSender sender, String key, Map<String, String> replacements) {
        sender.sendMessage(colorize(apply(get(key), replacements)));
    }

    public String get(String key) {
        String value = config.getString(key, key);
        String prefix = config.getString("prefix", "");
        if (!value.toLowerCase(Locale.ROOT).startsWith("&") && !value.contains("[")) {
            return prefix + value;
        }
        if ("prefix".equals(key)) {
            return value;
        }
        return prefix + value;
    }

    public static String apply(String message, Map<String, String> replacements) {
        String result = message;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    public static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
