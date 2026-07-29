package com.xinantown.quest;

import com.xinantown.quest.model.Quest;
import com.xinantown.quest.model.QuestStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public class QuestDataManager {

    private final Logger logger;
    private final File dataFile;
    private List<Quest> cache;

    public QuestDataManager(File dataFile, Logger logger) {
        this.dataFile = dataFile;
        this.logger = logger;
    }

    public synchronized List<Quest> loadAll() {
        if (cache != null) return new ArrayList<>(cache);
        cache = loadFromDisk();
        return new ArrayList<>(cache);
    }

    public synchronized void saveAll(List<Quest> quests) {
        this.cache = new ArrayList<>(quests);
        saveToDisk(quests);
    }

    private List<Quest> loadFromDisk() {
        List<Quest> result = new ArrayList<>();
        if (!dataFile.exists()) return result;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = cfg.getConfigurationSection("quests");
        if (sec == null) return result;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection q = sec.getConfigurationSection(key);
            if (q == null) continue;
            try {
                result.add(new Quest(
                        UUID.fromString(key),
                        q.getString("title", ""),
                        UUID.fromString(q.getString("publisherId", "")),
                        q.getString("publisherName", ""),
                        q.getBoolean("isTownQuest", false),
                        q.getString("questType", "material"),
                        q.getString("description", ""),
                        q.getDouble("reward", 0),
                        q.getDouble("deposit", 0),
                        q.getLong("acceptDeadline", 0),
                        q.getLong("completeDeadline", 0),
                        QuestStatus.valueOf(q.getString("status", "OPEN")),
                        q.contains("acceptorId") ? UUID.fromString(q.getString("acceptorId")) : null,
                        q.getString("acceptorName", null),
                        q.getBoolean("isTownAcceptor", false),
                        q.getInt("rejectCount", 0)));
            } catch (Exception e) {
                logger.warning("Skipping invalid quest entry: " + key);
            }
        }
        return result;
    }

    private void saveToDisk(List<Quest> quests) {
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection sec = cfg.createSection("quests");
        for (Quest q : quests) {
            ConfigurationSection qs = sec.createSection(q.id().toString());
            qs.set("title", q.title());
            qs.set("publisherId", q.publisherId().toString());
            qs.set("publisherName", q.publisherName());
            qs.set("isTownQuest", q.isTownQuest());
            qs.set("questType", q.questType());
            qs.set("description", q.description());
            qs.set("reward", q.reward());
            qs.set("deposit", q.deposit());
            qs.set("acceptDeadline", q.acceptDeadline());
            qs.set("completeDeadline", q.completeDeadline());
            qs.set("status", q.status().name());
            if (q.acceptorId() != null) qs.set("acceptorId", q.acceptorId().toString());
            if (q.acceptorName() != null) qs.set("acceptorName", q.acceptorName());
            qs.set("isTownAcceptor", q.isTownAcceptor());
            qs.set("rejectCount", q.rejectCount());
        }
        try { cfg.save(dataFile); } catch (IOException e) {
            logger.severe("Failed to save quests: " + e.getMessage());
        }
    }
}
