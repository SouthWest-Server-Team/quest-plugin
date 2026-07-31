package com.xinantown.quest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Base64;
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
                Object raw = cfg.get(key);
                ItemStack item;
                if (raw instanceof String s && !s.isEmpty()) {
                    item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
                } else if (raw instanceof ConfigurationSection) {
                    item = cfg.getItemStack(key);
                } else {
                    item = null;
                }
                if (item != null && item.getType() != org.bukkit.Material.AIR) {
                    items.put(slot, item);
                }
            } catch (Exception ex) {
                logger.warning("Failed to load warehouse item at slot " + key + ": " + ex.getMessage());
            }
        }
        return items;
    }

    public void save(UUID questId, Map<Integer, ItemStack> items) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var e : items.entrySet()) {
            if (e.getValue() != null && e.getValue().getType() != org.bukkit.Material.AIR) {
                try {
                    cfg.set(String.valueOf(e.getKey()), Base64.getEncoder().encodeToString(e.getValue().clone().serializeAsBytes()));
                } catch (Exception ex) {
                    logger.warning("Failed to serialize warehouse item at slot " + e.getKey() + ": " + ex.getMessage());
                }
            }
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
