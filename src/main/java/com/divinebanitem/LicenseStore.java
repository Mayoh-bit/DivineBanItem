package com.divinebanitem;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class LicenseStore {
    private final File file;
    private FileConfiguration config;

    public LicenseStore(File dataFolder) {
        this.file = new File(dataFolder, "licenses.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try {
                file.createNewFile();
            } catch (IOException ignored) {
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException ignored) {
        }
    }

    public Map<String, Long> getLicenses(UUID uuid) {
        String path = "licenses." + uuid.toString();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, Long> results = new HashMap<>();
        for (String key : section.getKeys(false)) {
            results.put(key, section.getLong(key, -1L));
        }
        return results;
    }

    public void grant(UUID uuid, String key, long expiresAt) {
        config.set("licenses." + uuid.toString() + "." + key, expiresAt);
        save();
    }

    public void revoke(UUID uuid, String key) {
        String path = "licenses." + uuid.toString() + "." + key;
        config.set(path, null);
        save();
    }

    public boolean hasValidLicense(UUID uuid, String key) {
        String path = "licenses." + uuid.toString() + "." + key;
        if (!config.contains(path)) {
            return false;
        }
        long expiresAt = config.getLong(path, -1L);
        if (expiresAt <= 0L) {
            return true;
        }
        if (System.currentTimeMillis() > expiresAt) {
            config.set(path, null);
            save();
            return false;
        }
        return true;
    }
}
