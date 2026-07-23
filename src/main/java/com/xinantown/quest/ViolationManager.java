package com.xinantown.quest;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Tracks violation counts per town for CasusBelli integration.
 * Violated towns cannot accept town quests for 1 day × violationCount.
 */
public class ViolationManager {

    private final Logger logger;
    private final File file;
    private final Map<String, ViolationRecord> records = new HashMap<>();

    public ViolationManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "violations.yml");
        this.logger = logger;
        load();
    }

    public void addViolation(String townName) {
        ViolationRecord r = records.computeIfAbsent(townName.toLowerCase(), k -> new ViolationRecord());
        r.count++;
        r.bannedUntil = System.currentTimeMillis() + 86400000L * r.count; // 1 day × N
        save();
    }

    public boolean isBanned(String townName) {
        ViolationRecord r = records.get(townName.toLowerCase());
        if (r == null) return false;
        if (System.currentTimeMillis() >= r.bannedUntil) {
            records.remove(townName.toLowerCase());
            save();
            return false;
        }
        return true;
    }

    public long getBanEnd(String townName) {
        ViolationRecord r = records.get(townName.toLowerCase());
        return r != null ? r.bannedUntil : 0;
    }

    public void notifyViolation(String townName, Player player) {
        long end = getBanEnd(townName);
        if (end > 0) {
            long hours = (end - System.currentTimeMillis()) / 3600000;
            if (player != null) {
                player.sendMessage("§c[违约] 城邦 " + townName + " 处于违约状态，剩余 " + hours + " 小时，无法接取城邦委托。");
            }
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            ViolationRecord r = new ViolationRecord();
            r.count = cfg.getInt(key + ".count", 0);
            r.bannedUntil = cfg.getLong(key + ".bannedUntil", 0);
            records.put(key, r);
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var e : records.entrySet()) {
            cfg.set(e.getKey() + ".count", e.getValue().count);
            cfg.set(e.getKey() + ".bannedUntil", e.getValue().bannedUntil);
        }
        try { cfg.save(file); } catch (IOException ex) {
            logger.severe("Failed to save violations: " + ex.getMessage());
        }
    }

    private static class ViolationRecord {
        int count;
        long bannedUntil;
    }
}
