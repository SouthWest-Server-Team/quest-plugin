package com.xinantown.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persists shared warehouse contents per quest.
 */
public class WarehouseManager {

    private final Logger logger;
    private final File folder;

    public WarehouseManager(File dataFolder, Logger logger) {
        this.folder = new File(dataFolder, "warehouses");
        this.logger = logger;
        if (!this.folder.exists()) this.folder.mkdirs();
    }

    public Map<Integer, ItemStack> load(UUID questId) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        File f = new File(folder, questId + ".yml");
        if (!f.exists()) return items;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String key : cfg.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                var serialized = cfg.getConfigurationSection(key);
                if (serialized != null) {
                    items.put(slot, ItemStack.deserialize(serialized.getValues(false)));
                }
            } catch (Exception ignored) {}
        }
        return items;
    }

    public void save(UUID questId, Map<Integer, ItemStack> items) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var e : items.entrySet()) {
            cfg.createSection(String.valueOf(e.getKey()), e.getValue().serialize());
        }
        try { cfg.save(new File(folder, questId + ".yml")); } catch (IOException ex) {
            logger.severe("Failed to save warehouse: " + ex.getMessage());
        }
    }

    public void delete(UUID questId) {
        File f = new File(folder, questId + ".yml");
        if (f.exists()) f.delete();
    }
}
